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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 摩声主控面板。
 *
 * 对讲核心常驻在 IntercomService；Activity 只负责权限、启动/停止按钮和状态显示。
 */
class MainActivity : Activity(), IntercomService.Listener {

    private lateinit var statusText: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var remoteRiderText: TextView
    private lateinit var audioSourceText: TextView
    private lateinit var audioRouteStateText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button
    private lateinit var riderNameInput: EditText
    private lateinit var contentRoot: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var rippleView: RippleView
    private lateinit var visualizerView: VisualizerView
    private lateinit var deviceListContainer: LinearLayout
    private lateinit var deviceEmptyText: TextView
    private lateinit var wifiDirectPill: TextView
    private lateinit var webRtcPill: TextView
    private lateinit var voxPill: TextView
    private lateinit var bluetoothPill: TextView
    private lateinit var voxListeningPill: TextView
    private lateinit var voxOpenPill: TextView
    private lateinit var voxHangoverPill: TextView

    private var intercomService: IntercomService? = null
    private var serviceBound = false
    private var intercomRunning = false
    private var mediaConnected = false
    private var currentButtonColor = DISABLED_BUTTON_COLOR
    private var buttonColorAnimator: ValueAnimator? = null
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
        runOnUiThread { updateAudioSource(status, bluetooth) }
    }

    override fun onLanDevicesChanged(devices: List<IntercomService.LanRiderDevice>) {
        runOnUiThread {
            lanDevices.clear()
            lanDevices.addAll(devices)
            renderLanDevices()
        }
    }

    override fun onAudioLevelChanged(level: Float) {
        runOnUiThread {
            visualizerView.setAmplitude(level)
            updateVoxDisplay(level)
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }

    override fun onToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    override fun onRemoteRiderIdentified(name: String) {
        runOnUiThread {
            remoteRiderText.text = name.ifBlank { "等待车友加入" }
            appendLog("远端骑士昵称：$name")
        }
    }

    override fun onError(message: String) {
        appendLog("错误：$message")
        onToast(message)
    }

    private fun buildSimpleUi() {
        window.statusBarColor = SURFACE_COLOR
        window.navigationBarColor = SURFACE_COLOR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        statusText = createText("正在检查权限...", 20f, TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        statusDetailText = createText("一对一对讲 · 无需网络", 13f, TEXT_SECONDARY, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
        }
        remoteRiderText = createText("等待车友加入", 24f, TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
        }
        audioSourceText = createText("当前音频源：待机", 15f, TEXT_PRIMARY, Typeface.BOLD)
        audioRouteStateText = createText("待机", 13f, TEXT_SECONDARY, Typeface.NORMAL)
        logText = createText("", 12f, TEXT_SECONDARY, Typeface.NORMAL).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(FIELD_COLOR, dp(12))
        }

        riderNameInput = EditText(this).apply {
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.edit_text_hint)
            setText(prefs.getString(KEY_RIDER_NAME, "").orEmpty())
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(WHITE, dp(12), BORDER_COLOR, dp(1))
        }

        actionButton = Button(this).apply {
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            isEnabled = false
            elevation = dp(4).toFloat()
            setOnClickListener {
                if (intercomRunning) stopIntercom() else startIntercom()
            }
        }
        updateActionButton()

        rippleView = RippleView(this)
        visualizerView = VisualizerView(this)

        wifiDirectPill = createPill("Wi-Fi Direct", false)
        webRtcPill = createPill("WebRTC", false)
        voxPill = createPill("VOX", false)
        bluetoothPill = createPill("蓝牙耳机", false)
        voxListeningPill = createPill("待机 / LISTENING", true)
        voxOpenPill = createPill("开麦 / OPEN", false)
        voxHangoverPill = createPill("保持 / HANGOVER", false)

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        deviceEmptyText = createText("正在搜索附近 MotoCom 车友...", 13f, TEXT_SECONDARY, Typeface.NORMAL)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            background = rounded(BACKGROUND_COLOR, 0)
        }

        page.addView(buildHeader())
        page.addView(buildConnectionCard(), matchWrap().withTop(dp(16)))
        page.addView(buildAudioCard(), matchWrap().withTop(dp(12)))
        page.addView(buildVoxCard(), matchWrap().withTop(dp(12)))
        page.addView(buildDiscoveryCard(), matchWrap().withTop(dp(12)))
        page.addView(buildSettingsCard(), matchWrap().withTop(dp(12)))
        page.addView(buildLogCard(), matchWrap().withTop(dp(12)))

        contentRoot = page
        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            isFillViewport = false
            clipToPadding = false
            addView(contentRoot)
        })
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(createIconButton("☰"))
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(createText("摩声 MotoCom", 22f, TEXT_PRIMARY, Typeface.BOLD))
                addView(createPill("一对一对讲 · 无需网络", true), wrapWrap().withTop(dp(8)))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(createIconButton("⚙").apply { alpha = 0.55f })
        return row
    }

    private fun buildConnectionCard(): View {
        val card = createCard()
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(statusText, matchWrap())
        card.addView(statusDetailText, matchWrap().withTop(dp(6)))
        card.addView(visualizerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(58)
        ).withTop(dp(18)))
        card.addView(remoteRiderText, matchWrap().withTop(dp(10)))
        card.addView(createText("对方在线状态由信令和媒体通道实时更新", 13f, TEXT_SECONDARY, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
        }, matchWrap().withTop(dp(4)))
        card.addView(
            chipRow(wifiDirectPill, webRtcPill, voxPill, bluetoothPill),
            matchWrap().withTop(dp(16))
        )
        card.addView(buildMainButton(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(164)
        ).withTop(dp(18)))
        return card
    }

    private fun buildMainButton(): View {
        return FrameLayout(this).apply {
            addView(rippleView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(actionButton, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        }
    }

    private fun buildAudioCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("音频输出"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createIconCircle("🎧", ACCENT_GREEN_SOFT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(audioSourceText)
                addView(audioRouteStateText, matchWrap().withTop(dp(4)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).withLeft(dp(12)))
        }
        card.addView(row, matchWrap().withTop(dp(10)))
        return card
    }

    private fun buildVoxCard(): View {
        val card = createCard()
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createSectionTitle("VOX 状态"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createText("只读", 12f, TEXT_MUTED, Typeface.NORMAL))
        }
        card.addView(titleRow)
        card.addView(chipRow(voxListeningPill, voxOpenPill, voxHangoverPill), matchWrap().withTop(dp(12)))
        card.addView(createText("当前项目未暴露 VOX 设置接口，本区只展示状态，不改变底层参数。", 12f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(10)))
        return card
    }

    private fun buildDiscoveryCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("发现车友"))
        card.addView(createText("仅连接通过 MotoCom 身份校验的设备", 13f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(4)))
        card.addView(deviceEmptyText, matchWrap().withTop(dp(12)))
        card.addView(deviceListContainer, matchWrap().withTop(dp(8)))
        return card
    }

    private fun buildSettingsCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("设置"))
        card.addView(createText("本机昵称", 13f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(12)))
        card.addView(riderNameInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ).withTop(dp(6)))
        card.addView(readOnlyRow("连接方式", "优先 Wi-Fi Direct（P2P）", "同一 Wi-Fi 下自动使用局域网发现"), matchWrap().withTop(dp(12)))
        card.addView(readOnlyRow("高级", "日志诊断 · 帮助与反馈 · 关于我们", "本轮只做展示，不新增入口逻辑"), matchWrap().withTop(dp(10)))
        return card
    }

    private fun buildLogCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("日志诊断"))
        logScroll = ScrollView(this).apply {
            addView(logText, matchWrap())
        }
        card.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(150)
        ).withTop(dp(10)))
        return card
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
            statusText.setTextColor(statusColor(message))
            statusDetailText.text = statusDetail(message)
            mediaConnected = isConnectedStatus(message)
            updateMotionForStatus(message)
            appendLog(message)
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            Log.d(TAG, message)
            logText.append("$message\n")
            if (::logScroll.isInitialized) {
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun updateAudioSource(status: String, bluetooth: Boolean) {
        audioSourceText.text = status
        audioRouteStateText.text = if (bluetooth) "蓝牙连接状态：已连接" else "手机外放 / 待机"
        audioRouteStateText.setTextColor(if (bluetooth) ACCENT_CONNECTED else TEXT_SECONDARY)
        bluetoothPill.applyPill(bluetooth)
    }

    private fun renderLanDevices() {
        deviceListContainer.removeAllViews()
        deviceEmptyText.visibility = if (lanDevices.isEmpty()) View.VISIBLE else View.GONE

        lanDevices.forEachIndexed { index, device ->
            if (index > 0) {
                deviceListContainer.addView(
                    separator(),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).withTop(dp(8))
                )
            }
            deviceListContainer.addView(deviceRow(device), matchWrap().withTop(dp(8)))
        }
    }

    private fun deviceRow(device: IntercomService.LanRiderDevice): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(FIELD_COLOR, dp(14), BORDER_COLOR, dp(1))
        }

        row.addView(createIconCircle("骑", ACCENT_GREEN_SOFT))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createText(device.name.ifBlank { "车友" }, 16f, TEXT_PRIMARY, Typeface.BOLD))
            addView(createText("${device.ip}:${device.port} · 局域网发现", 12f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(3)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).withLeft(dp(10)))
        row.addView(Button(this).apply {
            text = "连接"
            textSize = 14f
            isAllCaps = false
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = rounded(ACCENT_GREEN, dp(12))
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setOnClickListener {
                intercomService?.connectToLanDevice(device)
                    ?: Toast.makeText(this@MainActivity, "后台服务未就绪", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(dp(76), dp(42)))
        return row
    }

    private fun updateVoxDisplay(level: Float) {
        val open = mediaConnected && level > 0.12f
        voxListeningPill.applyPill(!mediaConnected)
        voxOpenPill.applyPill(open)
        voxHangoverPill.applyPill(mediaConnected && !open)
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
        wifiDirectPill.applyPill(intercomRunning || isPairingStatus(message) || isConnectedStatus(message))
        webRtcPill.applyPill(isConnectedStatus(message) || message == MEDIA_INITIALIZING_STATUS)
        voxPill.applyPill(isConnectedStatus(message))
        if (!isConnectedStatus(message)) updateVoxDisplay(0f)
    }

    private fun targetButtonColor(message: String): Int = when {
        isConnectedStatus(message) -> ACCENT_CONNECTED
        isPairingStatus(message) || intercomRunning -> ACCENT_GREEN
        !actionButton.isEnabled -> DISABLED_BUTTON_COLOR
        else -> ACCENT_GREEN
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

    private fun applyButtonColor(color: Int) {
        actionButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(7), Color.argb(60, 76, 203, 0))
        }
    }

    private fun statusColor(message: String): Int = when {
        message == WIFI_OFF_STATUS || message.contains("错误") || message.contains("缺少") -> ERROR_RED
        isConnectedStatus(message) -> ACCENT_CONNECTED
        isPairingStatus(message) -> ACCENT_GREEN
        else -> TEXT_PRIMARY
    }

    private fun statusDetail(message: String): String = when {
        isConnectedStatus(message) -> "语音通道在线，保持骑行沟通"
        message == SIGNALING_CONNECTED_STATUS -> "信令已建立，正在准备媒体"
        message == MEDIA_INITIALIZING_STATUS -> "正在初始化 WebRTC 音频"
        isPairingStatus(message) -> "正在搜索附近 MotoCom 车友..."
        message == ENDED_STATUS -> "已停止对讲，可重新启动"
        message == WIFI_OFF_STATUS -> "需要 Wi-Fi 才能使用 P2P 或局域网发现"
        else -> "一对一对讲 · 无需网络"
    }

    private fun isPairingStatus(message: String): Boolean =
        message == SEARCHING_STATUS ||
            message == PEER_FOUND_STATUS ||
            message == SIGNALING_CONNECTED_STATUS ||
            message == MEDIA_INITIALIZING_STATUS

    private fun isConnectedStatus(message: String): Boolean =
        message == VOICE_CONNECTED_STATUS

    private fun createCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(WHITE, dp(18), BORDER_COLOR, dp(1))
            elevation = dp(2).toFloat()
        }

    private fun createSectionTitle(text: String): TextView =
        createText(text, 16f, TEXT_PRIMARY, Typeface.BOLD)

    private fun createText(text: String, size: Float, color: Int, style: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, style)
            includeFontPadding = true
        }

    private fun createPill(text: String, active: Boolean): TextView =
        createText(text, 13f, if (active) ACCENT_CONNECTED else TEXT_SECONDARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            applyPill(active)
        }

    private fun TextView.applyPill(active: Boolean) {
        setTextColor(if (active) ACCENT_CONNECTED else TEXT_SECONDARY)
        background = rounded(if (active) ACCENT_GREEN_SOFT else FIELD_COLOR, dp(18))
        alpha = if (active) 1f else 0.78f
    }

    private fun createIconButton(text: String): TextView =
        createText(text, 24f, TEXT_PRIMARY, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(WHITE, dp(20), BORDER_COLOR, dp(1))
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

    private fun createIconCircle(text: String, color: Int): TextView =
        createText(text, 18f, ACCENT_CONNECTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        }

    private fun chipRow(vararg chips: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            chips.toList().chunked(2).forEachIndexed { rowIndex, rowChips ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    rowChips.forEachIndexed { index, chip ->
                        addView(chip, wrapWrap().apply { if (index > 0) leftMargin = dp(8) })
                    }
                }, wrapWrap().apply { if (rowIndex > 0) topMargin = dp(8) })
            }
        }

    private fun readOnlyRow(title: String, value: String, detail: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(FIELD_COLOR, dp(12), BORDER_COLOR, dp(1))
            addView(createText(title, 12f, TEXT_MUTED, Typeface.NORMAL))
            addView(createText(value, 15f, TEXT_PRIMARY, Typeface.BOLD), matchWrap().withTop(dp(4)))
            addView(createText(detail, 12f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(3)))
        }

    private fun separator(): View =
        View(this).apply { setBackgroundColor(BORDER_COLOR) }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun wrapWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

    private fun LinearLayout.LayoutParams.withTop(value: Int): LinearLayout.LayoutParams =
        apply { topMargin = value }

    private fun LinearLayout.LayoutParams.withLeft(value: Int): LinearLayout.LayoutParams =
        apply { leftMargin = value }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class RippleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = ACCENT_GREEN
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
            val base = minOf(width, height) * 0.32f
            val spread = minOf(width, height) * 0.18f
            repeat(3) { index ->
                val phase = (progress + index / 3f) % 1f
                paint.alpha = ((1f - phase) * 80).toInt().coerceIn(0, 80)
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
            color = ACCENT_GREEN
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
            val rows = 4
            val spacing = height / (rows + 1f)
            val amp = if (connected) amplitude * height * 0.24f else height * 0.04f
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
        private const val COLOR_ANIMATION_MS = 280L
        private const val RIPPLE_ANIMATION_MS = 1_800L
        private const val WAVE_ANIMATION_MS = 420L

        private val BACKGROUND_COLOR = Color.rgb(247, 249, 252)
        private val SURFACE_COLOR = Color.WHITE
        private val WHITE = Color.WHITE
        private val FIELD_COLOR = Color.rgb(248, 250, 252)
        private val BORDER_COLOR = Color.rgb(229, 231, 235)
        private val TEXT_PRIMARY = Color.rgb(17, 24, 39)
        private val TEXT_SECONDARY = Color.rgb(107, 114, 128)
        private val TEXT_MUTED = Color.rgb(156, 163, 175)
        private val ACCENT_GREEN = Color.rgb(126, 219, 34)
        private val ACCENT_CONNECTED = Color.rgb(76, 203, 0)
        private val ACCENT_GREEN_SOFT = Color.rgb(237, 252, 224)
        private val ERROR_RED = Color.rgb(239, 68, 68)
        private val DISABLED_BUTTON_COLOR = Color.rgb(190, 197, 208)
    }
}
