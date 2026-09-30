package com.kuma.motointercom

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.kuma.motointercom.group.*

/** UI observes the service. Navigation never leaves a room and saved state never restores a code. */
internal class GroupActivity : ComponentActivity() {
    private var service: IntercomService? = null
    private var bound = false
    private var resumed = false
    private var pending by mutableStateOf(false)
    private var pendingCode: String? = null
    private var permissionsReturned = false
    private val state = mutableStateOf(GroupServiceState())
    private val listener: (GroupServiceState) -> Unit = { state.value = it }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bound) return
            service = (binder as IntercomService.LocalBinder).service()
            service?.addGroupListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; state.value = GroupServiceState(message = "连接已结束，请重新创建或加入") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent { MotoComTheme {
            GroupScreen(state.value, ::requestStart, { service?.groupAction(it) },
                { service?.groupRoute(it) }, { service?.groupVox(it) }, ::finish,
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { code -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("房间码", code)) }, startPending = pending)
        } }
    }
    override fun onStart() {
        super.onStart()
        bound = try { bindService(Intent(this, IntercomService::class.java), connection, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
    }
    override fun onResume() { super.onResume(); resumed = true; if (pending && permissionsReturned) completeStart() }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        service?.removeGroupListener(listener); service = null
        if (bound) { bound = false; unbindService(connection) }
        super.onStop()
    }
    override fun onDestroy() { pending = false; pendingCode = null; super.onDestroy() }
    internal fun requestStart(code: String?) {
        if (!resumed || pending || state.value.busy) return
        if (code != null && !code.matches(Regex("[0-9]{6}"))) return
        pending = true; pendingCode = code; permissionsReturned = false
        val missing = groupRequiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toMutableList()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
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
        val code = pendingCode; pending = false; pendingCode = null
        if (!hasGroupPermissions()) { state.value = state.value.copy(message = "请授予麦克风与附近设备权限，再重试"); return }
        val intent = Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_START_GROUP)
        if (code != null) intent.putExtra(IntercomService.EXTRA_GROUP_CODE, code)
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
    startPending: Boolean = false
) {
    var code by remember { mutableStateOf("") }
    var revealCode by remember { mutableStateOf(false) }
    var confirmEnd by remember(state.snapshot?.operation) { mutableStateOf(false) }
    var removeMember by remember(state.snapshot?.operation) { mutableStateOf<String?>(null) }
    val snapshot = state.snapshot
    val room = snapshot?.view
    LaunchedEffect(snapshot?.phase) {
        if (snapshot?.phase == GroupPhase.IN_ROOM) { code = ""; revealCode = false }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(Modifier.widthIn(max = androidx.compose.ui.res.dimensionResource(R.dimen.motocom_content_max_width))
                .fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(androidx.compose.ui.res.dimensionResource(R.dimen.motocom_page_horizontal_padding)),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回主页") }
                Text("四人离线对讲", style = MaterialTheme.typography.titleLarge)
                Text("附近连接，无需互联网 · 含房主最多四人", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotoComPanel {
                    Text(if (startPending) "正在等待权限确认" else state.message,
                        Modifier.testTag("group_status").semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium)
                    if (state.busy) Text("返回主页不会退出房间或取消连接。退出请使用下方操作。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.busy && (snapshot == null || snapshot.phase == GroupPhase.IDLE)) {
                    MotoComPanel {
                        Text("我是房主", style = MaterialTheme.typography.titleMedium)
                        Text("创建后，把房间码告诉附近车友。", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { onStart(null) }, enabled = !startPending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_create")) { Text("创建房间") }
                    }
                    MotoComPanel {
                        Text("加入车友的房间", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(code, { code = it.filter { digit -> digit in '0'..'9' }.take(6) },
                            label = { Text("六位房间码") }, enabled = !startPending,
                            modifier = Modifier.fillMaxWidth().testTag("group_code_input"), singleLine = true,
                            visualTransformation = if (revealCode) VisualTransformation.None else PasswordVisualTransformation(),
                            supportingText = { Text("输入房主提供的六位数字；失败后可直接重试。") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        TextButton(onClick = { revealCode = !revealCode }, enabled = !startPending) {
                            Text(if (revealCode) "隐藏房间码" else "显示房间码")
                        }
                        Button(onClick = { onStart(code) }, enabled = !startPending && code.matches(Regex("[0-9]{6}")),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_join")) { Text("加入房间") }
                    }
                    TextButton(onClick = onSettings) { Text("查看应用权限") }
                } else {
                    if (snapshot?.matches?.isNotEmpty() == true) MotoComPanel {
                        Text("选择目标房间", style = MaterialTheme.typography.titleMedium)
                        snapshot.matches.forEach { match ->
                            OutlinedButton(onClick = { onAction(GroupSessionEvent.Select(match.descriptor.room)) }, Modifier.fillMaxWidth()) {
                                Text("${match.descriptor.nickname} · ${match.descriptor.room.instanceId.takeLast(8)}")
                            }
                        }
                    }
                    snapshot?.code?.takeIf { snapshot.phase == GroupPhase.IN_ROOM || snapshot.phase == GroupPhase.RECONNECTING }?.let { joinCode ->
                        MotoComPanel {
                            Text("邀请车友", style = MaterialTheme.typography.titleMedium)
                            Text(joinCode.digits, style = MaterialTheme.typography.headlineSmall)
                            TextButton(onClick = { onCopy(joinCode.digits) }) { Text("复制房间码") }
                        }
                    }
                    if (room != null) {
                        Text("房间成员 ${room.members.count { it.status != GroupMemberStatus.WAITING }} / 4",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (snapshot.voiceReady) "全队语音已就绪" else "语音连接确认中", Modifier.testTag("group_voice_status"))
                        room.members.filter { it.status != GroupMemberStatus.WAITING }.forEach { member ->
                            val id = member.lease.deviceId
                            val self = id == snapshot.local?.deviceId
                            MotoComPanel {
                                Text("${snapshot.names[id] ?: "骑士"}${if (id == room.hostId) " · 房主" else ""}${if (self) " · 我" else ""}",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(when { member.status == GroupMemberStatus.RESERVED -> "重连中，席位暂时保留"
                                    !member.audioAvailable -> "音频暂不可用"; else -> "已加入" }, style = MaterialTheme.typography.bodyMedium)
                                if (!self) {
                                    TextButton(onClick = { onAction(GroupSessionEvent.Block(id, !snapshot.participation.isBlocked(id))) }) {
                                        Text(if (snapshot.participation.isBlocked(id)) "恢复本机收听" else "在本机静音此成员")
                                    }
                                    if (snapshot.host) TextButton(onClick = { removeMember = id },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移出房间") }
                                }
                            }
                        }
                        room.links.filter { !it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second)) }.forEach {
                            Text("${snapshot.names[it.lease.pair.first] ?: "骑士"} ↔ ${snapshot.names[it.lease.pair.second] ?: "骑士"}：语音待确认",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (snapshot.host && snapshot.removed.isNotEmpty()) MotoComPanel {
                            Text("成员管理", style = MaterialTheme.typography.titleMedium)
                            snapshot.removed.forEach { id ->
                                TextButton(onClick = { onAction(GroupSessionEvent.Unblock(id)) }) { Text("解除移除限制 · ${snapshot.names[id] ?: id.takeLast(8)}") }
                            }
                        }
                        MotoComPanel {
                            Text("本次房间音频", style = MaterialTheme.typography.titleMedium)
                            Text("仅影响当前房间；下次启动使用默认设置。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onAction(GroupSessionEvent.Mute(!snapshot.participation.selfMuted)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (snapshot.participation.selfMuted) "打开我的麦克风" else "静音我的麦克风")
                            }
                            Text(state.audioLabel, style = MaterialTheme.typography.bodyMedium)
                            AudioRouteSelection.entries.forEach { route ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                    selected = state.route == route, role = Role.RadioButton, onClick = { onRoute(route) }),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    RadioButton(selected = state.route == route, onClick = null)
                                    Text(when (route) {
                                        AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"
                                        AudioRouteSelection.EARPIECE -> "手机听筒 / 有线耳机"
                                        AudioRouteSelection.SPEAKER -> "手机扬声器"
                                    }, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = state.vox,
                                role = Role.Switch, onValueChange = onVox), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("声控 VOX", Modifier.weight(1f))
                                Switch(checked = state.vox, onCheckedChange = null)
                            }
                        }
                    }
                    MotoComPanel {
                        Text("房间退出", style = MaterialTheme.typography.titleMedium)
                        Text(if (snapshot?.host == true) "房主结束后，全队退出，房间码失效。" else if (room != null) "只离开当前房间，不会结束其他成员的对讲。" else "取消本次连接，不会影响其他房间。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { if (snapshot?.host == true) confirmEnd = true else onAction(GroupSessionEvent.Leave) },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_leave"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text(if (snapshot?.host == true) "结束全队对讲" else if (room != null) "离开房间" else "取消连接")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmEnd && snapshot?.host == true) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("结束全队对讲？") },
        text = { Text("所有成员都会离开，当前房间码将失效。") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onAction(GroupSessionEvent.Leave) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("结束房间") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续对讲") } })
    val removal = removeMember?.takeIf { id -> snapshot?.host == true && room?.members?.any { it.lease.deviceId == id } == true }
    if (removal != null) AlertDialog(onDismissRequest = { removeMember = null }, title = { Text("移出房间？") },
        text = { Text("${snapshot?.names?.get(removal) ?: "此成员"}将离开房间；解除限制前无法重新加入。") },
        confirmButton = { TextButton(onClick = { removeMember = null; onAction(GroupSessionEvent.Remove(removal)) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("移出成员") } },
        dismissButton = { TextButton(onClick = { removeMember = null }) { Text("保留成员") } })
}

@Preview(widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(widthDp = 840, heightDp = 700)
@Composable private fun GroupScreenPreview() { MotoComTheme { GroupScreen(GroupServiceState(), {}, {}, {}, {}, {}, {}, {}) } }
