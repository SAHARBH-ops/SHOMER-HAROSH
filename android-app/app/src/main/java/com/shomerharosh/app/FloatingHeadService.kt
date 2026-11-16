package com.shomerharosh.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class FloatingHeadService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingBtn: TextView? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        val code = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (code == Activity.RESULT_OK && data != null) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(code, data)
            setupVirtualDisplay()
            showFloatingButton()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "shomer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "שומר הראש", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("שומר הראש פעיל")
            .setContentText("לחץ על הכפתור הצף לבדיקת הודעה")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(101, notification)
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ShomerCapture", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun showFloatingButton() {
        if (floatingBtn != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            150, 150,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
        }

        floatingBtn = TextView(this).apply {
            text = "🛡️"
            textSize = 26f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            var initialX = 0; var initialY = 0
            var initialTouchX = 0f; var initialTouchY = 0f
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(this, params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.rawX - initialTouchX) < 15 && Math.abs(event.rawY - initialTouchY) < 15) {
                            triggerScan()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        windowManager.addView(floatingBtn, params)
    }

    private fun triggerScan() {
        if (isScanning) return
        isScanning = true
        floatingBtn?.visibility = View.INVISIBLE

        handler.postDelayed({
            val jpegBytes = captureScreenJpeg()
            floatingBtn?.visibility = View.VISIBLE
            floatingBtn?.text = "⏳"

            if (jpegBytes == null) {
                isScanning = false
                floatingBtn?.text = "🛡️"
                showResultDialog("שגיאה", "לא הצלחתי לצלם את המסך. נסה שוב.", true)
                return@postDelayed
            }

            Thread {
                val reply = uploadScreenshot(jpegBytes)
                handler.post {
                    isScanning = false
                    floatingBtn?.text = "🛡️"
                    showResultDialog(reply.first, reply.second, reply.third)
                }
            }.start()
        }, 180)
    }

    private fun captureScreenJpeg(): ByteArray? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val fullBmp = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(fullBmp, 0, 0, image.width, image.height)
            val maxW = 1280
            val scaled = if (cropped.width > maxW) {
                val ratio = maxW.toFloat() / cropped.width
                Bitmap.createScaledBitmap(cropped, maxW, (cropped.height * ratio).toInt(), true)
            } else cropped
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun uploadScreenshot(jpegBytes: ByteArray): Triple<String, String, Boolean> {
        return try {
            val boundary = "----ShomerBoundary" + UUID.randomUUID().toString()
            val url = URL("https://shomer-harosh.shomer-harosh.workers.dev")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            val prefs = getSharedPreferences("prefs_shomer", Context.MODE_PRIVATE)
            val licenseKey = prefs.getString("license_key", "") ?: ""
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"

            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"device_id\"\r\n\r\n$deviceId\r\n")
                if (licenseKey.isNotEmpty()) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"license_key\"\r\n\r\n$licenseKey\r\n")
                }
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"screen.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                out.write(jpegBytes)
                out.writeBytes("\r\n--$boundary--\r\n")
            }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().readText()
            val json = JSONObject(body)
            val title = json.optString("alert_title", "תוצאת בדיקה")
            val explanation = json.optString("simple_explanation", body)
            val isDanger = title.contains("הונאה") || title.contains("זהירות") || title.contains("חשד") || conn.responseCode >= 400
            Triple(title, explanation, isDanger)
        } catch (e: Exception) {
            Triple("שגיאת תקשורת", "לא ניתן להתחבר לשרת. בדוק חיבור אינטרנט.", true)
        }
    }

    private fun showResultDialog(title: String, message: String, isDanger: Boolean) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(if (isDanger) Color.parseColor("#7F1D1D") else Color.parseColor("#14532D"))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val msgView = TextView(this).apply {
            text = message
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 36)
            gravity = Gravity.CENTER
        }
        val closeBtn = Button(this).apply {
            text = "סגור"
            textSize = 20f
        }
        card.addView(titleView)
        card.addView(msgView)
        card.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }
        closeBtn.setOnClickListener { windowManager.removeView(card) }
        windowManager.addView(card, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingBtn?.let { windowManager.removeView(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}