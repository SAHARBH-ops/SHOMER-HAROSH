package com.shomerharosh.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var phoneInput: EditText

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, FloatingHeadService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "הכפתור הצף הופעל! אפשר לצאת לוואטסאפ או להודעות.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "נדרש אישור צילום מסך להפעלה", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("prefs_shomer", Context.MODE_PRIVATE)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 56, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "🛡️ שומר הראש"
            textSize = 30f
            setTextColor(Color.parseColor("#1E3A8A"))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "הגנה חכמה מהונאות ופישינג בלחיצת כפתור"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        statusText = TextView(this).apply {
            textSize = 17f
            setPadding(24, 20, 24, 20)
            gravity = Gravity.CENTER
        }

        phoneInput = EditText(this).apply {
            hint = "הזן מספר טלפון לזיהוי (למשל 0501234567)"
            textSize = 18f
            inputType = InputType.TYPE_CLASS_PHONE
            gravity = Gravity.CENTER
            setText(prefs.getString("user_phone", ""))
        }

        val saveBtn = Button(this).apply {
            text = "שמור מספר טלפון"
            textSize = 17f
            setOnClickListener {
                val rawPhone = phoneInput.text.toString().replace("[^0-9]".toRegex(), "")
                if (rawPhone.length < 9) {
                    Toast.makeText(this@MainActivity, "נא להזין מספר טלפון תקין (לפחות 9 ספרות)", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("user_phone", rawPhone).apply()
                updateStatus(rawPhone)
                Toast.makeText(this@MainActivity, "מספר הטלפון נשמר בהצלחה", Toast.LENGTH_SHORT).show()
            }
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
        }

        val overlayBtn = Button(this).apply {
            text = "1. אשר תצוגה מעל אפליקציות"
            textSize = 18f
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        val startBtn = Button(this).apply {
            text = "2. הפעל כפתור צף על המסך"
            textSize = 20f
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            setPadding(24, 28, 24, 28)
            setOnClickListener {
                val currentPhone = phoneInput.text.toString().replace("[^0-9]".toRegex(), "")
                if (currentPhone.length < 9) {
                    Toast.makeText(this@MainActivity, "נא להזין ולשמור מספר טלפון לפני ההפעלה", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("user_phone", currentPhone).apply()
                updateStatus(currentPhone)

                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "יש לאשר קודם תצוגה מעל אפליקציות (שלב 1)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        val stopBtn = Button(this).apply {
            text = "כבה כפתור צף"
            textSize = 16f
            setOnClickListener {
                stopService(Intent(this@MainActivity, FloatingHeadService::class.java))
                Toast.makeText(this@MainActivity, "השירות כובה", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(statusText)
        layout.addView(phoneInput)
        layout.addView(saveBtn)
        layout.addView(divider)
        layout.addView(overlayBtn)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        scroll.addView(layout)
        setContentView(scroll)

        updateStatus(prefs.getString("user_phone", "") ?: "")
    }

    private fun updateStatus(phone: String) {
        if (phone.length >= 9) {
            statusText.text = "מזוהה במערכת: $phone\n(סטטוס VIP/PRO מתעדכן אוטומטית מהענן)"
            statusText.setBackgroundColor(Color.parseColor("#DCFCE7"))
            statusText.setTextColor(Color.parseColor("#14532D"))
        } else {
            statusText.text = "נא להזין מספר טלפון לזיהוי והפעלה"
            statusText.setBackgroundColor(Color.parseColor("#EFF6FF"))
            statusText.setTextColor(Color.parseColor("#1E3A8A"))
        }
    }
}