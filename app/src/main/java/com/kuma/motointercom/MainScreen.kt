package com.kuma.motointercom

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class MainScreen(
    private val activity: Activity,
    initialRiderName: String,
    private val onToggleIntercom: () -> Unit,
    private val onConnectDevice: (LanRiderDevice) -> Unit
) {
    val root: View
    val riderName: String
        get() = riderNameInput.text?.toString()?.trim().orEmpty()

    private lateinit var statusText: TextView
    private lateinit var statusDetailText: TextView
    private lateinit var remoteRiderText: TextView
    private lateinit var audioSourceText: TextView
    private lateinit var audioRouteStateText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: Button
    private lateinit var riderNameInput: EditText
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

    private var intercomRunning = false
    private var canStart = false
    private var mediaConnected = false
    private var currentButtonColor = DISABLED_BUTTON_COLOR
    private var buttonColorAnimator: ValueAnimator? = null
    private var lanDevices = emptyList<LanRiderDevice>()
    private val logBuffer = BoundedLogBuffer(300)

    init {
        root = buildSimpleUi(initialRiderName)
    }

    fun setRunning(running: Boolean, canStart: Boolean) {
        intercomRunning = running
        this.canStart = canStart
        actionButton.isEnabled = canStart
        updateActionButton()
        updateMotionForStatus(statusText.text.toString())
    }

    fun setStatus(message: String) {
        statusText.text = message
        statusText.setTextColor(statusColor(message))
        statusDetailText.text = statusDetail(message)
        mediaConnected = isConnectedStatus(message)
        updateMotionForStatus(message)
        appendLog(message)
    }

    fun setAudioSource(status: String, bluetooth: Boolean) {
        updateAudioSource(status, bluetooth)
    }

    fun setRemoteRider(name: String?) {
        remoteRiderText.text = name.orEmpty().ifBlank { "等待车友加入" }
    }

    fun setLanDevices(devices: List<LanRiderDevice>) {
        lanDevices = devices.toList()
        renderLanDevices()
    }

    fun setAudioLevel(level: Float) {
        visualizerView.setAmplitude(level)
        updateVoxDisplay(level)
    }

    fun appendLog(message: String) {
        Log.d(TAG, message)
        logBuffer.append(message)
        logText.text = logBuffer.snapshot().joinToString("\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun stopAnimations() {
        buttonColorAnimator?.cancel()
        buttonColorAnimator = null
        rippleView.stop()
        visualizerView.stop()
    }

    private fun buildSimpleUi(initialRiderName: String): View {
        activity.window.statusBarColor = SURFACE_COLOR
        activity.window.navigationBarColor = SURFACE_COLOR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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

        riderNameInput = EditText(activity).apply {
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = activity.getString(R.string.edit_text_hint)
            setText(initialRiderName)
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(WHITE, dp(12), BORDER_COLOR, dp(1))
        }

        actionButton = Button(activity).apply {
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            isEnabled = false
            elevation = dp(4).toFloat()
            setOnClickListener { onToggleIntercom() }
        }
        updateActionButton()

        rippleView = RippleView(activity)
        visualizerView = VisualizerView(activity)

        wifiDirectPill = createPill("Wi-Fi Direct", false)
        webRtcPill = createPill("WebRTC", false)
        voxPill = createPill("VOX", false)
        bluetoothPill = createPill("蓝牙耳机", false)
        voxListeningPill = createPill("待机 / LISTENING", true)
        voxOpenPill = createPill("开麦 / OPEN", false)
        voxHangoverPill = createPill("保持 / HANGOVER", false)

        deviceListContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        deviceEmptyText = createText("正在搜索附近 MotoCom 车友...", 13f, TEXT_SECONDARY, Typeface.NORMAL)

        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            background = rounded(BACKGROUND_COLOR, 0)
            addView(buildHeader())
            addView(buildConnectionCard(), matchWrap().withTop(dp(16)))
            addView(buildAudioCard(), matchWrap().withTop(dp(12)))
            addView(buildVoxCard(), matchWrap().withTop(dp(12)))
            addView(buildDiscoveryCard(), matchWrap().withTop(dp(12)))
            addView(buildSettingsCard(), matchWrap().withTop(dp(12)))
            addView(buildLogCard(), matchWrap().withTop(dp(12)))
        }

        return ScrollView(activity).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            isFillViewport = false
            clipToPadding = false
            addView(page)
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(createIconButton("☰"))
        row.addView(
            LinearLayout(activity).apply {
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
        card.addView(
            visualizerView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).withTop(dp(18))
        )
        card.addView(remoteRiderText, matchWrap().withTop(dp(10)))
        card.addView(
            createText("对方在线状态由信令和媒体通道实时更新", 13f, TEXT_SECONDARY, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
            },
            matchWrap().withTop(dp(4))
        )
        card.addView(chipRow(wifiDirectPill, webRtcPill, voxPill, bluetoothPill), matchWrap().withTop(dp(16)))
        card.addView(
            buildMainButton(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(164)).withTop(dp(18))
        )
        return card
    }

    private fun buildMainButton(): View =
        FrameLayout(activity).apply {
            addView(
                rippleView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(actionButton, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        }

    private fun buildAudioCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("音频输出"))
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createIconCircle("🎧", ACCENT_GREEN_SOFT))
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(audioSourceText)
                    addView(audioRouteStateText, matchWrap().withTop(dp(4)))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).withLeft(dp(12))
            )
        }
        card.addView(row, matchWrap().withTop(dp(10)))
        return card
    }

    private fun buildVoxCard(): View {
        val card = createCard()
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                createSectionTitle("VOX 状态"),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(createText("只读", 12f, TEXT_MUTED, Typeface.NORMAL))
        }
        card.addView(titleRow)
        card.addView(chipRow(voxListeningPill, voxOpenPill, voxHangoverPill), matchWrap().withTop(dp(12)))
        card.addView(
            createText(
                "当前项目未暴露 VOX 设置接口，本区只展示状态，不改变底层参数。",
                12f,
                TEXT_SECONDARY,
                Typeface.NORMAL
            ),
            matchWrap().withTop(dp(10))
        )
        return card
    }

    private fun buildDiscoveryCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("发现车友"))
        card.addView(
            createText("仅连接通过 MotoCom 身份校验的设备", 13f, TEXT_SECONDARY, Typeface.NORMAL),
            matchWrap().withTop(dp(4))
        )
        card.addView(deviceEmptyText, matchWrap().withTop(dp(12)))
        card.addView(deviceListContainer, matchWrap().withTop(dp(8)))
        return card
    }

    private fun buildSettingsCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("设置"))
        card.addView(createText("本机昵称", 13f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(12)))
        card.addView(
            riderNameInput,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).withTop(dp(6))
        )
        card.addView(
            readOnlyRow("连接方式", "优先 Wi-Fi Direct（P2P）", "同一 Wi-Fi 下自动使用局域网发现"),
            matchWrap().withTop(dp(12))
        )
        card.addView(
            readOnlyRow("高级", "日志诊断 · 帮助与反馈 · 关于我们", "本轮只做展示，不新增入口逻辑"),
            matchWrap().withTop(dp(10))
        )
        return card
    }

    private fun buildLogCard(): View {
        val card = createCard()
        card.addView(createSectionTitle("日志诊断"))
        logScroll = ScrollView(activity).apply { addView(logText, matchWrap()) }
        card.addView(
            logScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)).withTop(dp(10))
        )
        return card
    }

    private fun setIntercomRunning(running: Boolean) {
        setRunning(running, canStart)
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

    private fun deviceRow(device: LanRiderDevice): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(FIELD_COLOR, dp(14), BORDER_COLOR, dp(1))
        }

        row.addView(createIconCircle("骑", ACCENT_GREEN_SOFT))
        row.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(createText(device.name.ifBlank { "车友" }, 16f, TEXT_PRIMARY, Typeface.BOLD))
                addView(
                    createText("${device.ip}:${device.port} · 局域网发现", 12f, TEXT_SECONDARY, Typeface.NORMAL),
                    matchWrap().withTop(dp(3))
                )
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).withLeft(dp(10))
        )
        row.addView(
            Button(activity).apply {
                text = "连接"
                textSize = 14f
                isAllCaps = false
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                background = rounded(ACCENT_GREEN, dp(12))
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(48)
                minimumHeight = dp(48)
                setOnClickListener { onConnectDevice(device) }
            },
            LinearLayout.LayoutParams(dp(76), dp(48))
        )
        return row
    }

    private fun updateVoxDisplay(level: Float) {
        val open = mediaConnected && level > 0.12f
        voxListeningPill.applyPill(!mediaConnected)
        voxOpenPill.applyPill(open)
        voxHangoverPill.applyPill(mediaConnected && !open)
    }

    private fun updateActionButton() {
        actionButton.text = if (intercomRunning) "结束对讲" else "启动摩声"
        actionButton.setTextColor(Color.WHITE)
        animateButtonColor(targetButtonColor(statusText.text.toString()))
    }

    private fun updateMotionForStatus(message: String) {
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

    private fun isConnectedStatus(message: String): Boolean = message == VOICE_CONNECTED_STATUS

    private fun createCard(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(WHITE, dp(18), BORDER_COLOR, dp(1))
            elevation = dp(2).toFloat()
        }

    private fun createSectionTitle(text: String): TextView =
        createText(text, 16f, TEXT_PRIMARY, Typeface.BOLD)

    private fun createText(text: String, size: Float, color: Int, style: Int): TextView =
        TextView(activity).apply {
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
        val label = text.toString()
        isSelected = active
        contentDescription = "$label: ${if (active) "active" else "inactive"}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            stateDescription = if (active) "active" else "inactive"
        }
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
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            chips.toList().chunked(2).forEachIndexed { rowIndex, rowChips ->
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        rowChips.forEachIndexed { index, chip ->
                            addView(chip, wrapWrap().apply { if (index > 0) leftMargin = dp(8) })
                        }
                    },
                    wrapWrap().apply { if (rowIndex > 0) topMargin = dp(8) }
                )
            }
        }

    private fun readOnlyRow(title: String, value: String, detail: String): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(FIELD_COLOR, dp(12), BORDER_COLOR, dp(1))
            addView(createText(title, 12f, TEXT_MUTED, Typeface.NORMAL))
            addView(createText(value, 15f, TEXT_PRIMARY, Typeface.BOLD), matchWrap().withTop(dp(4)))
            addView(createText(detail, 12f, TEXT_SECONDARY, Typeface.NORMAL), matchWrap().withTop(dp(3)))
        }

    private fun separator(): View = View(activity).apply { setBackgroundColor(BORDER_COLOR) }

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable =
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
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        const val PEER_FOUND_STATUS = "已发现车友"
        const val SIGNALING_CONNECTED_STATUS = "信令已连接"
        const val MEDIA_INITIALIZING_STATUS = "媒体初始化中"
        const val VOICE_CONNECTED_STATUS = "语音通道已连接"
        const val ENDED_STATUS = "对讲已结束"
        const val WIFI_OFF_STATUS = "⚠️ 请先打开 Wi-Fi开关"

        private const val TAG = "MotoIntercom"
        private const val COLOR_ANIMATION_MS = 280L
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
