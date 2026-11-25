package com.shomerharosh.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingHeadService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingBtn: TextView? = null
    private var resultCard: View? = null
    private var screenReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var networkExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Guard flags — all accessed on main thread only
    private var isScanning = false
    private var isDestroyed = false
    private var isFloatingButtonEnabled = true

    // Tracks the last known foreground package so we can decide visibility
    // Written by ScreenCaptureAccessibilityService via notifyForegroundPackage()
    @Volatile private var currentForegroundPackage: String = ""

    companion object {
        /** Packages for which the floating button should be visible */
        val MESSAGING_PACKAGES: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",               // WhatsApp Business
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging",   // Samsung Messages
            "com.android.mms",                 // AOSP MMS/SMS
            "com.android.messaging",           // AOSP default messenger
            "org.telegram.messenger",          // Telegram
            "org.telegram.messenger.web",
            "com.viber.voip",                  // Viber
            "com.facebook.orca",               // Messenger
            "com.facebook.mlite",
            "com.instagram.android",           // Instagram DMs
            "com.snapchat.android",
            "com.discord"
        )

        const val ACTION_DISABLE_OVERLAY = "com.shomerharosh.app.ACTION_DISABLE_OVERLAY"

        /** Packages that must never replace the current foreground package (IMEs, system UI, dialogs). */
        val TRANSIENT_PACKAGES: Set<String> = setOf(
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.touchtype.swiftkey",
            "com.swiftkey.swiftkeyconfigurator",
            "com.google.android.gms",
            "com.android.systemui",
            "com.android.settings"
        )

        const val PREF_FLOATING_BTN_ENABLED = "floating_btn_enabled"

        /** Called by ScreenCaptureAccessibilityService on each window change. */
        @Volatile var instance: FloatingHeadService? = null
            private set

        fun notifyForegroundPackage(pkg: String) {
            instance?.onForegroundPackageChanged(pkg)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isDestroyed = false
        ensureNetworkExecutor()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        isFloatingButtonEnabled = readEnabledPref()
        startForegroundNotification()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle explicit disable command (sent from MainActivity toggle)
        if (intent?.action == ACTION_DISABLE_OVERLAY) {
            disableAndRemoveOverlay()
            stopSelf()
            return START_NOT_STICKY
        }

        isDestroyed = false
        ensureNetworkExecutor()
        isFloatingButtonEnabled = readEnabledPref()
        startForegroundNotification()
        registerScreenReceiver()          // idempotent
        evaluateAndUpdateButtonVisibility()
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        instance = null
        handler.removeCallbacksAndMessages(null)
        try { networkExecutor.shutdownNow() } catch (_: Exception) {}
        unregisterScreenReceiverSafe()
        safeRemoveView(floatingBtn)
        floatingBtn = null
        removeResultCard()
        super.onDestroy()
    }

    // ─── Foreground-app visibility filter ────────────────────────────────────

    /**
     * Called (from any thread) by [ScreenCaptureAccessibilityService] whenever the
     * foreground window/package changes.  We always dispatch to main thread.
     */
    fun onForegroundPackageChanged(pkg: String) {
        // Ignore empty/IME/transient window changes so the button doesn't flicker or hide
        // while the user stays inside a messaging app with a soft keyboard open.
        if (pkg.isBlank()) {
            // Service gone: treat as non-messaging and hide.
            currentForegroundPackage = ""
            handler.post { evaluateAndUpdateButtonVisibility() }
            return
        }
        if (pkg == currentForegroundPackage) return
        if (TRANSIENT_PACKAGES.contains(pkg)) return
        currentForegroundPackage = pkg
        handler.post { evaluateAndUpdateButtonVisibility() }
    }

    /**
     * Show/hide the floating button based on:
     *  1. User toggle pref (isFloatingButtonEnabled)
     *  2. SYSTEM_ALERT_WINDOW permission still granted
     *  3. Foreground app is in MESSAGING_PACKAGES
     */
    private fun evaluateAndUpdateButtonVisibility() {
        if (isDestroyed) return

        val permOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
        val shouldShow = isFloatingButtonEnabled &&
                permOk &&
                MESSAGING_PACKAGES.contains(currentForegroundPackage)

        if (shouldShow) {
            showFloatingButtonIfNeeded()
        } else {
            hideFloatingButton()
        }
    }

    // ─── Overlay add / remove ─────────────────────────────────────────────────

    private fun showFloatingButtonIfNeeded() {
        if (isDestroyed || floatingBtn != null) return          // already on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) return             // permission revoked

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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

        val btn = TextView(this).apply {
            text = "🛡️"
            textSize = 28f
            gravity = Gravity.CENTER
            background = bg
            setTextColor(Color.WHITE)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val newX = initialX + (event.rawX - initialTouchX).toInt()
                        val newY = initialY + (event.rawY - initialTouchY).toInt()
                        params.x = newX
                        params.y = newY
                        // Guard: view might have been detached by now
                        if (v.isAttachedToWindow) {
                            try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)
                        if (dx < 15 && dy < 15) triggerScan()
                        true
                    }
                    else -> false
                }
            }
        }

        try {
            windowManager.addView(btn, params)
            floatingBtn = btn
        } catch (_: Exception) {
            // WindowManager rejected the view (e.g. permission revoked between check and add)
        }
    }

    /** Removes the floating button from WindowManager without touching resultCard. */
    private fun hideFloatingButton() {
        safeRemoveView(floatingBtn)
        floatingBtn = null
    }

    /** Full disable: hides button, clears pref, closes result card. */
    private fun disableAndRemoveOverlay() {
        isFloatingButtonEnabled = false
        saveEnabledPref(false)
        handler.post {
            hideFloatingButton()
            removeResultCard()
        }
    }

    // ─── Screen on/off receiver ───────────────────────────────────────────────

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return          // already registered

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (isDestroyed) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        startForegroundNotification()
                        evaluateAndUpdateButtonVisibility()
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // Hide everything when screen locks
                        hideFloatingButton()
                        removeResultCard()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterScreenReceiverSafe() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
        }
        screenReceiver = null
    }

    // ─── Scan flow ────────────────────────────────────────────────────────────

    private fun triggerScan() {
        if (isDestroyed || isScanning) return       // prevent double-tap
        attemptScanWithRetry(retriesLeft = 6)
    }

    private fun attemptScanWithRetry(retriesLeft: Int) {
        if (isDestroyed || isScanning) return

        val service = ScreenCaptureAccessibilityService.instance
        if (service == null) {
            if (retriesLeft > 0) {
                floatingBtn?.text = "⏳"
                handler.postDelayed({
                    if (!isDestroyed) {
                        floatingBtn?.text = "🛡️"
                        attemptScanWithRetry(retriesLeft - 1)
                    }
                }, 250)
                return
            }
            // Service not available after retries — open settings
            val openSettingsIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("OPEN_ACCESSIBILITY_SETTINGS", true)
            }
            try { startActivity(openSettingsIntent) } catch (_: Exception) {}
            return
        }

        isScanning = true
        floatingBtn?.visibility = View.INVISIBLE

        handler.postDelayed({
            if (isDestroyed) {
                resetScanState()
                return@postDelayed
            }

            service.captureScreenBase64 { base64Img ->
                handler.post {
                    if (isDestroyed) {
                        resetScanState()
                        return@post
                    }

                    // Always restore button visibility before processing result
                    floatingBtn?.visibility = View.VISIBLE

                    if (base64Img == null) {
                        resetScanState()
                        showResultDialog(
                            "⚠️ שגיאה",
                            "לא הצלחתי לצלם את המסך. ודא שהרשאת הנגישות מופעלת ונסה שוב.",
                            "WARNING"
                        )
                        return@post
                    }

                    floatingBtn?.text = "⏳"
                    ensureNetworkExecutor()

                    networkExecutor.execute {
                        val reply = uploadScreenshotJson(base64Img)
                        handler.post {
                            if (isDestroyed) {
                                resetScanState()
                                return@post
                            }
                            resetScanState()
                            showResultDialog(reply.first, reply.second, reply.third)
                        }
                    }
                }
            }
        }, 250)
    }

    /** Always restores button text + clears isScanning flag. Call on any early exit. */
    private fun resetScanState() {
        isScanning = false
        floatingBtn?.text = "🛡️"
        floatingBtn?.visibility = View.VISIBLE
    }

    // ─── Network ──────────────────────────────────────────────────────────────

    private fun uploadScreenshotJson(imageBase64: String): Triple<String, String, String> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://shomer-harosh.shomer-harosh.workers.dev")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 25000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            val prefs = getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
            val userPhone = prefs.getString("user_phone", "") ?: ""
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_android"

            val payload = JSONObject().apply {
                put("image_base64", imageBase64)
                put("device_id", deviceId)
                if (userPhone.isNotEmpty()) put("phone_number", userPhone)
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (body.isBlank()) {
                return Triple("⚠️ תגובה ריקה", "השרת החזיר תשובה ריקה. נסה שוב.", "WARNING")
            }

            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                return Triple(
                    if (code >= 400) "🚨 שגיאת שרת ($code)" else "⚠️ שגיאת עיבוד",
                    body.take(300),
                    if (code >= 400) "DANGER" else "WARNING"
                )
            }

            val title = json.optString("alert_title", "תוצאת בדיקה")
            val headline = json.optString("headline", "")
            val explanation = json.optString("simple_explanation", body)
            val fullMsg = if (headline.isNotEmpty()) "$headline\n\n$explanation" else explanation

            val level = when {
                title.contains("✅") -> "SAFE"
                title.contains("🚨") || code >= 400 -> "DANGER"
                else -> "WARNING"
            }
            Triple(title, fullMsg, level)
        } catch (_: IOException) {
            Triple("⚠️ שגיאת תקשורת", "לא ניתן להתחבר לשרת. בדוק חיבור אינטרנט.", "WARNING")
        } catch (_: Exception) {
            Triple("⚠️ שגיאה", "אירעה שגיאה בעת בדיקת התמונה.", "WARNING")
        } finally {
            conn?.disconnect()
        }
    }

    // ─── Result card ──────────────────────────────────────────────────────────

    private fun removeResultCard() {
        safeRemoveView(resultCard)
        resultCard = null
    }

    private fun showResultDialog(title: String, message: String, level: String) {
        if (isDestroyed) return
        removeResultCard()

        val bgColor = when (level) {
            "SAFE"   -> Color.parseColor("#14532D")
            "DANGER" -> Color.parseColor("#7F1D1D")
            else     -> Color.parseColor("#92400E")
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

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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

        closeBtn.setOnClickListener { removeResultCard() }
        resultCard = card

        try {
            windowManager.addView(card, params)
        } catch (_: Exception) {
            resultCard = null
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val channelId = "shomer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "שומר הראש", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("שומר הראש פעיל")
            .setContentText("לחץ על הכפתור הצף בוואטסאפ/הודעות לבדיקת הודעה חשודה")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(101, notification)
    }

    /** WindowManager-safe removal — catches all exceptions. */
    private fun safeRemoveView(view: View?) {
        view ?: return
        try {
            if (view.isAttachedToWindow) windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun ensureNetworkExecutor() {
        if (networkExecutor.isShutdown) {
            networkExecutor = Executors.newSingleThreadExecutor()
        }
    }

    private fun readEnabledPref(): Boolean =
        getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_FLOATING_BTN_ENABLED, true)

    private fun saveEnabledPref(enabled: Boolean) =
        getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_FLOATING_BTN_ENABLED, enabled).apply()
}
