package com.kuma.motointercom

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 摩声遥控器面板。
 *
 * 对讲核心常驻在 IntercomService；Activity 只负责权限、启动/停止按钮和状态显示。
 */
class MainActivity : Activity(), IntercomService.Listener {

    private lateinit var statusText: TextView
    private lateinit var audioSourceText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button
    private lateinit var riderNameInput: EditText
    private lateinit var contentRoot: FrameLayout
    private lateinit var rippleView: RippleView
    private lateinit var visualizerView: VisualizerView
    private lateinit var deviceListView: ListView
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var intercomService: IntercomService? = null
    private var serviceBound = false
    private var intercomRunning = false
    private var currentButtonColor = DISABLED_BUTTON_COLOR
    private var currentBackgroundColor = BACKGROUND_IDLE_COLOR
    private var buttonColorAnimator: ValueAnimator? = null
    private var backgroundColorAnimator: ValueAnimator? = null
    private val lanDevices = mutableListOf<IntercomService.LanRiderDevice>()
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val local = service as IntercomService.LocalBinder
            intercomService = local.service().also { it.setListener(this@MainActivity) }
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            intercomService = null
            setIntercomRunning(false)
            setStatus("后台服务已断开")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildSimpleUi()
        ensureRuntimePermissions()
    }

    override fun onDestroy() {
        unbindIntercomService()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (hasAllRuntimePermissions()) {
            actionButton.isEnabled = true
            updateActionButton()
            setStatus(READY_STATUS)
        } else {
            actionButton.isEnabled = false
            updateActionButton()
            setStatus("缺少必要权限，无法启动摩声")
        }
    }

    override fun onStatusChanged(status: String, running: Boolean) {
        runOnUiThread {
            setIntercomRunning(running)
            setStatus(status)
        }
    }

    override fun onAudioSourceChanged(status: String, bluetooth: Boolean) {
        runOnUiThread {
            audioSourceText.text = status
            audioSourceText.setTextColor(
                if (bluetooth) Color.rgb(0, 230, 118) else Color.rgb(120, 120, 120)
            )
        }
    }

    override fun onLanDevicesChanged(devices: List<IntercomService.LanRiderDevice>) {
        runOnUiThread {
            lanDevices.clear()
            lanDevices.addAll(devices)
            deviceAdapter.clear()
            deviceAdapter.addAll(devices.map { "${it.name}  ${it.ip}" })
            deviceAdapter.notifyDataSetChanged()
            deviceListView.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onAudioLevelChanged(level: Float) {
        runOnUiThread { visualizerView.setAmplitude(level) }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    override fun onToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onRemoteRiderIdentified(name: String) {
        appendLog("远端骑士昵称：$name")
    }

    override fun onError(message: String) {
        appendLog("错误：$message")
        onToast(message)
    }

    private fun buildSimpleUi() {
        audioSourceText = TextView(this).apply {
            text = "当前音频源：待机"
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.rgb(120, 120, 120))
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }

        riderNameInput = EditText(this).apply {
            textSize = 18f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.edit_text_hint)
            setText(prefs.getString(KEY_RIDER_NAME, "").orEmpty())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(150, 150, 150))
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.rgb(32, 32, 32))
                setStroke(dp(2), Color.rgb(0, 200, 83))
            }
        }

        statusText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            text = "正在检查权限..."
        }

        actionButton = Button(this).apply {
            textSize = 24f
            setTypeface(Typeface.DEFAULT_BOLD)
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            isEnabled = false
            setOnClickListener {
                if (intercomRunning) stopIntercom() else startIntercom()
            }
        }
        updateActionButton()

        logText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(170, 170, 170))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.rgb(28, 28, 28))
        }

        rippleView = RippleView(this)
        visualizerView = VisualizerView(this)

        deviceAdapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setBackgroundColor(Color.rgb(24, 24, 24))
                }
            }
        }

        deviceListView = ListView(this).apply {
            visibility = View.GONE
            adapter = deviceAdapter
            divider = GradientDrawable().apply { setColor(Color.rgb(58, 58, 58)) }
            dividerHeight = dp(1)
            setBackgroundColor(Color.rgb(24, 24, 24))
            setOnItemClickListener { _, _, position, _ ->
                val device = lanDevices.getOrNull(position) ?: return@setOnItemClickListener
                intercomService?.connectToLanDevice(device)
                    ?: Toast.makeText(this@MainActivity, "后台服务未就绪", Toast.LENGTH_SHORT).show()
            }
        }

        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(24), 0, dp(24), dp(20))
        }

        val buttonContainer = FrameLayout(this).apply {
            addView(rippleView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(actionButton, FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER))
        }

        val buttonParams = LinearLayout.LayoutParams(dp(330), dp(330))

        val visualizerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            setMargins(dp(54), dp(16), dp(54), 0)
        }

        val centerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(statusText, statusParams)
            addView(buttonContainer, buttonParams)
            addView(visualizerView, visualizerParams)
        }

        val centerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ).apply {
            setMargins(0, dp(128), 0, dp(136))
        }

        val audioParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(34),
            Gravity.TOP
        ).apply {
            setMargins(dp(18), dp(10), dp(18), 0)
        }

        val nameParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(58),
            Gravity.TOP
        ).apply {
            setMargins(dp(24), dp(56), dp(24), 0)
        }

        val logParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), 0, dp(16), dp(132))
        }

        val deviceParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(104)
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), 0, dp(16), dp(16))
        }

        contentRoot = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND_IDLE_COLOR)
            addView(audioSourceText, audioParams)
            addView(centerPanel, centerParams)
            addView(riderNameInput, nameParams)
            addView(ScrollView(this@MainActivity).apply { addView(logText) }, logParams)
            addView(deviceListView, deviceParams)
        }

        setContentView(contentRoot)
    }

    private fun ensureRuntimePermissions() {
        val missing = requiredRuntimePermissions().filterNot(::hasPermission)
        if (missing.isEmpty()) {
            actionButton.isEnabled = true
            updateActionButton()
            setStatus(READY_STATUS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            actionButton.isEnabled = false
            updateActionButton()
            setStatus("正在申请必要权限...")
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun startIntercom() {
        if (!ensureWifiEnabled()) return

        if (!hasAllRuntimePermissions()) {
            ensureRuntimePermissions()
            return
        }

        setIntercomRunning(true)
        setStatus(SEARCHING_STATUS)
        val riderName = riderNameInput.text?.toString()?.trim().orEmpty()
        prefs.edit().putString(KEY_RIDER_NAME, riderName).apply()
        val intent = IntercomService.startIntent(this, riderName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindIntercomService(intent)
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) return true

        setStatus(WIFI_OFF_STATUS)
        Toast.makeText(this, WIFI_OFF_STATUS, Toast.LENGTH_LONG).show()

        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startActivityForResult(intent, RC_WIFI_PANEL)
            } else {
                startActivity(intent)
            }
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        return false
    }

    private fun stopIntercom() {
        setStatus(ENDED_STATUS)
        intercomService?.requestStop() ?: startService(IntercomService.stopIntent(this))
        unbindIntercomService()
        setIntercomRunning(false)
        setStatus(ENDED_STATUS)
    }

    private fun bindIntercomService(intent: Intent = Intent(this, IntercomService::class.java)) {
        if (serviceBound) return
        bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun unbindIntercomService() {
        if (!serviceBound) return
        intercomService?.setListener(null)
        unbindService(serviceConnection)
        serviceBound = false
        intercomService = null
    }

    private fun setIntercomRunning(running: Boolean) {
        intercomRunning = running
        actionButton.isEnabled = hasAllRuntimePermissions()
        updateActionButton()
        if (::statusText.isInitialized) updateMotionForStatus(statusText.text.toString())
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            statusText.text = message
            statusText.setTextColor(
                when {
                    message == WIFI_OFF_STATUS -> Color.rgb(255, 82, 82)
                    isConnectedStatus(message) -> Color.rgb(0, 230, 118)
                    else -> Color.WHITE
                }
            )
            updateMotionForStatus(message)
            appendLog(message)
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            Log.d(TAG, message)
            logText.append("$message\n")
        }
    }

    private fun hasAllRuntimePermissions(): Boolean =
        requiredRuntimePermissions().all(::hasPermission)

    private fun hasPermission(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredRuntimePermissions(): Array<String> {
        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

        return (
            WifiDirectTunnel.requiredPermissions().asList() +
                RiderAudioEngine.requiredPermissions().asList() +
                AudioRouteController.requiredPermissions().asList() +
                notificationPermission
            )
            .filterNot { it == Manifest.permission.MODIFY_AUDIO_SETTINGS }
            .distinct()
            .toTypedArray()
    }

    private fun updateActionButton() {
        if (!::actionButton.isInitialized) return

        actionButton.text = if (intercomRunning) "结束对讲" else "启动摩声"
        actionButton.setTextColor(Color.WHITE)
        animateButtonColor(targetButtonColor(if (::statusText.isInitialized) statusText.text.toString() else ""))
    }

    private fun updateMotionForStatus(message: String) {
        if (!::rippleView.isInitialized) return

        val active = isPairingStatus(message) || isConnectedStatus(message)
        rippleView.setRunning(active)
        visualizerView.setConnected(isConnectedStatus(message))
        animateButtonColor(targetButtonColor(message))
        animateBackgroundColor(
            when {
                isConnectedStatus(message) -> BACKGROUND_CONNECTED_COLOR
                isPairingStatus(message) -> BACKGROUND_PAIRING_COLOR
                else -> BACKGROUND_IDLE_COLOR
            }
        )
    }

    private fun targetButtonColor(message: String): Int = when {
        isConnectedStatus(message) -> CONNECTED_BUTTON_COLOR
        isPairingStatus(message) || intercomRunning -> PAIRING_BUTTON_COLOR
        !actionButton.isEnabled -> DISABLED_BUTTON_COLOR
        else -> IDLE_BUTTON_COLOR
    }

    private fun animateButtonColor(targetColor: Int) {
        if (currentButtonColor == targetColor) {
            applyButtonColor(targetColor)
            return
        }
        buttonColorAnimator?.cancel()
        buttonColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentButtonColor, targetColor).apply {
            duration = COLOR_ANIMATION_MS
            addUpdateListener {
                currentButtonColor = it.animatedValue as Int
                applyButtonColor(currentButtonColor)
            }
            start()
        }
    }

    private fun animateBackgroundColor(targetColor: Int) {
        if (!::contentRoot.isInitialized || currentBackgroundColor == targetColor) return
        backgroundColorAnimator?.cancel()
        backgroundColorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            currentBackgroundColor,
            targetColor
        ).apply {
            duration = COLOR_ANIMATION_MS
            addUpdateListener {
                currentBackgroundColor = it.animatedValue as Int
                contentRoot.setBackgroundColor(currentBackgroundColor)
            }
            start()
        }
    }

    private fun applyButtonColor(color: Int) {
        actionButton.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(130).toFloat()
            setColor(color)
            setStroke(dp(4), if (color == CONNECTED_BUTTON_COLOR) Color.rgb(178, 255, 89) else Color.rgb(255, 213, 79))
        }
    }

    private fun isPairingStatus(message: String): Boolean =
        message == SEARCHING_STATUS ||
            message == PEER_FOUND_STATUS ||
            message == SIGNALING_CONNECTED_STATUS ||
            message == MEDIA_INITIALIZING_STATUS

    private fun isConnectedStatus(message: String): Boolean =
        message == VOICE_CONNECTED_STATUS

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class RippleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.rgb(255, 213, 79)
        }
        private var progress = 0f
        private var animator: ValueAnimator? = null

        init {
            visibility = INVISIBLE
        }

        fun setRunning(running: Boolean) {
            if (running && animator == null) {
                visibility = VISIBLE
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = RIPPLE_ANIMATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        progress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else if (!running && animator != null) {
                animator?.cancel()
                animator = null
                visibility = INVISIBLE
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (animator == null) return

            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * 0.34f
            val spread = minOf(width, height) * 0.16f
            repeat(3) { index ->
                val phase = (progress + index / 3f) % 1f
                paint.alpha = ((1f - phase) * 90).toInt().coerceIn(0, 90)
                canvas.drawCircle(cx, cy, base + spread * phase, paint)
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

    private class VisualizerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 230, 118)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val path = Path()
        private var connected = false
        private var amplitude = 0f
        private var phase = 0f
        private var animator: ValueAnimator? = null

        fun setConnected(value: Boolean) {
            connected = value
            if (value && animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = WAVE_ANIMATION_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        phase += 0.32f
                        amplitude *= 0.92f
                        invalidate()
                    }
                    start()
                }
            } else if (!value && animator != null) {
                animator?.cancel()
                animator = null
                amplitude = 0f
                invalidate()
            }
        }

        fun setAmplitude(level: Float) {
            if (!connected) return
            amplitude = max(amplitude, level.coerceIn(0f, 1f))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rows = 5
            val spacing = height / (rows + 1f)
            val amp = if (connected) amplitude * height * 0.22f else 0f
            repeat(rows) { row ->
                val centerY = spacing * (row + 1)
                path.reset()
                path.moveTo(0f, centerY)
                var x = 0f
                while (x <= width) {
                    val wave = sin((x / width.coerceAtLeast(1).toFloat() * PI * 4.0) + phase + row)
                    path.lineTo(x, centerY + (wave * amp).toFloat())
                    x += 18f
                }
                canvas.drawPath(path, paint)
            }
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val TAG = "MotoIntercom"
        private const val REQUEST_PERMISSIONS = 1001
        private const val RC_WIFI_PANEL = 1002
        private const val PREFS_NAME = "moto_intercom"
        private const val KEY_RIDER_NAME = "rider_name"
        private const val READY_STATUS = "请点击下方启动对讲"
        private const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        private const val PEER_FOUND_STATUS = "已发现车友"
        private const val SIGNALING_CONNECTED_STATUS = "信令已连接"
        private const val MEDIA_INITIALIZING_STATUS = "媒体初始化中"
        private const val VOICE_CONNECTED_STATUS = "语音通道已连接"
        private const val ENDED_STATUS = "对讲已结束"
        private const val WIFI_OFF_STATUS = "⚠️ 请先打开 Wi-Fi开关"
        private const val COLOR_ANIMATION_MS = 420L
        private const val RIPPLE_ANIMATION_MS = 1_800L
        private const val WAVE_ANIMATION_MS = 420L
        private val BACKGROUND_IDLE_COLOR = Color.rgb(18, 18, 18)
        private val BACKGROUND_PAIRING_COLOR = Color.rgb(30, 24, 10)
        private val BACKGROUND_CONNECTED_COLOR = Color.rgb(8, 28, 18)
        private val DISABLED_BUTTON_COLOR = Color.rgb(70, 70, 70)
        private val IDLE_BUTTON_COLOR = Color.rgb(78, 78, 78)
        private val PAIRING_BUTTON_COLOR = Color.rgb(245, 181, 42)
        private val CONNECTED_BUTTON_COLOR = Color.rgb(0, 200, 83)
    }
}
