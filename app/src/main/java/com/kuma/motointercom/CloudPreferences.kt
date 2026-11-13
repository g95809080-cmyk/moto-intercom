package com.kuma.motointercom

import android.content.Context

internal class CloudPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gme_cloud_preferences", Context.MODE_PRIVATE)
    val consentAt: Long? get() = prefs.getLong("consent_at", 0).takeIf {
        it > 0 && prefs.getInt("consent_version", 0) == PRIVACY_VERSION
    }
    val enabled: Boolean get() = prefs.getBoolean("enabled", false) && consentAt != null
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(value) { check(prefs.edit().putBoolean("wifi_only", value).commit()) }

    fun accept(now: Long = System.currentTimeMillis()) {
        require(now > 0)
        check(prefs.edit().putLong("consent_at", consentAt ?: now).putInt("consent_version", PRIVACY_VERSION).putBoolean("enabled", true).commit())
    }
    fun disable() { check(prefs.edit().putBoolean("enabled", false).commit()) }

    companion object {
        const val PRIVACY_VERSION = 2
        val PRIVACY_TEXT = """
            云端对讲用于不同网络下的一对一通话，本地直连仍是默认方式。

            语音由腾讯云 GME 采集、传输和播放。摩声的 AWS 服务（悉尼区域）处理设备公钥、双方配对证明、在线状态、邀请、骑行状态及短期进房授权；设备私钥留在 Android Keystore，不可导出。我们不保存音频或语音内容，不记录进房票据。

            设备公钥与配对关系保留到解除配对；骑行元数据保留 7 天，邀请记录保留 24 小时。服务器按配置的保留期异步清理。

            邀请只有在对方接受后才启动语音。本地曾经成功连接、双方确认骑行后发生传输故障，可以自动转云端；主动结束不会触发降级。云端每次骑行上限 4 小时，可手动延长。

            应用使用录音权限进行对讲、电话状态权限识别来电并暂停采集与播放；电话状态仅在本机处理，不上传。标准耳机路由需要蓝牙权限。云端不应用本地 RNNoise 降噪。

            云端可以使用蜂窝数据并产生运营商流量费用。可在云端设置中选择仅 Wi-Fi；没有冷启动推送，仅在 App 前台或对讲前台服务运行时接收邀请。

            你可随时关闭云端功能；解除车友配对会同步撤销云端关系，离线时在重新联网后完成同步。当前仍为实验版本。
        """.trimIndent()
    }
}
