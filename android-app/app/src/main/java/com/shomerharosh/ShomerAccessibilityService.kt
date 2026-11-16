package com.shomerharosh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import kotlin.math.max

class ShomerAccessibilityService : AccessibilityService() {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var overlay: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val workerUrl = "https://shomer-harosh.shomer-harosh.workers.dev"
    private val targets = setOf("com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging")

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val always = getSharedPreferences("shomer_prefs", MODE_PRIVATE).getBoolean("show_always", true)
        if (always || event.packageName?.toString() in targets) showBubble() else hideBubble()
    }

    override fun onInterrupt() = Unit

    private fun showBubble() {
        if (bubble != null) return
        val button = FrameLayout(this).apply {
            setBackgroundColor(0xFF1565C0.toInt())
            addView(TextView(this@ShomerAccessibilityService).apply {
                text = "\u05d1\u05d3\u05d9\u05e7\u05ea\n\u05e2\u05d5\u05e7\u05e5"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            })
        }
        val params = WindowManager.LayoutParams(dp(76), dp(76), overlayType(), PixelFormat.TRANSLUCENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20); y = dp(240)
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) { }
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) { hideBubble(); handler.postDelayed({ captureScreen() }, 200) }; return true }
                }
                return true
            }
        })
        bubble = button
        try { windowManager.addView(button, params) } catch (_: Exception) { bubble = null }
    }

    private fun hideBubble() {
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }
        bubble = null
    }

    private fun captureScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showResult("\u05dc\u05d0 \u05e0\u05d9\u05ea\u05df \u05dc\u05e6\u05dc\u05dd", "WARNING", "\u05d4\u05de\u05db\u05e9\u05d9\u05e8 \u05dc\u05d0 \u05ea\u05d5\u05de\u05da \u05d1\u05e6\u05d9\u05dc\u05d5\u05dd \u05de\u05e1\u05da.")
            return
        takeScreenshot(DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardwareBuffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                hardwareBuffer.close()
                if (bitmap == null) {
                    handler.post { showResult("\u05dc\u05d0 \u05e0\u05d9\u05ea\u05df \u05dc\u05e6\u05dc\u05dd", "WARNING", "\u05d0\u05e4\u05e9\u05e8 \u05dc\u05e0\u05e1\u05d5\u05ea \u05e9\u05d5\u05d1.") }
                    return
                }
                Thread {
                    try {
                        val response = requestAnalysis(encodeBitmap(bitmap))
                        val risk = response.optString("risk_level", "WARNING")
                        val headline = response.optString("headline", "\u05d1\u05d3\u05d9\u05e7\u05d4")
                        val message = response.optString("simple_explanation", "\u05dc\u05d0 \u05d4\u05ea\u05e7\u05d1\u05dc \u05de\u05d9\u05d3\u05e2.")
                        handler.post { showResult(headline, risk, message) }
                    } catch (e: Exception) {
                        Log.e("Shomer", "analysis failed", e)
                        handler.post { showResult("\u05e9\u05d2\u05d9\u05d0\u05d4 \u05d1\u05d1\u05d3\u05d9\u05e7\u05d4", "WARNING", "\u05e0\u05e1\u05d4 \u05e9\u05d5\u05d1 \u05de\u05d0\u05d5\u05d7\u05e8 \u05d9\u05d5\u05ea\u05e8.") }
                    } finally { bitmap.recycle() }
                }.start()
            }
            override fun onFailure(errorCode: Int) {
                showResult("\u05dc\u05d0 \u05e0\u05d9\u05ea\u05df \u05dc\u05e6\u05dc\u05dd", "WARNING", "\u05d0\u05e4\u05e9\u05e8 \u05dc\u05e0\u05e1\u05d5\u05ea \u05e9\u05d5\u05d1.")
            }
        })
    }

    }

    private fun encodeBitmap(bitmap: Bitmap): String {
        val scale = if (bitmap.width > 900) 900f / bitmap.width else 1f
        val resized = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
        val bytes = ByteArrayOutputStream().use { out -> resized.compress(Bitmap.CompressFormat.JPEG, 82, out); out.toByteArray() }
        if (resized !== bitmap) resized.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun requestAnalysis(image: String): JSONObject {
        val connection = (URL(workerUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 30000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(JSONObject().put("image_base64", image).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw IllegalStateException("Worker returned HTTP $code")
            return JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun showResult(headline: String, risk: String, explanation: String) {
        removeOverlay()
        val prefs = getSharedPreferences("shomer_prefs", MODE_PRIVATE)
        val name = prefs.getString("contact_name", "\u05d0\u05d9\u05e9 \u05e7\u05e9\u05e8") ?: "\u05d0\u05d9\u05e9 \u05e7\u05e9\u05e8"
        val phone = prefs.getString("contact_phone", "") ?: ""
        val color = when (risk) { "DANGER" -> 0xFFB71C1C.toInt(); "WARNING" -> 0xFFFFB300.toInt(); else -> 0xFF2E7D32.toInt() }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(color) }
        card.addView(TextView(this).apply { text = headline; textSize = 28f; setTextColor(-1); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(18)) })
        card.addView(TextView(this).apply { text = explanation; textSize = 22f; setTextColor(-1); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20)) })
        if (risk != "SAFE") card.addView(Button(this).apply {
            text = "\u05d7\u05d9\u05d9\u05d2 \u05e2\u05db\u05e9\u05d9\u05d5 \u05d0\u05dc $name"
            setTextColor(-1); setBackgroundColor(0xFFB71C1C.toInt())
            setOnClickListener { if (phone.isNotBlank()) startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); removeOverlay() }
        })
        card.addView(Button(this).apply { text = "\u05e1\u05d2\u05d5\u05e8"; setOnClickListener { removeOverlay() } })
        overlay = FrameLayout(this).apply { setBackgroundColor(0xAA000000.toInt()); addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
        val params = WindowManager.LayoutParams(-1, -1, overlayType(), PixelFormat.TRANSLUCENT, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        try { windowManager.addView(overlay, params) } catch (_: Exception) { overlay = null }
    }

    private fun removeOverlay() { overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) { } }; overlay = null }
    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
