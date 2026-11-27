package com.famage.skydrop_tv_app.receiver

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.famage.skydrop_tv_app.R

/**
 * Manages floating window overlay over any TV app (e.g., YouTube, Netflix, TV Home Screen).
 * Shows live incoming file transfer progress and completion alert without stealing input focus.
 */
class TvSystemOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "TvSystemOverlayManager"

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var digitGroups = 0
            var size = bytes.toDouble()
            while (size >= 1024 && digitGroups < units.size - 1) {
                size /= 1024
                digitGroups++
            }
            return "%.1f %s".format(size, units[digitGroups])
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private var overlayView: View? = null
    private var isOverlayAttached = false
    private var autoDismissRunnable: Runnable? = null

    // Layout views
    private var transferSection: LinearLayout? = null
    private var transferPercent: TextView? = null
    private var transferFilename: TextView? = null
    private var transferProgressBar: ProgressBar? = null
    private var transferBytesRead: TextView? = null
    private var transferTotalBytes: TextView? = null
    private var completionSection: LinearLayout? = null
    private var completionText: TextView? = null

    fun updateProgress(progress: TvReceiveProgress) {
        mainHandler.post {
            cancelAutoDismiss()
            ensureOverlayCreated()

            transferSection?.visibility = View.VISIBLE
            completionSection?.visibility = View.GONE

            transferFilename?.text = progress.filename
            val pct = progress.percent
            if (pct >= 0) {
                transferPercent?.text = "$pct%"
                transferProgressBar?.isIndeterminate = false
                transferProgressBar?.progress = pct.coerceIn(0, 100)
            } else {
                transferPercent?.text = "Streaming"
                transferProgressBar?.isIndeterminate = true
            }

            transferBytesRead?.text = formatBytes(progress.bytesReceived)
            if (progress.totalBytes > 0) {
                transferTotalBytes?.visibility = View.VISIBLE
                transferTotalBytes?.text = "Total: ${formatBytes(progress.totalBytes)}"
            } else {
                transferTotalBytes?.visibility = View.GONE
            }
        }
    }

    fun showCompletion(message: String, autoDismissDelayMs: Long = 4500L) {
        mainHandler.post {
            ensureOverlayCreated()

            transferSection?.visibility = View.GONE
            completionSection?.visibility = View.VISIBLE
            completionText?.text = message

            scheduleAutoDismiss(autoDismissDelayMs)
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            removeOverlay()
        }
    }

    private fun ensureOverlayCreated() {
        if (isOverlayAttached && overlayView != null) return
        if (windowManager == null) {
            Log.w(TAG, "WindowManager is null; cannot display overlay")
            return
        }

        if (!canDrawOverlays(context)) {
            Log.d(TAG, "SYSTEM_ALERT_WINDOW permission not granted; skipping system overlay")
            return
        }

        try {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.tv_floating_transfer_overlay, null)

            transferSection = view.findViewById(R.id.transfer_section)
            transferPercent = view.findViewById(R.id.transfer_percent)
            transferFilename = view.findViewById(R.id.transfer_filename)
            transferProgressBar = view.findViewById(R.id.transfer_progress_bar)
            transferBytesRead = view.findViewById(R.id.transfer_bytes_read)
            transferTotalBytes = view.findViewById(R.id.transfer_total_bytes)
            completionSection = view.findViewById(R.id.completion_section)
            completionText = view.findViewById(R.id.completion_text)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = context.resources.displayMetrics.density
            val marginEndPx = (36 * density).toInt()
            val marginBottomPx = (28 * density).toInt()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = marginEndPx
                y = marginBottomPx
            }

            windowManager.addView(view, params)
            overlayView = view
            isOverlayAttached = true
            Log.i(TAG, "System transfer overlay attached successfully at bottom-end")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach system transfer overlay", e)
            overlayView = null
            isOverlayAttached = false
        }
    }

    private fun removeOverlay() {
        cancelAutoDismiss()
        if (isOverlayAttached && overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView)
                Log.d(TAG, "System transfer overlay removed")
            } catch (e: Exception) {
                Log.w(TAG, "Error removing system transfer overlay: ${e.message}")
            }
        }
        overlayView = null
        isOverlayAttached = false
    }

    private fun scheduleAutoDismiss(delayMs: Long) {
        cancelAutoDismiss()
        val runnable = Runnable { removeOverlay() }
        autoDismissRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = null
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeOverlay()
    }
}
