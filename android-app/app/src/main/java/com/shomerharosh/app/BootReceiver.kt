package com.shomerharosh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        // Respect the user's toggle — don't auto-start if they disabled the overlay
        val prefs = context.getSharedPreferences("shomer_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(FloatingHeadService.PREF_FLOATING_BTN_ENABLED, true)
        if (!enabled) return

        // Don't start if overlay permission was revoked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return

        val svc = Intent(context, FloatingHeadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (_: Exception) {}
    }
}
