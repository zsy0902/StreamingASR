import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.k2fsa.sherpa.ncnn.R
import androidx.core.net.toUri

class TranscriptionOverlay(private val context: Context) {

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private lateinit var floatingView: View
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @SuppressLint("InflateParams")
    @RequiresApi(Build.VERSION_CODES.M)
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            requestOverlayPermission()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        floatingView = LayoutInflater.from(context).inflate(R.layout.overlay_transcription, null)
        params.gravity = Gravity.START or Gravity.TOP
        params.x = 0
        params.y = 100

        setupDragListener(params)

        windowManager.addView(floatingView, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragListener(params: WindowManager.LayoutParams) {
        floatingView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    fun updateText(text: String) {
        floatingView.findViewById<TextView>(R.id.tv_transcript).text = text
    }

    fun hide() {
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }


    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}