package com.kuma.motointercom

import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class LocalShadowProbeTest {
    private val a="11111111-1111-4111-8111-111111111111";private val b="22222222-2222-4222-8222-222222222222"
    private inner class Adapter(val transport: Transport,val observed: (FreshDiscoveryObservation)->Unit,val failed: (ConnectionAttempt?)->Unit) : ShadowDiscoveryAdapter {
        override val source=FreshDiscoverySource(transport)
        val connections=mutableListOf<ConnectionAttempt>();var refreshes=0;var closed=false;var delayClose=false
        var closeCallback: ((Boolean)->Unit)?=null
        override fun start()=true
        override fun connect(attempt: ConnectionAttempt): Boolean {connections.add(attempt);return true}
        override fun refresh(){refreshes++}
        override fun closeWhenReleased(callback: (Boolean)->Unit) {closed=true;source.close();if(delayClose)closeCallback=callback else callback(true)}
        fun emit() {
            val candidate=DiscoveryCandidate(transport,"endpoint-${transport.name}","192.168.49.2",8888,
                DiscoveryIdentityClaim(b,RuntimeSessionId("22222222-3333-4333-8333-222222222222"),"b","phone",2))
            val kind=if(transport==Transport.LAN)DiscoveryObservationKind.LAN_UDP else DiscoveryObservationKind.WIFI_DIRECT_TXT
            val receipt=source.capture(kind,SystemClock.elapsedRealtime())!!
            observed(source.accept(receipt,candidate){Unit}!!.second)
        }
    }
    @Test fun wifiAppearingAfterCellularStartCreatesLanAndStopsNewP2pWork() {
        var state=ShadowProbeNetworks(null,true);val made=mutableListOf<Adapter>()
        val factory=ShadowAdapterFactory {t,_,_,o,f->Adapter(t,o,f).also {made.add(it)}}
        val probe=LocalShadowProbe(ApplicationProvider.getApplicationContext(),a,b,{error("No socket")},{},factory=factory,networks={state})
        try {
            probe.start();assertEquals(listOf(Transport.WIFI_DIRECT),made.map {it.transport})
            state=ShadowProbeNetworks("wifi-lan",false);probe.refreshNetworks();shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(Transport.WIFI_DIRECT,Transport.LAN),made.map {it.transport})
            assertTrue(made.first().closed)
        } finally {probe.close()}
    }
    @Test fun changedLanAddressWaitsForTheExactOldAdapterToClose() {
        var state=ShadowProbeNetworks("lan-one",false);val made=mutableListOf<Adapter>()
        val factory=ShadowAdapterFactory {t,_,_,o,f->Adapter(t,o,f).also {made.add(it)}}
        val probe=LocalShadowProbe(ApplicationProvider.getApplicationContext(),a,b,{},{},factory=factory,networks={state})
        try {
            probe.start();val old=made.single();old.delayClose=true
            state=ShadowProbeNetworks("lan-two",false);probe.refreshNetworks();assertEquals(1,made.size)
            old.closeCallback!!(true);shadowOf(Looper.getMainLooper()).idle();assertEquals(2,made.size)
        } finally {made.forEach {it.delayClose=false};probe.close()}
    }
    @Test fun failedLanYieldsToAStillFreshP2pReceiptAndBackoffPreventsImmediateStarvation() {
        val made=mutableListOf<Adapter>();val factory=ShadowAdapterFactory {t,_,_,o,f->Adapter(t,o,f).also {made.add(it)}}
        val probe=LocalShadowProbe(ApplicationProvider.getApplicationContext(),a,b,{},{},factory=factory,networks={ShadowProbeNetworks("lan",true)})
        try {
            probe.start();val lan=made.first {it.transport==Transport.LAN};val wifi=made.first {it.transport==Transport.WIFI_DIRECT}
            lan.emit();wifi.emit();assertEquals(1,lan.connections.size);assertTrue(wifi.connections.isEmpty())
            lan.failed(lan.connections.single());assertEquals(1,wifi.connections.size)
            wifi.failed(wifi.connections.single());lan.emit();assertEquals(1,lan.connections.size)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100));lan.emit();assertEquals(2,lan.connections.size)
            assertTrue(wifi.refreshes>0)
        } finally {probe.close()}
    }
    @Test fun lateFailureForAnOlderAttemptCannotRevokeTheSelectedConnection() {
        lateinit var adapter: Adapter;lateinit var accept: (SignalingSessionV2,PendingSocketLease)->Unit
        var runtime: RuntimeSessionId?=null;var losses=0;var selected=0
        val factory=ShadowAdapterFactory {t,r,s,o,f->runtime=r;accept=s;Adapter(t,o,f).also {adapter=it}}
        val probe=LocalShadowProbe(ApplicationProvider.getApplicationContext(),a,b,{selected++},{losses++},
            factory=factory,networks={ShadowProbeNetworks("lan",false)})
        val pool=Executors.newFixedThreadPool(2)
        try {
            probe.start();adapter.emit();val obsolete=adapter.connections.single();adapter.failed(obsolete)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100));adapter.emit()
            val current=adapter.connections.last();val clock=MonotonicClock {MonotonicTimestamp(SystemClock.elapsedRealtime())}
            ServerSocket(0).use {server->Socket("127.0.0.1",server.localPort).use {client->server.accept().use {remote->
                val lease=PendingSocketLease(client,current,current.deadlineElapsedRealtimeMs,clock)
                lease.armAdmissionDeadline()
                val left=pool.submit<SignalingSessionV2> {SignalingSessionV2.establish(client,Transport.LAN,PhysicalSocketRole.OPENER,
                    SystemClock.elapsedRealtime(),a,requireNotNull(runtime),"a","test",current,monotonicClock=clock,pendingSocketLease=lease,
                    localCapabilities=setOf(CLOUD_SHADOW_CAPABILITY))}
                val right=pool.submit<SignalingSessionV2> {SignalingSessionV2.establish(remote,Transport.LAN,PhysicalSocketRole.ACCEPTOR,
                    SystemClock.elapsedRealtime(),b,current.targetLock.expectedRemoteSessionId!!,"b","test",null,monotonicClock=clock,
                    localCapabilities=setOf(CLOUD_SHADOW_CAPABILITY))}
                val session=left.get(3,TimeUnit.SECONDS);val peer=right.get(3,TimeUnit.SECONDS)
                try {
                    assertTrue(lease.prepareAdmission({true}));accept(session,lease);assertEquals(1,selected)
                    adapter.failed(obsolete);assertEquals(0,losses);assertFalse(session.isClosed)
                    adapter.failed(current);assertEquals(1,losses)
                } finally {session.close();peer.close();lease.close()}
            }}}
        } finally {probe.close();pool.shutdownNow()}
    }
    @Test fun failedAdapterReleaseBlocksRecreationAndIsIncludedInTheFinalFence() {
        var state=ShadowProbeNetworks("one",false);val made=mutableListOf<Adapter>()
        val factory=ShadowAdapterFactory {t,_,_,o,f->Adapter(t,o,f).also {made.add(it)}}
        val probe=LocalShadowProbe(ApplicationProvider.getApplicationContext(),a,b,{},{},factory=factory,networks={state})
        probe.start();made.single().delayClose=true;state=ShadowProbeNetworks("two",false);probe.refreshNetworks()
        made.single().closeCallback!!(false);shadowOf(Looper.getMainLooper()).idle();probe.refreshNetworks()
        assertEquals(1,made.size)
        val results=mutableListOf<Boolean>();probe.closeWhenReleased {results.add(it)}
        assertEquals(listOf(false),results)
    }
}
