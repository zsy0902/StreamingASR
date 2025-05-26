



package com.k2fsa.sherpa.ncnn
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.canDrawOverlays
import android.text.method.ScrollingMovementMethod
import android.text.method.Touch.scrollTo
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
//import com.k2fsa.sherpa.ncnn.R
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.ncnn.getDecoderConfig
import com.k2fsa.sherpa.ncnn.getFeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.getModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt
import androidx.core.net.toUri

private const val TAG = "sherpa-ncnn"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
private const val SILENCE_THRESHOLD = 0.02  // RMS阈值，可根据实际情况调整
private const val MAX_SUBTITLE_LINES = 3    // 最大显示行数

class MainActivity : AppCompatActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    private val useGPU: Boolean = true

    // Audio相关配置
//    private val audioSource = MediaRecorder.AudioSource.VOICE_CALL
    private val audioSource = MediaRecorder.AudioSource.MIC
    private val sampleRateInHz = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 核心组件
    private lateinit var model: SherpaNcnn
    private var audioRecord: AudioRecord? = null
    private lateinit var recordButton: FloatingActionButton
    private lateinit var textView: TextView

    // 线程控制
    private var recordingThread: Thread? = null
    private var subtitleUpdateThread: Thread? = null
    private val subtitleQueue = LinkedBlockingQueue<String>()

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    // 状态管理
    @Volatile private var isRecording: Boolean = false

    private var lastText: String = ""
    private var idx: Int = 0
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
        } else {
            setupFloatingWindow()
        }




        logSupportedSampleRates()
        initPermissions()
//        initUI()
        initModel()
        startSubtitleThread()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
        )
    }
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            setupFloatingWindow()
        } else {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    private fun canDrawOverlaysCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupFloatingWindow() {
        // 初始化窗口参数
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                300.dpToPx(),
                400.dpToPx(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                300.dpToPx(),
                400.dpToPx(),
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
        }

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        // 加载视图
        floatingView = LayoutInflater.from(this).inflate(R.layout.activity_main, null)

        // 初始化组件
        initUIComponents(floatingView)

        // 设置拖动事件
        floatingView.findViewById<View>(R.id.drag_handle).setOnTouchListener { v, event ->
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
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }

        // 设置关闭按钮
        floatingView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener {
            windowManager.removeView(floatingView)
            finish()
        }

        // 添加悬浮窗到窗口
        windowManager.addView(floatingView, params)
    }
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initUIComponents(view: View) {
        try {
            recordButton = view.findViewById<FloatingActionButton>(R.id.record_button).apply {
                setOnClickListener  {
                    Log.d(TAG, "录音按钮被点击")
                    toggleRecording()
                }
            }

            textView = view.findViewById(R.id.subtitle_text)
            textView.apply {
                movementMethod = ScrollingMovementMethod()
                // 自动滚动处理
                viewTreeObserver.addOnScrollChangedListener {
                    val layout = layout ?: return@addOnScrollChangedListener
                    val scrollAmount = layout.getLineTop(lineCount) - height
                    if (scrollAmount > 0) {
                        scrollTo(0, scrollAmount)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "UI初始化失败: ${e.stackTraceToString()}")
            runOnUiThread {
                Toast.makeText(this, "界面加载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()


    private fun initPermissions() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    private fun initUI() {
//        try {
//            // 使用明确类型转换
//            recordButton = findViewById<FloatingActionButton>(R.id.record_button).apply {
//                setOnClickListener {
//                    Log.d(TAG, "录音按钮被点击")
//                    toggleRecording() }
//            }
//
//
//            textView = findViewById(R.id.subtitle_text)
//            textView.apply {
//                movementMethod = ScrollingMovementMethod()
//                // 自动滚动处理
//                textView.viewTreeObserver.addOnScrollChangedListener {
//                    val layout = textView.layout ?: return@addOnScrollChangedListener
//
//                    val totalHeight = layout.getLineTop(textView.lineCount)
//                    val visibleHeight =
//                        textView.height - textView.paddingTop - textView.paddingBottom
//
//                    val maxScroll = totalHeight - visibleHeight
//                    if (maxScroll > 0 && textView.scrollY >= maxScroll) {
//                        textView.scrollTo(0, maxScroll)
//                    }
//                }
//            }
////            Log.e(TAG,"成功")
//        } catch (e: Exception) {
//            Log.e(TAG, "UI初始化失败: ${e.stackTraceToString()}")
//            runOnUiThread {
//                Toast.makeText(this, "界面加载失败", Toast.LENGTH_LONG).show()
////                finish()
//            }
//        }
//    }


    // 在 Activity 中声明协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + Job())



    // 修改后的协程检查
    private fun startSubtitleThread() {
        scope.launch {
            while (isActive) { // 使用协程自带的状态检查
                try {
                    // 添加Activity状态检查
                    if (isDestroyed || isFinishing) break
                    // ...原有逻辑...
                    val newText = withTimeout(500) {
                        subtitleQueue.take()
                    }
                    withContext(Dispatchers.Main) {
                        if (isActive) updateSubtitle(newText)
                    }
                    Log.e(TAG,"成功")
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        Log.d(TAG, "协程正常取消")
                    } else {
                        Log.e(TAG, "字幕处理异常", e)
                    }
                    break
                }
            }
        }
    }
//    private fun startSubtitleThread() {
//        scope.launch {
//            while (isActive) {
//                try {
//                    val newText = withTimeout(500) {
//                        subtitleQueue.take()
//                    }
//                    withContext(Dispatchers.Main) {
//                        if (isActive) updateSubtitle(newText)
//                    }
//                } catch (e: TimeoutCancellationException) {
//                    // 超时继续检查状态
//                } catch (e: Exception) {
//                    Log.e(TAG, "字幕处理异常", e)
//                    break
//                }
//            }
//        }
//    }

    // 添加节流控制
    private var lastUpdateTime = 0L
    private val UPDATE_INTERVAL = 100 // 最小更新间隔 100ms


    private fun updateSubtitle(newText: String) {
        val lines = textView.text.split("\n").toMutableList()
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < UPDATE_INTERVAL) return

        lastUpdateTime = currentTime
        when {
            newText.isEmpty() -> lines.clear()
            newText == "\n" -> lines.add("")
            else -> {
                if (lines.lastOrNull()?.isNotEmpty() == true) {
                    lines.add(newText)
                } else {
                    lines[lines.lastIndex] = newText
                }
            }
        }

        // 保持最大行数
        while (lines.size > MAX_SUBTITLE_LINES) {
            lines.removeAt(0)
        }

        textView.text = lines.joinToString("\n")

        // 自动滚动
        textView.post {
            val scrollAmount = textView.layout?.getLineBottom(textView.lineCount - 1) ?: 0
            if (scrollAmount > textView.height) {
                textView.scrollTo(0, scrollAmount - textView.height)
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun toggleRecording() {
        if (!isRecording) {
            Log.d(TAG, "开始录音")
            startRecording()
            Log.e(TAG,"isRecording:"+isRecording)
        } else {
            Log.d(TAG, "停止录音")
            stopRecording()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {

        if (!initAudioRecorder()) return
        audioRecord!!.startRecording()
        isRecording = true
        recordButton.setImageResource(R.drawable.ic_stop)  // 设置停止图标

        textView.text = ""
        lastText = ""
        idx = 0

        Log.e(TAG,"StartR成功")
        recordingThread = thread {
            model.reset(true)
            processAudioStream()
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordButton.setImageResource(R.drawable.ic_mic)   // 设置麦克风图标
    }

    // 使用AtomicBoolean替代基本类型


    private fun processAudioStream() {
        val interval = 0.1  // 100ms
        val bufferSize = (interval * sampleRateInHz).toInt()
        val buffer = ShortArray(bufferSize)

        while (isRecording) {
            val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
//            Log.e(TAG,"pro成功")

            if (readResult > 0) {
                val samples = FloatArray(readResult) { buffer[it] / 32768.0f }

                // VAD检测
//                if (calculateRMS(samples) < SILENCE_THRESHOLD) continue
//                Log.e(TAG,"provad成功")

                model.acceptSamples(samples)
                processRecognitionResult()
            }
        }
    }

    private var partialResult = StringBuilder()

    private fun processRecognitionResult() {
        // ...原有代码...
        while (model.isReady()) model.decode()

        val text = model.text.trim()
        val isEndpoint = model.isEndpoint()

        Log.e(TAG,"文本："+text)
//        if (text.isNotEmpty()) {
//            Log.e(TAG,"非空")
//            subtitleQueue.put(text)
//            if (isEndpoint) {
//                model.reset()
//                subtitleQueue.put("\n")  // 段落分隔
//            }
//        }
        Log.e(TAG,"空")
//        val text = model.text.trim()
//        val isEndpoint = model.isEndpoint()

        if (text.isNotEmpty()) {
            // 合并部分结果
            if (text != partialResult.toString()) {
                partialResult.clear()
                partialResult.append(text)
                subtitleQueue.put(text)
            }

            if (isEndpoint) {
                // 最终结果处理
                subtitleQueue.put("[FINAL]${partialResult}")
                partialResult.clear()
                model.reset()
                subtitleQueue.put("\n")
            }
        }
    }


//    private fun processRecognitionResult() {
//        while (model.isReady()) model.decode()
//
//        val text = model.text.trim()
//        val isEndpoint = model.isEndpoint()
//
//        Log.e(TAG,"文本："+text)
//        if (text.isNotEmpty()) {
//            Log.e(TAG,"非空")
//            subtitleQueue.put(text)
//            if (isEndpoint) {
//                model.reset()
//                subtitleQueue.put("\n")  // 段落分隔
//            }
//        }
//        Log.e(TAG,"空")
//    }

    private fun calculateRMS(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size)
    }
    private fun logSupportedSampleRates() {
        val sampleRates = intArrayOf(8000, 16000, 44100, 48000)
        for (rate in sampleRates) {
            val bufferSize = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            Log.d(TAG, "采样率 $rate Hz 支持状态: ${if (bufferSize > 0) "支持" else "不支持"}")
        }
    }
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initAudioRecorder(): Boolean {
        try {
            // 1. 检查权限
            if (!hasAudioPermission()) {
                Log.w(TAG, "录音权限未授予")
                requestAudioPermission()
                return false
            }

            // 2. 验证音频参数
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                audioFormat
            )
            if (minBufferSize <= 0) {
                Log.e(TAG, "无效的缓冲区大小: $minBufferSize")
                return false
            }

            // 3. 初始化录音器
            audioRecord = AudioRecord(
                audioSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            // 4. 验证初始化状态
            return when (audioRecord?.state) {
                AudioRecord.STATE_INITIALIZED -> {
                    Log.d(TAG, "音频录制器初始化成功")
                    true
                }
                else -> {
                    Log.e(TAG, "音频录制器初始化失败")
                    audioRecord?.release()
                    false
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "初始化音频录制器异常", e)
            audioRecord?.release()
            return false
        }
    }

    // 权限检查辅助函数
    private fun hasAudioPermission() = ActivityCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // 权限请求函数
    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION
        )
    }

//    private fun initAudioRecorder(): Boolean {
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.RECORD_AUDIO
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            return false
//        }
//
//        val minBufferSize = AudioRecord.getMinBufferSize(
//            sampleRateInHz,
//            channelConfig,
//            audioFormat
//        )
//
//        audioRecord = AudioRecord(
//            audioSource,
//            sampleRateInHz,
//            channelConfig,
//            audioFormat,
//            minBufferSize * 2
//        )
//        return audioRecord?.state == AudioRecord.STATE_INITIALIZED
//    }

    private fun initModel() {
        val config = RecognizerConfig(
            featConfig = getFeatureExtractorConfig(
                sampleRate = 16000.0f,
                featureDim = 80
            ),
            modelConfig = getModelConfig(type = 6, useGPU = useGPU)!!,
            decoderConfig = getDecoderConfig(
                method = "greedy_search",
                numActivePaths = 4
            ),
            enableEndpoint = true,
            rule1MinTrailingSilence = 2.0f,
            rule2MinTrailingSilence = 0.8f,
            rule3MinUtteranceLength = 20.0f
        )

        model = SherpaNcnn(
            assetManager = application.assets,
            config = config

        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION &&
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        ) {
            finish()
        }
    }
    // 修改后的onDestroy
    override fun onDestroy() {
        scope.cancel("Activity destroyed") // 取消所有协程
        super.onDestroy()
    }
//    override fun onDestroy() {
//        super.onDestroy()
//        isRecording = false
//        subtitleUpdateThread?.interrupt()
//        recordingThread?.interrupt()
//    }
}

