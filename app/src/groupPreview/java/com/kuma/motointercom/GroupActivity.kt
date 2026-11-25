package com.kuma.motointercom

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*

/** UI observes the service. Navigation never leaves a room and saved state never restores a code. */
internal class GroupActivity : ComponentActivity() {
    private var service: GroupIntercomService? = null
    private var bound = false
    private var resumed = false
    private var pending = false
    private var pendingCode: String? = null
    private var pendingNetwork: GroupCloudStartRequest? = null
    private var permissionsReturned = false
    private val state = mutableStateOf(GroupServiceState())
    private lateinit var cloudPrefs: GroupCloudPreferences
    private val listener: (GroupServiceState) -> Unit = { state.value = it }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound) return
            service = (binder as GroupIntercomService.LocalBinder).service()
            service?.addGroupListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; state.value = GroupServiceState(message = "连接已结束，请重新创建或加入") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cloudPrefs = GroupCloudPreferences(this)
        setContent { MotoComTheme {
            GroupScreen(
                state = state.value,
                onStart = ::requestStart,
                onAction = { service?.groupAction(it) },
                onRoute = { service?.groupRoute(it) },
                onVox = { service?.groupVox(it) },
                onBack = ::finish,
                onSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                onCopy = { code -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("房间码", code)) },
                onStartNetwork = ::requestNetworkStart,
                initialServerUrl = cloudPrefs.loadServerUrl(),
                onSaveCloudSettings = cloudPrefs::saveServerUrl
            )
        } }
    }
    override fun onStart() {
        super.onStart()
        bound = try { bindService(Intent(this, GroupIntercomService::class.java), connection, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
    }
    override fun onResume() { super.onResume(); resumed = true; if (pending && permissionsReturned) completeStart() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        service?.removeGroupListener(listener); service = null
        if (bound) { bound = false; unbindService(connection) }
        super.onStop()
    }
    override fun onDestroy() { pending = false; pendingCode = null; pendingNetwork = null; super.onDestroy() }
    internal fun requestStart(code: String?) {
        if (!resumed || pending || state.value.busy) return
        if (code != null && !code.matches(Regex("[0-9]{6}"))) return
        pending = true; pendingCode = code; pendingNetwork = null; permissionsReturned = false
        val missing = missingGroupPromptPermissions(groupRequiredPermissions())
        if (missing.isEmpty()) { permissionsReturned = true; completeStart() }
        else requestPermissions(missing.toTypedArray(), 8056)
    }
    internal fun requestNetworkStart(request: GroupCloudStartRequest) {
        if (!resumed || pending || state.value.busy) return
        val normalizedUrl = try {
            GroupCloudUrlPolicy.normalizeAndValidate(request.serverUrl)
        } catch (error: IllegalArgumentException) {
            state.value = state.value.copy(message = error.message ?: "云端服务地址无效")
            return
        }
        cloudPrefs.saveServerUrl(normalizedUrl)
        val normalizedRequest = when (request) {
            is GroupCloudStartRequest.Create -> request.copy(serverUrl = normalizedUrl)
            is GroupCloudStartRequest.Join -> request.copy(serverUrl = normalizedUrl)
        }
        pending = true; pendingCode = null; pendingNetwork = normalizedRequest; permissionsReturned = false
        val basePermissions = if (normalizedRequest.mode == GroupMode.HYBRID) groupRequiredPermissions()
        else networkGroupPromptPermissions()
        val missing = missingGroupPromptPermissions(basePermissions, hybrid = normalizedRequest.mode == GroupMode.HYBRID)
        if (missing.isEmpty()) { permissionsReturned = true; completeStart() }
        else requestPermissions(missing.toTypedArray(), 8056)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8056 && pending) {
            permissionsReturned = true
            if (resumed) completeStart()
        }
    }
    private fun completeStart() {
        if (!pending || !resumed || isFinishing || isDestroyed) return
        val code = pendingCode
        val networkRequest = pendingNetwork
        pending = false; pendingCode = null; pendingNetwork = null
        val intent = if (networkRequest != null) {
            val isHybridRequest = networkRequest.mode == GroupMode.HYBRID
            if (isHybridRequest) {
                if (!hasGroupPermissions()) {
                    state.value = state.value.copy(message = "请授予麦克风与附近设备权限，再重试双模群组")
                    return
                }
            } else if (!hasNetworkGroupPermissions()) {
                state.value = state.value.copy(message = "请授予麦克风权限，再重试网络群组")
                return
            }
            Intent(this, GroupIntercomService::class.java)
                .setAction(
                    if (isHybridRequest) GroupIntercomService.ACTION_START_HYBRID_GROUP
                    else GroupIntercomService.ACTION_START_NETWORK_GROUP
                )
                .putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, networkRequest.serverUrl)
                .apply {
                    when (networkRequest) {
                        is GroupCloudStartRequest.Create -> putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, networkRequest.requireApproval)
                        is GroupCloudStartRequest.Join -> putExtra(GroupIntercomService.EXTRA_CLOUD_INVITE_CODE, networkRequest.inviteCode.value)
                    }
                }
        } else {
            if (!hasGroupPermissions()) { state.value = state.value.copy(message = "请授予麦克风与附近设备权限，再重试"); return }
            Intent(this, GroupIntercomService::class.java).setAction(GroupIntercomService.ACTION_START_GROUP).apply {
                if (code != null) putExtra(GroupIntercomService.EXTRA_GROUP_CODE, code)
            }
        }
        try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent) }
        catch (_: Exception) { state.value = state.value.copy(message = "无法启动，请回到页面重试") }
    }
}

@Composable
internal fun GroupScreen(
    state: GroupServiceState,
    onStart: (String?) -> Unit,
    onAction: (GroupSessionEvent) -> Unit,
    onRoute: (AudioRouteSelection) -> Unit,
    onVox: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onStartNetwork: (GroupCloudStartRequest) -> Unit = {},
    initialServerUrl: String = GroupCloudPreferences.DEFAULT_DEV_SERVER_URL,
    onSaveCloudSettings: (String) -> Unit = {}
) {
    var selectedMode by remember { mutableStateOf(GroupMode.LOCAL) }
    var code by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var inviteInput by remember { mutableStateOf("") }
    var requireApproval by remember { mutableStateOf(true) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmNetworkOwnerExit by remember { mutableStateOf(false) }
    var roleTargetId by remember { mutableStateOf<String?>(null) }
    var showAllMembers by remember { mutableStateOf(false) }
    var showAllPending by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val room = snapshot?.view
    val activeMode = if (state.busy || (snapshot != null && snapshot.phase != GroupPhase.IDLE)) {
        snapshot?.mode ?: selectedMode
    } else selectedMode
    LaunchedEffect(snapshot?.isOwner, room == null, snapshot?.mode) {
        if (snapshot?.isOwner != true || room == null) {
            confirmEnd = false
            confirmNetworkOwnerExit = false
            roleTargetId = null
            showAllMembers = false
            showAllPending = false
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("收起对讲") }
                Text(
                    when (activeMode) {
                        GroupMode.NETWORK -> "网络群组 · R5 预览"
                        GroupMode.HYBRID -> "双模群组 · R5 预览"
                        GroupMode.LOCAL -> "摩声群组 · R5 预览"
                    },
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    when (activeMode) {
                        GroupMode.NETWORK -> "火山引擎 RTC · 单公共频道 · 最多 50 人"
                        GroupMode.HYBRID -> "本地优先 · 断线整组切云端 · 公共/领队/私聊 · 最多 4 人"
                        GroupMode.LOCAL -> "附近连接，无需互联网 · 含房主最多四人"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(state.message, Modifier.testTag("group_status"), style = MaterialTheme.typography.titleMedium)
                if (!state.busy && (snapshot == null || snapshot.phase == GroupPhase.IDLE)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { selectedMode = GroupMode.LOCAL },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mode_local")
                        ) {
                            Text(if (selectedMode == GroupMode.LOCAL) "✓ 本地群组" else "本地群组")
                        }
                        OutlinedButton(
                            onClick = { selectedMode = GroupMode.HYBRID },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mode_hybrid")
                        ) {
                            Text(if (selectedMode == GroupMode.HYBRID) "✓ 双模 4 人" else "双模 4 人")
                        }
                        OutlinedButton(
                            onClick = { selectedMode = GroupMode.NETWORK },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("mode_network")
                        ) {
                            Text(if (selectedMode == GroupMode.NETWORK) "✓ 网络群组" else "网络群组")
                        }
                    }
                    if (selectedMode == GroupMode.LOCAL) {
                        Button(onClick = { onStart(null) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_create")) { Text("创建房间") }
                        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位房间码") },
                            modifier = Modifier.fillMaxWidth().testTag("group_code_input"), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Button(onClick = { onStart(code); code = "" }, enabled = code.matches(Regex("[0-9]{6}")),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_join")) { Text("加入房间") }
                    } else {
                        val creatingHybrid = selectedMode == GroupMode.HYBRID
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("云端服务地址（HTTPS 或本机回环）") },
                            modifier = Modifier.fillMaxWidth().testTag("network_server_input"),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = requireApproval,
                                    role = Role.Switch,
                                    onValueChange = { requireApproval = it }
                                )
                                .testTag("network_require_approval_prep"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("新成员加入需群主审批", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (requireApproval) "开启：持邀请码申请后需群主点击同意"
                                    else "关闭：持邀请码可直接加入房间",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = requireApproval, onCheckedChange = null)
                        }
                        Button(
                            onClick = {
                                onSaveCloudSettings(serverUrl)
                                onStartNetwork(GroupCloudStartRequest.Create(serverUrl, requireApproval, selectedMode))
                            },
                            enabled = serverUrl.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(if (creatingHybrid) "hybrid_create" else "network_create")
                        ) { Text(if (creatingHybrid) "创建双模房间（4 人）" else "创建网络房间") }
                        OutlinedTextField(
                            value = inviteInput,
                            onValueChange = { inviteInput = it.filter { ch -> ch in '0'..'9' }.take(GroupCloudInviteCode.LENGTH) },
                            label = { Text(if (creatingHybrid) "6 位数字双模房间邀请码" else "6 位数字网络房间邀请码") },
                            modifier = Modifier.fillMaxWidth().testTag("network_invite_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Button(
                            onClick = {
                                val codeObj = GroupCloudInviteCode(inviteInput)
                                inviteInput = ""
                                onSaveCloudSettings(serverUrl)
                                onStartNetwork(GroupCloudStartRequest.Join(serverUrl, codeObj, selectedMode))
                            },
                            enabled = serverUrl.isNotBlank() && GroupCloudInviteCode.isValid(inviteInput),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag(if (creatingHybrid) "hybrid_join" else "network_join")
                        ) { Text(if (creatingHybrid) "申请加入双模房间" else "申请加入网络房间") }
                    }
                    TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("权限设置") }
                } else {
                    val isNetwork = snapshot?.mode == GroupMode.NETWORK
                    val isHybrid = snapshot?.mode == GroupMode.HYBRID
                    val isCloudBacked = isNetwork || isHybrid
                    snapshot?.matches?.forEach { match ->
                        OutlinedButton(onClick = { onAction(GroupSessionEvent.Select(match.descriptor.room)) },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("${match.descriptor.nickname} · ${match.descriptor.room.instanceId.takeLast(8)}")
                        }
                    }
                    snapshot?.code?.takeIf { snapshot.host }?.let { joinCode ->
                        Text("房间码 ${joinCode.digits}", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { onCopy(joinCode.digits) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制房间码") }
                    }
                    if (isCloudBacked && snapshot.isOwner) {
                        snapshot.inviteCode?.let { invite ->
                            Text("邀请码 $invite", Modifier.testTag("network_invite_code"), style = MaterialTheme.typography.headlineSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onCopy(invite) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制邀请码") }
                                TextButton(
                                    onClick = { onAction(GroupSessionEvent.RotateInvite) },
                                    enabled = snapshot.phase == GroupPhase.IN_ROOM && !snapshot.cloudOffline,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("network_rotate_invite")
                                ) { Text("刷新邀请码") }
                            }
                        }
                    }
                    if (room != null) {
                        val inRoom = snapshot.phase == GroupPhase.IN_ROOM
                        val actionsEnabled = inRoom && !snapshot.channelSwitching && !snapshot.transportSwitching && !snapshot.cloudOffline
                        val occupiedMembers = room.members
                            .filter { it.status != GroupMemberStatus.WAITING }
                            .sortedWith(
                                compareBy<GroupMember> { it.lease.deviceId != room.ownerId }
                                    .thenBy { it.lease.deviceId !in snapshot.activeSpeakerIds }
                                    .thenBy { it.lease.deviceId != snapshot.local?.deviceId }
                                    .thenBy { snapshot.names[it.lease.deviceId].orEmpty() }
                            )
                        val partnerName = snapshot.privatePartner?.lease?.deviceId?.let { snapshot.names[it] } ?: "队友"
                        val selfBusyPrivate = snapshot.currentChannel is GroupChannel.Private ||
                            snapshot.outgoingPrivateInvite != null ||
                            snapshot.incomingPrivateInvite != null
                        val voiceStatusText = when {
                            snapshot.transportSwitching -> "正在切换传输路径，已断开旧语音通道（零双路音频）"
                            snapshot.channelSwitching -> "正在切换频道，旧频道语音已断开"
                            isNetwork || (isHybrid && snapshot.activeTransport == GroupTransport.CLOUD) -> when (snapshot.networkMediaState) {
                                GroupNetworkMediaState.WAITING_FOR_PEER -> if (isHybrid) "当前频道在线 1 人，云端 RTC 待机中，不消耗停留时长"
                                else "公共频道在线 1 人，RTC 待机中，不消耗停留时长"
                                GroupNetworkMediaState.JOINING -> "正在加入火山引擎 RTC 音频房间"
                                GroupNetworkMediaState.JOINED -> if (isHybrid) "双模云端兜底语音已连接" else "火山引擎 RTC 语音已连接"
                                GroupNetworkMediaState.FAILED -> "RTC 语音连接失败，正在自动重试"
                                GroupNetworkMediaState.IDLE -> "正在准备 RTC 语音凭证"
                            }
                            snapshot.voiceReady -> when (snapshot.currentChannel) {
                                GroupChannel.Public -> if (isHybrid) "本地直连 · 公共频道语音已就绪" else "公共频道语音已就绪"
                                GroupChannel.Rider -> if (isHybrid) "本地直连 · 领队频道语音已就绪" else "领队频道语音已就绪"
                                is GroupChannel.Private -> if (isHybrid) "本地直连 · 私聊语音已就绪" else "私聊语音已就绪"
                            }
                            snapshot.channelMembers.size < 2 -> "当前频道仅你 1 人，等待成员进入此频道"
                            else -> if (isHybrid) "本地直连语音确认中" else "语音连接确认中"
                        }

                        if (isHybrid) {
                            Card(Modifier.fillMaxWidth().testTag("hybrid_transport_card")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val transportTitle = when {
                                        snapshot.transportSwitching -> "双模传输：正在切换（零双路音频保护）"
                                        snapshot.activeTransport == GroupTransport.LOCAL -> "双模传输：本地 Wi-Fi Direct 直连"
                                        else -> "双模传输：云端兜底（火山引擎 RTC）"
                                    }
                                    Text(transportTitle, Modifier.testTag("hybrid_transport_summary"), style = MaterialTheme.typography.titleMedium)
                                    if (snapshot.cloudOffline) {
                                        Text(
                                            "云端暂离线，当前保持本地直连通话，正在后台重连云端",
                                            Modifier.testTag("hybrid_cloud_offline_banner"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (snapshot.activeTransport == GroupTransport.CLOUD && snapshot.activeSpeakerIds.isNotEmpty()) {
                                        Text(
                                            "正在发言：" + snapshot.activeSpeakerIds.joinToString("、") {
                                                snapshot.names[it] ?: "骑士"
                                            },
                                            Modifier.testTag("network_active_speakers"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (snapshot.isOwner) {
                                        val targetTransport = if (snapshot.activeTransport == GroupTransport.LOCAL) GroupTransport.CLOUD
                                        else GroupTransport.LOCAL
                                        OutlinedButton(
                                            onClick = { onAction(GroupSessionEvent.SwitchTransport(targetTransport)) },
                                            enabled = actionsEnabled,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hybrid_switch_transport")
                                        ) {
                                            Text(
                                                if (targetTransport == GroupTransport.CLOUD) "手动切至云端兜底"
                                                else "手动切回本地直连"
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (isNetwork) "网络公共频道 · 全队 ${occupiedMembers.size}/${snapshot.maxMembers} 人"
                                    else "${snapshot.currentChannel.displayLabel()} · 频道内 ${snapshot.channelMembers.size} 人 · 全队 ${occupiedMembers.size}/4 人",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(voiceStatusText, Modifier.testTag("group_voice_status"), style = MaterialTheme.typography.bodyMedium)
                                if (isCloudBacked) {
                                    if (isNetwork) {
                                        Text(
                                            "语音层：火山引擎 RTC · 音频选路 · 单公共频道",
                                            Modifier.testTag("network_transport_summary"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (snapshot.activeSpeakerIds.isNotEmpty()) {
                                            Text(
                                                "正在发言：" + snapshot.activeSpeakerIds.joinToString("、") {
                                                    snapshot.names[it] ?: "骑士"
                                                },
                                                Modifier.testTag("network_active_speakers"),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    if (snapshot.isOwner) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .toggleable(
                                                    value = snapshot.requireApproval,
                                                    enabled = actionsEnabled,
                                                    role = Role.Switch,
                                                    onValueChange = { onAction(GroupSessionEvent.SetRequireApproval(it)) }
                                                )
                                                .testTag("network_require_approval_toggle"),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                                Text("加入需群主审批", style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    if (snapshot.requireApproval) "已开启：新成员申请需群主同意"
                                                    else "已关闭：持邀请码可直接加入",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Switch(checked = snapshot.requireApproval, onCheckedChange = null, enabled = actionsEnabled)
                                        }
                                    }
                                }
                                if (!isNetwork) {
                                    OutlinedButton(
                                        onClick = { onAction(GroupSessionEvent.SwitchChannel(GroupChannel.Public)) },
                                        enabled = actionsEnabled && snapshot.currentChannel != GroupChannel.Public,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("channel_public")
                                    ) {
                                        Text(if (snapshot.currentChannel == GroupChannel.Public) "✓ 公共频道（当前）" else "切换到公共频道")
                                    }
                                    if (snapshot.canEnterRider) {
                                        OutlinedButton(
                                            onClick = { onAction(GroupSessionEvent.SwitchChannel(GroupChannel.Rider)) },
                                            enabled = actionsEnabled && snapshot.currentChannel != GroupChannel.Rider,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("channel_rider")
                                        ) {
                                            Text(if (snapshot.currentChannel == GroupChannel.Rider) "✓ 领队频道（当前）" else "切换到领队频道")
                                        }
                                    } else {
                                        Text(
                                            if (snapshot.riderIncludesFormation) "领队频道仅限群主、骑行官、头车与尾车进入"
                                            else "领队频道仅限群主与骑行官进入",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (snapshot.channelSwitching) {
                                        Text(
                                            "频道切换中：正在前往${snapshot.pendingSwitchTarget?.displayLabel() ?: "目标频道"}，请勿重复切换",
                                            Modifier.testTag("channel_switching"),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (snapshot.host) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .toggleable(
                                                    value = snapshot.riderIncludesFormation,
                                                    enabled = inRoom,
                                                    role = Role.Switch,
                                                    onValueChange = { onAction(GroupSessionEvent.SetRiderIncludesFormation(it)) }
                                                )
                                                .testTag("rider_includes_formation"),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                                Text("领队频道包含头车 / 尾车", style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    if (snapshot.riderIncludesFormation) "已开启：头车与尾车可进入领队频道"
                                                    else "已关闭：仅群主与骑行官可进入领队频道",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            Switch(checked = snapshot.riderIncludesFormation, onCheckedChange = null, enabled = inRoom)
                                        }
                                    }
                                }
                            }
                        }

                        if (isCloudBacked && snapshot.isOwner && snapshot.pendingJoinRequests.isNotEmpty()) {
                            val visiblePending = if (showAllPending) snapshot.pendingJoinRequests
                            else snapshot.pendingJoinRequests.take(3)
                            Card(Modifier.fillMaxWidth().testTag("network_pending_requests_card")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("待审批加入申请（${snapshot.pendingJoinRequests.size}）", style = MaterialTheme.typography.titleMedium)
                                    visiblePending.forEach { req ->
                                        Text("${req.nickname} 申请加入网络房间")
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = { onAction(GroupSessionEvent.ApproveJoin(req.requestId)) },
                                                enabled = inRoom && occupiedMembers.size < snapshot.maxMembers,
                                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("approve_${req.requestId}")
                                            ) { Text("同意加入") }
                                            OutlinedButton(
                                                onClick = { onAction(GroupSessionEvent.RejectJoin(req.requestId)) },
                                                enabled = inRoom,
                                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reject_${req.requestId}")
                                            ) { Text("拒绝") }
                                        }
                                    }
                                    if (snapshot.pendingJoinRequests.size > visiblePending.size || showAllPending) {
                                        TextButton(
                                            onClick = { showAllPending = !showAllPending },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(if (showAllPending) "收起申请" else "展开全部 ${snapshot.pendingJoinRequests.size} 条申请")
                                        }
                                    }
                                }
                            }
                        }

                        snapshot.incomingPrivateInvite?.let { invite ->
                            Card(Modifier.fillMaxWidth().testTag("private_incoming_card")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("私聊来电", style = MaterialTheme.typography.titleMedium)
                                    Text("${snapshot.names[invite.callerId] ?: "骑士"} 邀请你 1:1 私聊")
                                    Button(
                                        onClick = { onAction(GroupSessionEvent.AcceptPrivate(invite.inviteId)) },
                                        enabled = actionsEnabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("private_accept")
                                    ) { Text("接受私聊") }
                                    OutlinedButton(
                                        onClick = { onAction(GroupSessionEvent.DeclinePrivate(invite.inviteId)) },
                                        enabled = inRoom,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("private_decline")
                                    ) { Text("拒绝私聊") }
                                }
                            }
                        }

                        snapshot.outgoingPrivateInvite?.let { invite ->
                            Card(Modifier.fillMaxWidth().testTag("private_outgoing_card")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("正在呼叫私聊", style = MaterialTheme.typography.titleMedium)
                                    Text("等待 ${snapshot.names[invite.calleeId] ?: "骑士"} 接受（30 秒内有效）")
                                    OutlinedButton(
                                        onClick = { onAction(GroupSessionEvent.CancelPrivate(invite.inviteId)) },
                                        enabled = inRoom,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("private_cancel")
                                    ) { Text("取消私聊呼叫") }
                                }
                            }
                        }

                        if (snapshot.currentChannel is GroupChannel.Private) {
                            Card(Modifier.fillMaxWidth().testTag("private_active_banner")) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("1:1 私聊通话中", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (snapshot.privatePartner?.status == GroupMemberStatus.RESERVED)
                                            "对方（$partnerName）重连中，席位暂时保留 60 秒"
                                        else "正在与 $partnerName 私聊，其他队员听不到此频道"
                                    )
                                    Button(
                                        onClick = { onAction(GroupSessionEvent.EndPrivate) },
                                        enabled = actionsEnabled,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("private_end")
                                    ) { Text("结束私聊并返回公共频道") }
                                }
                            }
                        }

                        Text(
                            "成员列表（${occupiedMembers.size}/${snapshot.maxMembers}）",
                            Modifier.testTag("group_member_count"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        val visibleMembers = if (showAllMembers || occupiedMembers.size <= 12) occupiedMembers
                        else occupiedMembers.take(12)
                        visibleMembers.forEach { member ->
                            val id = member.lease.deviceId
                            val self = id == snapshot.local?.deviceId
                            val peerBusyPrivate = member.channel is GroupChannel.Private ||
                                room.privateInvites.any { it.involves(id) }
                            val canCallPrivate = actionsEnabled && member.status == GroupMemberStatus.ADMITTED &&
                                !selfBusyPrivate && !peerBusyPrivate
                            val statusText = when {
                                member.status == GroupMemberStatus.RESERVED -> "重连中，席位暂时保留"
                                isNetwork && !self -> "已加入"
                                isNetwork -> if (snapshot.networkMediaState == GroupNetworkMediaState.JOINED) "RTC 在线" else "等待 RTC 语音"
                                !member.audioAvailable -> "音频暂不可用"
                                else -> "已加入"
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${snapshot.names[id] ?: "骑士"} · ${member.role.displayLabel()}" +
                                            (if (self) " · 我" else "") +
                                            (if (id in snapshot.activeSpeakerIds) " · 发言中" else "")
                                    )
                                    Text("频道：${member.channel.displayLabel()} · $statusText")
                                    if (!self) {
                                        if (!isNetwork) {
                                            TextButton(
                                                onClick = { onAction(GroupSessionEvent.RequestPrivate(id)) },
                                                enabled = canCallPrivate,
                                                modifier = Modifier.heightIn(min = 48.dp).testTag("private_call_$id")
                                            ) { Text("发起私聊") }
                                        }
                                        TextButton(
                                            onClick = { onAction(GroupSessionEvent.Block(id, !snapshot.participation.isBlocked(id))) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(if (snapshot.participation.isBlocked(id)) "恢复本机收听" else "在本机静音此成员")
                                        }
                                        if (snapshot.isOwner) {
                                            if (!isNetwork) {
                                                TextButton(
                                                    onClick = { roleTargetId = id },
                                                    enabled = actionsEnabled,
                                                    modifier = Modifier.heightIn(min = 48.dp).testTag("member_role_$id")
                                                ) { Text("修改角色") }
                                            }
                                            if (isCloudBacked && member.status == GroupMemberStatus.ADMITTED) {
                                                TextButton(
                                                    onClick = { onAction(GroupSessionEvent.TransferOwner(id, alsoLeave = false)) },
                                                    enabled = actionsEnabled,
                                                    modifier = Modifier.heightIn(min = 48.dp).testTag("member_transfer_$id")
                                                ) { Text("设为新群主") }
                                            }
                                            TextButton(
                                                onClick = { onAction(GroupSessionEvent.Remove(id)) },
                                                enabled = actionsEnabled,
                                                modifier = Modifier.heightIn(min = 48.dp).testTag("member_remove_$id")
                                            ) { Text("移出房间") }
                                        }
                                    }
                                }
                            }
                        }
                        if (!isNetwork && (!isHybrid || snapshot.activeTransport == GroupTransport.LOCAL)) {
                            room.links
                                .filter { !it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second)) }
                                .forEach { link ->
                                    Text(
                                        "${snapshot.names[link.lease.pair.first] ?: "骑士"} ↔ " +
                                            "${snapshot.names[link.lease.pair.second] ?: "骑士"}：语音待确认",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                        }
                        if (occupiedMembers.size > visibleMembers.size || showAllMembers) {
                            TextButton(
                                onClick = { showAllMembers = !showAllMembers },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("toggle_all_members")
                            ) {
                                Text(if (showAllMembers) "收起成员列表" else "查看全部 ${occupiedMembers.size} 人")
                            }
                        }
                        if (snapshot.isOwner) snapshot.removed.forEach { id ->
                            TextButton(onClick = { onAction(GroupSessionEvent.Unblock(id)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("解除移除限制 · ${snapshot.names[id] ?: "骑士"}")
                            }
                        }
                        Button(
                            onClick = { onAction(GroupSessionEvent.Mute(!snapshot.participation.selfMuted)) },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(if (snapshot.participation.selfMuted) "打开我的麦克风" else "静音我的麦克风")
                        }
                        Text(state.audioLabel)
                        AudioRouteSelection.entries.forEach { route ->
                            TextButton(onClick = { onRoute(route) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text((if (state.route == route) "✓ " else "") + when (route) {
                                    AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"; AudioRouteSelection.EARPIECE -> "手机听筒 / 有线耳机"; AudioRouteSelection.SPEAKER -> "手机扬声器"
                                })
                            }
                        }
                        if (!isNetwork) {
                            TextButton(onClick = { onVox(!state.vox) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(
                                    if (isHybrid && snapshot.activeTransport == GroupTransport.CLOUD) "云端兜底语音自动拾音中（切回本地后沿用声控设置）"
                                    else if (state.vox) "声控已开启 · 点击关闭" else "声控已关闭 · 点击开启"
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            when {
                                isCloudBacked && snapshot?.isOwner == true && room != null -> confirmNetworkOwnerExit = true
                                snapshot?.host == true -> confirmEnd = true
                                else -> onAction(GroupSessionEvent.Leave)
                            }
                        },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_leave")) {
                        Text(
                            when {
                                isCloudBacked && snapshot?.isOwner == true && room != null -> "转让群主 / 解散房间"
                                snapshot?.host == true -> "结束全队对讲"
                                else -> "离开 / 取消"
                            }
                        )
                    }
                }
            }
        }
    }
    val roleTarget = if (snapshot?.mode != GroupMode.NETWORK && snapshot?.isOwner == true && room != null && roleTargetId != null) {
        room.members.singleOrNull { it.lease.deviceId == roleTargetId && it.status != GroupMemberStatus.WAITING }
    } else null
    if (roleTarget != null && snapshot != null) {
        val targetId = roleTarget.lease.deviceId
        AlertDialog(
            onDismissRequest = { roleTargetId = null },
            title = { Text("修改 ${snapshot.names[targetId] ?: "骑士"} 的角色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        GroupRole.MEMBER to "普通成员（仅公共与私聊）",
                        GroupRole.RIDING_OFFICER to "骑行官（可进入领队频道）",
                        GroupRole.HEAD to "头车（全队唯一）",
                        GroupRole.TAIL to "尾车（全队唯一）"
                    ).forEach { (role, label) ->
                        TextButton(
                            onClick = {
                                roleTargetId = null
                                onAction(GroupSessionEvent.SetRole(targetId, role))
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("role_option_${role.name}")
                        ) {
                            Text((if (roleTarget.role == role) "✓ " else "") + label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { roleTargetId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
            }
        )
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("结束全队对讲？") },
        text = { Text("本地模式不支持转让群主，离开将直接解散全队，所有成员都会退出且当前房间码立即失效。") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onAction(GroupSessionEvent.Leave) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("结束房间") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续对讲") } })
    if (confirmNetworkOwnerExit && snapshot != null && room != null) {
        val transferable = room.members.filter {
            it.status == GroupMemberStatus.ADMITTED && it.lease.deviceId != snapshot.local?.deviceId
        }
        AlertDialog(
            onDismissRequest = { confirmNetworkOwnerExit = false },
            title = { Text("群主离开方式") },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        if (transferable.isNotEmpty()) "当前模式不能留下无主群。请选择一位在线成员转让群主并离开，或直接解散全队。"
                        else "当前没有其他可接任的在线成员，离开将直接解散该房间。"
                    )
                    transferable.forEach { member ->
                        val targetId = member.lease.deviceId
                        Button(
                            onClick = {
                                confirmNetworkOwnerExit = false
                                onAction(GroupSessionEvent.TransferOwner(targetId, alsoLeave = true))
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("transfer_leave_$targetId")
                        ) {
                            Text("转让给 ${snapshot.names[targetId] ?: "骑士"} 并离开")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNetworkOwnerExit = false
                        onAction(GroupSessionEvent.EndRoom)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("network_confirm_disband")
                ) { Text("解散全队") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNetworkOwnerExit = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续对讲") }
            }
        )
    }
}

@Preview(widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(widthDp = 840, heightDp = 700)
@Composable private fun GroupScreenPreview() { MotoComTheme { GroupScreen(GroupServiceState(), {}, {}, {}, {}, {}, {}, {}) } }
