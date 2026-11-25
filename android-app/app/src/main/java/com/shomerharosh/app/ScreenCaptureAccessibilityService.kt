package com.shomerharosh.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenCaptureAccessibilityService : AccessibilityService() {

    private var screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    companion object {
        @Volatile
        var instance: ScreenCaptureAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureExecutor()
        instance = this
    }

    /**
     * Forward foreground-package changes to FloatingHeadService so it can
     * show/hide the overlay based on the active app.
     *
     * We only act on TYPE_WINDOW_STATE_CHANGED events that carry a package name,
     * to avoid reacting to sub-window changes (keyboard, dialogs) that report
     * the same package we already know about.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isBlank()) return
        // Forward to FloatingHeadService — it will evaluate visibility on main thread
        FloatingHeadService.notifyForegroundPackage(pkg)
    }

    override fun onInterrupt() {
        // Not needed
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance == this) {
            instance = null
            // Tell the floating service the accessibility service is gone
            FloatingHeadService.notifyForegroundPackage("")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        try { screenshotExecutor.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    fun captureScreenBase64(onResult: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null)
            return
        }

        ensureExecutor()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    var scaled: Bitmap? = null
                    try {
                        val colorSpace = screenshot.colorSpace
                            ?: ColorSpace.get(ColorSpace.Named.SRGB)
                        val wrapped = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, colorSpace)
                        bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()

                        if (bitmap == null) {
                            onResult(null)
                            return
                        }

                        val maxW = 650
                        scaled = if (bitmap.width > maxW) {
                            val ratio = maxW.toFloat() / bitmap.width
                            Bitmap.createScaledBitmap(
                                bitmap,
                                maxW,
                                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                                true
                            )
                        } else {
                            bitmap
                        }

                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        onResult(base64)
                    } catch (_: Exception) {
                        onResult(null)
                    } finally {
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        if (scaled != null && scaled !== bitmap) {
                            try { scaled.recycle() } catch (_: Exception) {}
                        }
                        try { bitmap?.recycle() } catch (_: Exception) {}
                    }
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            }
        )
    }

    private fun ensureExecutor() {
        if (screenshotExecutor.isShutdown) {
            screenshotExecutor = Executors.newSingleThreadExecutor()
        }
    }
}
