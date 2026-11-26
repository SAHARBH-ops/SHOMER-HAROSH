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

        // ── Tip text ──────────────────────────────────────────────────────────
        val tipText = TextView(this).apply {
            text = "💡 הגדרה חד-פעמית: מאשרים הצגה מעל אפליקציות והפעלה בנגישות.\n" +
                    "הכפתור יופיע רק בתוך אפליקציות ווטסאפ, הודעות, טלגרם ודומות."
            textSize = 15f
            setTextColor(Color.parseColor("#475569"))
            setPadding(0, (18 * density).toInt(), 0, 0)
            gravity = Gravity.CENTER
        }

        // ── Floating button mode ────────────────────────────────────────────
        val modeLabel = TextView(this).apply {
            text = "3. מצב כפתור צף:"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.START
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (10 * density).toInt())
        }

        val currentMode = prefs.getString(FloatingHeadService.PREF_FLOATING_BTN_MODE, "messaging") ?: "messaging"
        var selectedModeButton: Button? = null

        fun makeModeButton(mode: String, label: String): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(
                    (12 * density).toInt(), (10 * density).toInt(),
                    (12 * density).toInt(), (10 * density).toInt()
                )
                val selected = mode == currentMode
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(if (selected) Color.parseColor("#1D4ED8") else Color.parseColor("#CBD5E1"))
                }
                setOnClickListener {
                    prefs.edit().putString(FloatingHeadService.PREF_FLOATING_BTN_MODE, mode).apply()
                    selectedModeButton?.let { b ->
                        b.background = GradientDrawable().apply {
                            cornerRadius = 10 * density
                            setColor(Color.parseColor("#CBD5E1"))
                        }
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = 10 * density
                        setColor(Color.parseColor("#1D4ED8"))
                    }
                    selectedModeButton = this
                    tipText.text = when (mode) {
                        "always" -> "💡 הכפתור הצף תמיד גלוי, בכל האפליקציות."
                        "off" -> "💡 הכפתור הצף כבוי והוסר מהמסך."
                        else -> "💡 הכפתור יופיע רק בתוך ווטסאפ, הודעות, טלגרם ודומות."
                    }
                    if (mode == "off") {
                        sendDisableCommand()
                    } else {
                        activateFloatingButtonAndMinimize()
                    }
                }
            }
        }

        val btnAlways = makeModeButton("always", "תמיד דולק")
        val btnMessaging = makeModeButton("messaging", "רק בוואטסאפ ובהודעות")
        val btnOff = makeModeButton("off", "כבוי")

        selectedModeButton = when (currentMode) {
            "always" -> btnAlways
            "off" -> btnOff
            else -> btnMessaging
        }

        modeRow.addView(btnAlways)
        modeRow.addView(btnMessaging)
        modeRow.addView(btnOff)

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
                            val msg = try {
                                val json = org.json.JSONObject(body)
                                val plan = json.optString("plan", "FREE")
                                val used = json.optInt("used", 0)
                                val limit = json.optInt("limit", 0)
                                val remaining = json.optInt("remaining", 0)
                                if (plan.equals("PRO", ignoreCase = true)) {
                                    "מסלול PRO פעיל — נותרו $remaining מתוך $limit סריקות החודש."
                                } else {
                                    "מסלול חינמי — נותרו $remaining מתוך $limit סריקות החודש."
                                }
                            } catch (e: Exception) {
                                "לא ניתן לקרוא את סטטוס המנוי מהשרת."
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
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
        layout.addView(modeLabel)
        layout.addView(modeRow)
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
