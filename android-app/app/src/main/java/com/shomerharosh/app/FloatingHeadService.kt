package com.shomerharosh.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FloatingHeadService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingBtn: TextView? = null
    private var resultCard: View? = null
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
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, handler)
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
            .setContentText("לחץ על הכפתור הצף לבדיקת הודעה חשודה")
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
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            165, 165,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
        }

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1E3A8A"))
            setStroke(5, Color.WHITE)
        }

        floatingBtn = TextView(this).apply {
            text = "🛡️"
            textSize = 28f
            gravity = Gravity.CENTER
            background = bg
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
                        try { windowManager.updateViewLayout(this, params) } catch (_: Exception) {}
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
            val base64Img = captureScreenBase64()
            floatingBtn?.visibility = View.VISIBLE
            floatingBtn?.text = "⏳"

            if (base64Img == null) {
                isScanning = false
                floatingBtn?.text = "🛡️"
                showResultDialog("⚠️ שגיאה", "לא הצלחתי לצלם את המסך. נסה שוב.", "WARNING")
                return@postDelayed
            }

            Thread {
                val reply = uploadScreenshotJson(base64Img)
                handler.post {
                    isScanning = false
                    floatingBtn?.text = "🛡️"
                    showResultDialog(reply.first, reply.second, reply.third)
                }
            }.start()
        }, 200)
    }

    private fun captureScreenBase64(): String? {
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
            fullBmp.recycle()

            val maxW = 1080
            val scaled = if (cropped.width > maxW) {
                val ratio = maxW.toFloat() / cropped.width
                Bitmap.createScaledBitmap(cropped, maxW, (cropped.height * ratio).toInt(), true).also { cropped.recycle() }
            } else cropped

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            scaled.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun uploadScreenshotJson(imageBase64: String): Triple<String, String, String> {
        return try {
            val url = URL("https://shomer-harosh.shomer-harosh.workers.dev")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 25000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val prefs = getSharedPreferences("prefs_shomer", Context.MODE_PRIVATE)
            val licenseKey = prefs.getString("license_key", "") ?: ""
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"

            val payload = JSONObject().apply {
                put("image_base64", imageBase64)
                put("device_id", deviceId)
                if (licenseKey.isNotEmpty()) put("license_key", licenseKey)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val json = JSONObject(body)
            val title = json.optString("alert_title", "תוצאת בדיקה")
            val headline = json.optString("headline", "")
            val explanation = json.optString("simple_explanation", body)
            val fullMsg = if (headline.isNotEmpty()) "$headline\n\n$explanation" else explanation

            val level = when {
                title.contains("✅") -> "SAFE"
                title.contains("🚨") || conn.responseCode >= 400 -> "DANGER"
                else -> "WARNING"
            }
            Triple(title, fullMsg, level)
        } catch (e: Exception) {
            Triple("⚠️ שגיאת תקשורת", "לא ניתן להתחבר לשרת. בדוק חיבור אינטרנט.", "WARNING")
        }
    }

    private fun showResultDialog(title: String, message: String, level: String) {
        resultCard?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }

        val bgColor = when (level) {
            "SAFE" -> Color.parseColor("#14532D")
            "DANGER" -> Color.parseColor("#7F1D1D")
            else -> Color.parseColor("#92400E")
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(52, 52, 52, 52)
            setBackgroundColor(bgColor)
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
            setPadding(0, 28, 0, 40)
            gravity = Gravity.CENTER
        }
        val closeBtn = Button(this).apply {
            text = "הבנתי, סגור"
            textSize = 19f
        }
        card.addView(titleView)
        card.addView(msgView)
        card.addView(closeBtn)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.65f
            gravity = Gravity.CENTER
        }
        closeBtn.setOnClickListener {
            try { windowManager.removeView(card) } catch (_: Exception) {}
            resultCard = null
        }
        resultCard = card
        windowManager.addView(card, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingBtn?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        resultCard?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}