package com.kuma.motointercom

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.kuma.motointercom.group.*

/** UI observes the service. Navigation never leaves a room and saved state never restores a code. */
internal class GroupActivity : ComponentActivity() {
    private var service: IntercomService? = null
    private var bound = false
    private var resumed = false
    private var pending = false
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
        setContent { MotoComTheme {
            GroupScreen(state.value, ::requestStart, { service?.groupAction(it) },
                { service?.groupRoute(it) }, { service?.groupVox(it) }, ::finish,
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { code -> (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("房间码", code)) })
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
    onCopy: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    val snapshot = state.snapshot
    val room = snapshot?.view
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text("返回主页") }
                Text("四人离线对讲", style = MaterialTheme.typography.headlineMedium)
                Text("附近连接，无需互联网 · 含房主最多四人", style = MaterialTheme.typography.bodyMedium)
                Text(state.message, Modifier.testTag("group_status"), style = MaterialTheme.typography.titleMedium)
                if (!state.busy && (snapshot == null || snapshot.phase == GroupPhase.IDLE)) {
                    Button(onClick = { onStart(null) }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_create")) { Text("创建房间") }
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位房间码") },
                        modifier = Modifier.fillMaxWidth().testTag("group_code_input"), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Button(onClick = { onStart(code); code = "" }, enabled = code.matches(Regex("[0-9]{6}")),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("group_join")) { Text("加入房间") }
                    TextButton(onClick = onSettings) { Text("权限设置") }
                } else {
                    snapshot?.matches?.forEach { match ->
                        OutlinedButton(onClick = { onAction(GroupSessionEvent.Select(match.descriptor.room)) }, Modifier.fillMaxWidth()) {
                            Text("${match.descriptor.nickname} · ${match.descriptor.room.instanceId.takeLast(8)}")
                        }
                    }
                    snapshot?.code?.let { joinCode ->
                        Text("房间码 ${joinCode.digits}", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { onCopy(joinCode.digits) }) { Text("复制房间码") }
                    }
                    if (room != null) {
                        Text(if (snapshot.voiceReady) "全队语音已就绪" else "语音连接确认中", Modifier.testTag("group_voice_status"))
                        room.members.filter { it.status != GroupMemberStatus.WAITING }.forEach { member ->
                            val id = member.lease.deviceId
                            val self = id == snapshot.local?.deviceId
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${snapshot.names[id] ?: "骑士"}${if (id == room.hostId) " · 房主" else ""}${if (self) " · 我" else ""}")
                                    Text(when { member.status == GroupMemberStatus.RESERVED -> "重连中，席位暂时保留"
                                        !member.audioAvailable -> "音频暂不可用"; else -> "已加入" })
                                    if (!self) {
                                        TextButton(onClick = { onAction(GroupSessionEvent.Block(id, !snapshot.participation.isBlocked(id))) }) {
                                            Text(if (snapshot.participation.isBlocked(id)) "恢复本机收听" else "在本机静音此成员")
                                        }
                                        if (snapshot.host) TextButton(onClick = { onAction(GroupSessionEvent.Remove(id)) }) { Text("移出房间") }
                                    }
                                }
                            }
                        }
                        room.links.filter { !it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second)) }.forEach {
                            Text("${snapshot.names[it.lease.pair.first] ?: "骑士"} ↔ ${snapshot.names[it.lease.pair.second] ?: "骑士"}：语音待确认")
                        }
                        snapshot.removed.forEach { id -> TextButton(onClick = { onAction(GroupSessionEvent.Unblock(id)) }) { Text("解除移除限制 · ${id.takeLast(8)}") } }
                        Button(onClick = { onAction(GroupSessionEvent.Mute(!snapshot.participation.selfMuted)) }, Modifier.fillMaxWidth()) {
                            Text(if (snapshot.participation.selfMuted) "打开我的麦克风" else "静音我的麦克风")
                        }
                        Text(state.audioLabel)
                        AudioRouteSelection.entries.forEach { route ->
                            TextButton(onClick = { onRoute(route) }, Modifier.fillMaxWidth()) {
                                Text((if (state.route == route) "✓ " else "") + when (route) {
                                    AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"; AudioRouteSelection.EARPIECE -> "手机听筒 / 有线耳机"; AudioRouteSelection.SPEAKER -> "手机扬声器"
                                })
                            }
                        }
                        TextButton(onClick = { onVox(!state.vox) }) { Text(if (state.vox) "声控已开启 · 点击关闭" else "声控已关闭 · 点击开启") }
                    }
                    OutlinedButton(onClick = { if (snapshot?.host == true) confirmEnd = true else onAction(GroupSessionEvent.Leave) },
                        Modifier.fillMaxWidth().testTag("group_leave")) { Text(if (snapshot?.host == true) "结束全队对讲" else "离开 / 取消") }
                }
            }
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("结束全队对讲？") },
        text = { Text("所有成员都会离开，当前房间码将失效。") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onAction(GroupSessionEvent.Leave) }) { Text("结束房间") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("继续对讲") } })
}

@Preview(widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(widthDp = 840, heightDp = 700)
@Composable private fun GroupScreenPreview() { MotoComTheme { GroupScreen(GroupServiceState(), {}, {}, {}, {}, {}, {}, {}) } }
