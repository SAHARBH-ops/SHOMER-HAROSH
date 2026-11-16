package com.shomerharosh.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText

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
            Toast.makeText(this, "הכפתור הצף הופעל!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "נדרש אישור צילום מסך להפעלה", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("prefs_shomer", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "🛡️ שומר הראש"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 24, 0, 32)
            gravity = Gravity.CENTER
        }

        codeInput = EditText(this).apply {
            hint = "הזן קוד קופון / מנוי VIP (אופציונלי)"
            textSize = 18f
            setText(prefs.getString("license_key", ""))
        }

        val saveBtn = Button(this).apply {
            text = "שמור קוד מנוי"
            textSize = 18f
            setOnClickListener {
                val code = codeInput.text.toString().trim().uppercase()
                prefs.edit().putString("license_key", code).apply()
                updateStatus(code)
                Toast.makeText(this@MainActivity, "הקוד נשמר", Toast.LENGTH_SHORT).show()
            }
        }

        val overlayBtn = Button(this).apply {
            text = "1. אפשר תצוגה מעל אפליקציות"
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
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "יש לאשר קודם תצוגה מעל אפליקציות (שלב 1)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(codeInput)
        layout.addView(saveBtn)
        layout.addView(overlayBtn)
        layout.addView(startBtn)
        setContentView(layout)

        updateStatus(prefs.getString("license_key", "") ?: "")
    }

    private fun updateStatus(code: String) {
        statusText.text = if (code.isNotEmpty()) {
            "מצב פעיל: מנוי VIP ללא הגבלה ($code)"
        } else {
            "מצב פעיל: חינמי (עד 3 בדיקות בחודש)"
        }
    }
}