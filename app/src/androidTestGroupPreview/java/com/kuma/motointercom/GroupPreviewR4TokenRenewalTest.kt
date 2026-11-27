package com.kuma.motointercom

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuma.motointercom.group.GroupAuthEndpoint
import com.kuma.motointercom.group.cloud.CloudAdmittedSession
import com.kuma.motointercom.group.cloud.CloudRtcToken
import com.kuma.motointercom.group.cloud.GroupCloudException
import com.kuma.motointercom.group.cloud.GroupCloudInviteCode
import com.kuma.motointercom.group.cloud.GroupCloudRuntime
import com.kuma.motointercom.group.cloud.GroupCloudStartRequest
import com.kuma.motointercom.group.cloud.GroupRtcMedia
import com.kuma.motointercom.group.cloud.GroupRtcMediaFactory
import com.kuma.motointercom.group.cloud.HttpGroupCloudClient
import com.kuma.motointercom.group.cloud.VolcGroupRtcMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class GroupPreviewR4TokenRenewalTest {
    @Test
    fun liveRtcRenewsBeforeExpiryAndStaysJoined() {
        val url = InstrumentationRegistry.getArguments().getString("rtcRenewalUrl").orEmpty()
        assumeTrue("Provide rtcRenewalUrl to run the live RTC renewal test", url.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val run = UUID.randomUUID().toString().take(8)
        val marker = "rtc_renewal_probe_$run"
        val joins = AtomicInteger()
        val expiry = AtomicLong()
        val appliedAt = AtomicLong()
        val renewedExpiry = AtomicLong()
        val voiceReady = AtomicBoolean()
        val mediaFactory = GroupRtcMediaFactory { context, onEvent, onRoute ->
            val media = VolcGroupRtcMedia(context, onEvent, onRoute)
            object : GroupRtcMedia by media {
                override fun join(token: CloudRtcToken, startMuted: Boolean, route: AudioRouteSelection) {
                    joins.incrementAndGet()
                    expiry.compareAndSet(0, token.expiresAtMs)
                    media.join(token, startMuted = true, route = route)
                }

                override fun updateToken(token: CloudRtcToken): Boolean {
                    val first = appliedAt.get() == 0L
                    if (first) Log.i("MotoComGroup", "$marker begin")
                    val accepted = media.updateToken(token)
                    if (first) {
                        val returnedAt = System.currentTimeMillis()
                        Log.i("MotoComGroup", "$marker end")
                        renewedExpiry.set(token.expiresAtMs)
                        appliedAt.set(returnedAt)
                    }
                    return accepted
                }
            }
        }

        HttpGroupCloudClient(url).use { host ->
            val room = host.createRoom("renew-host-$run", "host-$run", "RTC probe host", requireApproval = false)
            var peer: GroupCloudRuntime? = null
            try {
                val runtime = GroupCloudRuntime(
                    context = instrumentation.targetContext,
                    endpoint = GroupAuthEndpoint("renew-peer-$run", "peer-$run"),
                    nickname = "RTC probe peer",
                    request = GroupCloudStartRequest.Join(url, GroupCloudInviteCode(checkNotNull(room.snapshot.inviteCode))),
                    initialRoute = AudioRouteSelection.EARPIECE,
                    onSnapshot = { voiceReady.set(it.voiceReady) },
                    onAudioLabel = {},
                    onReleased = {},
                    rtcMediaFactory = mediaFactory
                )
                peer = runtime
                instrumentation.runOnMainSync { runtime.start(null) }
                val start = SystemClock.elapsedRealtime()
                var cursor = room.latestSeq
                var confirmed = false
                while (!confirmed || System.currentTimeMillis() <= expiry.get() + 2_000) {
                    cursor = host.pollEvents(room.sessionToken, room.roomId, cursor, waitMs = 5_000).latestSeq
                    val elapsed = SystemClock.elapsedRealtime() - start
                    assertTrue("RTC never joined or lost the room", elapsed < 40_000 || voiceReady.get())
                    assertTrue("RTC did not renew within the live test window", elapsed < 12 * 60_000)
                    if (!confirmed && appliedAt.get() != 0L) {
                        val command = "logcat -d -v raw --pid=${Process.myPid()} -s MotoComGroup:I"
                        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                            val lines = reader.readLines().map(String::trim)
                            val begin = lines.indexOf("$marker begin")
                            val end = lines.indexOf("$marker end")
                            assertTrue("Missing SDK call markers", begin >= 0 && end > begin)
                            val result = lines.subList(begin + 1, end).singleOrNull {
                                it.startsWith("group_cloud_rtc_token_update ")
                            }
                            assertEquals("First SDK updateToken result", "group_cloud_rtc_token_update code=0", result)
                        }
                        assertTrue("First token expired before updateToken", appliedAt.get() < expiry.get())
                        assertTrue("Replacement token did not extend expiry", renewedExpiry.get() > expiry.get())
                        confirmed = true
                    }
                }
                assertTrue("RTC not ready after the old token expired", voiceReady.get())
                assertEquals("RTC rejoined during renewal", 1, joins.get())
                instrumentation.addResults(Bundle().apply {
                    putLong("rtcRenewalBeforeExpiryMs", expiry.get() - appliedAt.get())
                    putLong("rtcRenewalExtensionMs", renewedExpiry.get() - expiry.get())
                    putInt("rtcJoinCount", joins.get())
                })
            } finally {
                try {
                    endProbeRoom(host, room)
                } finally {
                    instrumentation.runOnMainSync { peer?.close() }
                }
            }
        }
    }

    private fun endProbeRoom(host: HttpGroupCloudClient, room: CloudAdmittedSession) {
        repeat(3) { attempt ->
            val latest = host.pollEvents(room.sessionToken, room.roomId, Int.MAX_VALUE.toLong(), waitMs = 0)
            try {
                host.endRoom(room.sessionToken, room.roomId, checkNotNull(latest.snapshot).view.revision)
                return
            } catch (error: GroupCloudException) {
                if (error.code != "REVISION_CONFLICT" || attempt == 2) throw error
            }
        }
    }
}
