package com.shomerharosh.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private var waitingForOverlayPermission = false
    private var waitingForAccessibilityPermission = false

    // Keep a reference so we can keep it in sync on resume
    private var floatingBtnSwitch: Switch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)

        val root = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F8FAFC")) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(), (36 * density).toInt(),
                (24 * density).toInt(), (36 * density).toInt()
            )
        }

        // ── Title ────────────────────────────────────────────────────────────
        val title = TextView(this).apply {
            text = "🛡️ שומר הראש"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1D4ED8"))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "הגנה חכמה מהונאות בלחיצת כפתור"
            textSize = 18f
            setTextColor(Color.parseColor("#475569"))
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (24 * density).toInt())
        }

        // ── Phone input ───────────────────────────────────────────────────────
        val phoneLabel = TextView(this).apply {
            text = "1. הזינו מספר טלפון:"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
        }

        val phoneInput = EditText(this).apply {
            hint = "למשל: 0501234567"
            setText(prefs.getString("user_phone", ""))
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 20f
            setPadding(
                (16 * density).toInt(), (14 * density).toInt(),
                (16 * density).toInt(), (14 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.WHITE)
                setStroke((2 * density).toInt(), Color.parseColor("#CBD5E1"))
            }
        }

        val savePhoneBtn = makeButton("שמור מספר טלפון", "#0F172A", density).apply {
            setOnClickListener {
                val p = phoneInput.text.toString().trim()
                if (p.length < 9) {
                    Toast.makeText(this@MainActivity, "נא להזין מספר טלפון תקין", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("user_phone", p).apply()
                    Toast.makeText(this@MainActivity, "מספר הטלפון נשמר!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ── Enable / activate button ──────────────────────────────────────────
        val startBtn = makeButton("2. הפעלת כפתור המגן הצף", "#16A34A", density).apply {
            setOnClickListener {
                val p = phoneInput.text.toString().trim()
                if (p.length >= 9) prefs.edit().putString("user_phone", p).apply()
                startSetupFlow()
            }
        }

        // ── Floating button toggle ────────────────────────────────────────────
        val toggleLabel = TextView(this).apply {
            text = "3. כפתור צף פעיל:"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.START
        }

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }

        val switchLabel = TextView(this).apply {
            text = "הצג כפתור רק בוואטסאפ ובהודעות"
            textSize = 15f
            setTextColor(Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val enabledSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(FloatingHeadService.PREF_FLOATING_BTN_ENABLED, true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(FloatingHeadService.PREF_FLOATING_BTN_ENABLED, isChecked).apply()
                if (isChecked) {
                    // Re-launch the service so it re-reads the pref and shows button if appropriate
                    activateFloatingButtonAndMinimize()
                } else {
                    // Send a disable command to the service — it removes the view immediately
                    sendDisableCommand()
                    Toast.makeText(
                        this@MainActivity,
                        "הכפתור הצף הוסר מהמסך",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        floatingBtnSwitch = enabledSwitch

        toggleRow.addView(switchLabel)
        toggleRow.addView(enabledSwitch)

        // ── Tip text ──────────────────────────────────────────────────────────
        val tipText = TextView(this).apply {
            text = "💡 הגדרה חד-פעמית: מאשרים הצגה מעל אפליקציות והפעלה בנגישות.\n" +
                    "הכפתור יופיע רק בתוך אפליקציות ווטסאפ, הודעות, טלגרם ודומות."
            textSize = 15f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, (18 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER
        }

        val upgradeBtn = makeButton("שדרג ל-PRO — לחץ כאן", "#7C3AED", density).apply {
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shomer-harosh.vercel.app/pro.html")))
            }
        }

        val refreshBtn = makeButton("רענן סטטוס PRO", "#0369A1", density).apply {
            setOnClickListener {
                val p = phoneInput.text.toString().trim()
                if (p.length < 9) {
                    Toast.makeText(this@MainActivity, "נא להזין מספר טלפון תקין", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Thread {
                    try {
                        val url = java.net.URL("https://shomer-harosh.shomer-harosh.workers.dev/quota?phone=" + Uri.encode(p))
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, body, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "שגיאה ברענון סטטוס", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }

        // ── Assemble ──────────────────────────────────────────────────────────
        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(phoneLabel)
        layout.addView(phoneInput)
        layout.addView(space(10, density))
        layout.addView(savePhoneBtn)
        layout.addView(space(22, density))
        layout.addView(startBtn)
        layout.addView(space(22, density))
        layout.addView(toggleLabel)
        layout.addView(toggleRow)
        layout.addView(tipText)
        layout.addView(space(22, density))
        layout.addView(upgradeBtn)
        layout.addView(space(10, density))
        layout.addView(refreshBtn)

        root.addView(layout)
        setContentView(root)

        handleIntentExtras(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()

        // Sync switch state in case permission was revoked externally
        val prefs = getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
        val prefEnabled = prefs.getBoolean(FloatingHeadService.PREF_FLOATING_BTN_ENABLED, true)
        val permOk = !needsOverlayPermission()
        floatingBtnSwitch?.isChecked = prefEnabled && permOk

        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                if (!ScreenCaptureAccessibilityService.isEnabled()) {
                    openAccessibilitySettings()
                } else {
                    activateFloatingButtonAndMinimize()
                }
            }
            return
        }

        if (waitingForAccessibilityPermission) {
            waitingForAccessibilityPermission = false
            if (ScreenCaptureAccessibilityService.isEnabled() && Settings.canDrawOverlays(this)) {
                activateFloatingButtonAndMinimize()
            } else if (!ScreenCaptureAccessibilityService.isEnabled()) {
                Toast.makeText(this, "הרשאת הנגישות עדיין לא הופעלה", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── Setup / permission flow ──────────────────────────────────────────────

    private fun handleIntentExtras(targetIntent: Intent?) {
        if (targetIntent?.getBooleanExtra("OPEN_ACCESSIBILITY_SETTINGS", false) == true) {
            targetIntent.removeExtra("OPEN_ACCESSIBILITY_SETTINGS")
            openAccessibilitySettings()
        }
    }

    private fun startSetupFlow() {
        if (needsOverlayPermission()) {
            waitingForOverlayPermission = true
            Toast.makeText(this, "יש להפעיל את המתג עבור 'שומר הראש' ולחזור", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (!ScreenCaptureAccessibilityService.isEnabled()) {
            openAccessibilitySettings()
            return
        }

        activateFloatingButtonAndMinimize()
    }

    private fun openAccessibilitySettings() {
        waitingForAccessibilityPermission = true
        Toast.makeText(this, "יש להפעיל את 'שומר הראש' תחת נגישות ולחזור", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun activateFloatingButtonAndMinimize() {
        val prefs = getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(FloatingHeadService.PREF_FLOATING_BTN_ENABLED, true).apply()

        val svc = Intent(this, FloatingHeadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "לא ניתן להפעיל את השירות. ודא שהרשאות ניתנו.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "כפתור שומר הראש פעיל! יופיע בוואטסאפ ובאפליקציות הודעות.", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    /**
     * Sends ACTION_DISABLE_OVERLAY to a running FloatingHeadService.
     * If the service is not running this is a no-op (Intent dropped).
     */
    private fun sendDisableCommand() {
        val intent = Intent(this, FloatingHeadService::class.java).apply {
            action = FloatingHeadService.ACTION_DISABLE_OVERLAY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun needsOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)

    private fun makeButton(label: String, hexColor: String, density: Float): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(
                (16 * density).toInt(), (16 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor(hexColor))
            }
        }
    }

    private fun space(dp: Int, density: Float): Space =
        Space(this).apply { minimumHeight = (dp * density).toInt() }
}
