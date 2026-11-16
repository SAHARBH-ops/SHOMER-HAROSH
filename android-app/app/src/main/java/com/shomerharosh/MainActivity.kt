package com.shomerharosh

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var statusBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        prefs = getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32, 48, 32, 32)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val title = label("\u05e9\u05d5\u05de\u05e8 \u05d4\u05e8\u05d0\u05e9", 30f)
        val subtitle = label("\u05d4\u05d2\u05e0\u05d4 \u05de\u05d4\u05d5\u05e0\u05d0\u05d5\u05ea \u05e2\u05dc \u05de\u05d1\u05d5\u05d2\u05e8\u05d9\u05dd", 19f)
        val contactName = input("\u05e9\u05dd \u05d0\u05d9\u05e9 \u05e7\u05e9\u05e8")
        val contactPhone = input("\u05de\u05e1\u05e4\u05e8 \u05d8\u05dc\u05e4\u05d5\u05df")
        val saveButton = Button(this).apply {
            text = "\u05e9\u05de\u05d5\u05e8 \u05e4\u05e8\u05d8\u05d9 \u05e7\u05e9\u05e8"
            setOnClickListener {
                val name = contactName.text.toString().trim()
                val phone = contactPhone.text.toString().trim()
                if (name.isNotEmpty() && phone.isNotEmpty()) {
                    prefs.edit { putString("contact_name", name); putString("contact_phone", phone) }
                    showStatus("\u05e4\u05e8\u05d8\u05d9 \u05e7\u05e9\u05e8 \u05e0\u05e9\u05de\u05e8\u05d5", false)
                } else showStatus("\u05d4\u05db\u05e0\u05e1 \u05e9\u05dd \u05d5\u05de\u05e1\u05e4\u05e8", true)
            }
        }
        val showAlways = CheckBox(this).apply {
            text = "\u05d4\u05e6\u05d2 \u05ea\u05de\u05d9\u05d3 \u05d0\u05ea \u05d4\u05db\u05e4\u05ea\u05d5\u05e8"
            setTextColor(android.graphics.Color.WHITE)
            isChecked = prefs.getBoolean("show_always", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean("show_always", checked) } }
        }
        statusBanner = TextView(this).apply {
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
        }
        val openSettingsButton = Button(this).apply {
            text = "\u05e4\u05ea\u05d7 \u05d4\u05d2\u05d3\u05e8\u05d5\u05ea \u05e0\u05d2\u05d9\u05e9\u05d5\u05ea"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        listOf(title, subtitle, contactName, contactPhone, saveButton, showAlways, statusBanner, openSettingsButton).forEach(root::addView)
        contactName.setText(prefs.getString("contact_name", "") ?: "")
        contactPhone.setText(prefs.getString("contact_phone", "") ?: "")
        setContentView(root)
        updateAccessibilityStatus()
    }

    override fun onResume() { super.onResume(); if (::statusBanner.isInitialized) updateAccessibilityStatus() }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(android.graphics.Color.WHITE); setPadding(0, 0, 0, 16)
    }

    private fun input(value: String) = EditText(this).apply {
        hint = value; textSize = 18f; setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.LTGRAY)
        textDirection = View.TEXT_DIRECTION_RTL; gravity = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun showStatus(message: String, error: Boolean) {
        statusBanner.text = message
        statusBanner.setBackgroundColor(if (error) 0xFFB71C1C.toInt() else 0xFF2E7D32.toInt())
    }

    private fun updateAccessibilityStatus() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val active = enabled?.contains("com.shomerharosh/.ShomerAccessibilityService") == true
        statusBanner.text = if (active) "\u05e9\u05d9\u05e8\u05d5\u05ea \u05d4\u05e0\u05d2\u05d9\u05e9\u05d5\u05ea \u05e4\u05e2\u05d9\u05dc" else "\u05e9\u05d9\u05e8\u05d5\u05ea \u05d4\u05e0\u05d2\u05d9\u05e9\u05d5\u05ea \u05dc\u05d0 \u05e4\u05e2\u05d9\u05dc"
        statusBanner.setBackgroundColor(if (active) 0xFF2E7D32.toInt() else 0xFFB71C1C.toInt())
    }
}
