package com.kuma.motointercom

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast

// Notification acceptance returns to a visible Activity before microphone FGS
// promotion. Service-owned nonces reject stale or externally forged actions.
internal class CloudRequestUi(
    private val activity: Activity,
    private val preferences: CloudPreferences,
    private val serviceAvailable: () -> Boolean,
    private val respond: (String,String,String,Boolean) -> Boolean,
    private val micAllowed: () -> Boolean,
    private val requestMic: () -> Unit,
    private val settingsChanged: () -> Unit
) {
    private data class Action(val kind: String,val id: String,val nonce: String,val accepted: Boolean)
    private var visible=false
    private var snapshotReady=false
    private var invite: CloudInvitePrompt?=null
    private var pairing: CloudPairingPrompt?=null
    private var pending: Action?=null
    private var permissionPending=false
    private var dialog: AlertDialog?=null
    private var dialogKey: String?=null

    fun invitation(prompt: CloudInvitePrompt?) {
        invite=prompt
        if (prompt==null && dialogKey?.startsWith("invite/")==true) dismiss()
        pump()
    }
    fun pairing(prompt: CloudPairingPrompt?) {
        pairing=prompt
        if (prompt==null && dialogKey?.startsWith("pair/")==true) dismiss()
        pump()
    }
    fun intent(intent: Intent?) {
        if (intent?.action != IntercomService.ACTION_CLOUD_UI) return
        val kind=intent.getStringExtra(IntercomService.EXTRA_CLOUD_KIND).orEmpty()
        val id=intent.getStringExtra(IntercomService.EXTRA_CLOUD_ENTITY).orEmpty()
        val nonce=intent.getStringExtra(IntercomService.EXTRA_CLOUD_ACTION_NONCE).orEmpty()
        val accept=intent.getBooleanExtra(IntercomService.EXTRA_CLOUD_ACCEPTED,false)
        if (kind !in setOf("pair","invite") || !cloudId(id) || !nonce.matches(Regex("[A-Za-z0-9_-]{16,128}"))) return
        if (intent.data?.toString() != "motointercom://cloud/$kind/$id/$nonce/$accept") return
        pending=Action(kind,id,nonce,accept); pump()
    }
    fun resume() { visible=true; pump() }
    fun connected() { snapshotReady=true; pump() }
    fun stop() { visible=false; snapshotReady=false; dismiss() }
    fun permissionResult(granted: Boolean) {
        permissionPending=false
        if (!granted) {
            pending?.let { respond(it.kind,it.id,it.nonce,false) }; pending=null
        }
        pump()
    }

    private fun pump() {
        if (!visible || !snapshotReady || !serviceAvailable() || permissionPending) return
        pending?.let { action ->
            val known=if(action.kind=="invite") invite?.let { it.invitation.inviteId==action.id && it.actionNonce==action.nonce }==true
                else pairing?.let { it.pairId==action.id && it.actionNonce==action.nonce }==true
            if (!known) { pending=null } // Fresh service snapshots present a current prompt instead.
            else if (!action.accepted) { pending=null; respond(action.kind,action.id,action.nonce,false); dismiss(); return }
            else if (action.kind=="invite" && !micAllowed()) { permissionPending=true; requestMic(); return }
            else if (preferences.enabled) {
                pending=null; dismiss()
                if (!respond(action.kind,action.id,action.nonce,true)) toast("请求已失效")
                return
            } else pending=null // A pairing request still needs the complete first-use privacy notice.
        }
        val p=pairing
        if (p!=null && p.expiresAt>System.currentTimeMillis()) {
            show("pair",p.pairId,p.actionNonce,p.peerName,p.expiresAt); return
        }
        invite?.takeIf { it.expiresAtLocal>System.currentTimeMillis() }?.let {
            show("invite",it.invitation.inviteId,it.actionNonce,it.peerName,it.expiresAtLocal)
        }
    }

    private fun show(kind: String,id: String,nonce: String,name: String,expires: Long) {
        val key="$kind/$id/$nonce"
        if (dialog?.isShowing==true && dialogKey==key) return
        dismiss(); dialogKey=key
        val needsConsent=!preferences.enabled
        dialog=AlertDialog.Builder(activity)
            .setTitle(if(kind=="pair") "与 $name 重新配对云端" else "$name 邀请云端对讲")
            .setMessage(if(needsConsent) CloudPreferences.PRIVACY_TEXT else if(kind=="pair")
                "双方将验证设备身份并建立云端配对，本地对讲继续保持。" else "接受后自动进入本次骑行的云端语音房间。邀请有效期为 30 秒。")
            .setPositiveButton(if(needsConsent) "同意并接受" else "接受") { _,_ ->
                if(needsConsent) {
                    if (runCatching { preferences.accept() }.isFailure) { toast("无法保存隐私设置，请重试"); return@setPositiveButton }
                    settingsChanged()
                }
                pending=Action(kind,id,nonce,true); pump()
            }
            .setNegativeButton("拒绝") { _,_ -> pending=Action(kind,id,nonce,false); pump() }
            .create().also { d ->
                d.setOnCancelListener { respond(kind,id,nonce,false) }
                d.setOnDismissListener { if (dialog===d) { dialog=null; dialogKey=null } }
                d.show()
                activity.window.decorView.postDelayed({ if(dialog===d) dismiss() },(expires-System.currentTimeMillis()).coerceAtLeast(1))
            }
    }
    private fun dismiss() { val old=dialog; dialog=null; dialogKey=null; old?.dismiss() }
    private fun toast(text: String) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show() }
}
