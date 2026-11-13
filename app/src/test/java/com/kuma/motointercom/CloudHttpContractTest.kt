package com.kuma.motointercom

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudHttpContractTest {
    private class Server : Closeable {
        private val backend = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it,"backend/gme-control-plane") }.first { File(it,"test/contract-server.mjs").isFile }
        private val process = ProcessBuilder("node","test/contract-server.mjs").directory(backend).start()
        val base: String
        init {
            val reader=Executors.newSingleThreadExecutor()
            try {
                val line=reader.submit<String> { process.inputStream.bufferedReader().readLine() }.get(10,TimeUnit.SECONDS)
                val port=JsonParser.parseString(line).asJsonObject["port"].asInt
                check(port in 1..65535); base="http://127.0.0.1:$port"
            } finally { reader.shutdownNow() }
        }
        override fun close() { process.destroy(); if(!process.waitFor(5,TimeUnit.SECONDS)) process.destroyForcibly() }
    }
    private fun transport(base: String,id: String,signer: DeviceSigner,http: OkHttpClient) =
        ControlPlaneClient(base,id,signer,http,allowLoopbackForTests=true)
    private suspend fun until(condition: ()->Boolean) { withTimeout(10_000) { while(!condition()) delay(20) } }
    private fun ticketCount(server: Server,http: OkHttpClient): Int = http.newCall(Request.Builder().url(server.base+"/__fixture/stats").build())
        .execute().use { JsonParser.parseString(it.body.string()).asJsonObject["ticketCalls"].asInt }

    @Test fun twoAndroidClientsSignActualNodeCommandsAndReceiveInvitesOverWebSocket() = runBlocking {
        Server().use { server ->
            val http=cloudHttpClient()
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val signerA=TestDeviceSigner(); val signerB=TestDeviceSigner()
            val signedA=transport(server.base,"a",signerA,http); val signedB=transport(server.base,"b",signerB,http)
            val apiA=HttpCloudControlPlane(signedA); val apiB=HttpCloudControlPlane(signedB)
            val proof=testPair(signerA,signerB)
            apiA.registerDevice(); apiB.registerDevice(); apiA.registerPair(proof)
            val tickets=java.util.Collections.synchronizedList(mutableListOf<GmeTicket>())
            val stops=AtomicInteger(); val consent=System.currentTimeMillis()-1
            fun co(api: HttpCloudControlPlane, signed: ControlPlaneClient, peer: String) = DefaultCloudSessionCoordinator(api,
                CloudControlSocket(server.base.replace("http://","ws://"),http,true),
                MemoryCloudStore(listOf(CloudPairRecord(peer,proof,true))),{consent},{true},{true},{false},{it},
                { approved -> tickets.add(runBlocking { GmeTicketClient(signed).fetch(approved) }); true },
                { stops.incrementAndGet() },serverNow=api::serverNow,dispatcher=dispatcher)
            val a=co(apiA,signedA,"b"); var b=co(apiB,signedB,"a")
            try {
                a.start(); b.start()
                withTimeout(10_000) { a.online.first { it }; b.online.first { it } }
                a.invite("pair_test")
                until { b.incoming.value!=null }
                assertEquals(0,ticketCount(server,http))
                assertTrue(tickets.isEmpty())
                b.respond(b.incoming.value!!.invitation.inviteId,true)
                until { tickets.size==2 }
                assertEquals(tickets[0].roomId,tickets[1].roomId)
                assertNotEquals(tickets[0].openId,tickets[1].openId)
                val rideId=a.ride.value!!.rideId
                b.close()
                b=co(apiB,signedB,"a"); b.start()
                until { b.state.value is CloudIntercomState.ResumeAvailable }
                assertEquals(2,tickets.size)
                b.resumeManually(rideId)
                until { tickets.size==3 }
                b.end(rideId)
                until { a.ride.value==null }
                assertTrue(stops.get()>=2)
                assertEquals(3,ticketCount(server,http))
            } finally {
                a.close(); b.close(); dispatcher.close()
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun twoLocalSuccessesShareARideAndFallbackWaitsForNativeOwnershipRelease() = runBlocking {
        Server().use { server ->
            val http=cloudHttpClient(); val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val keyA=TestDeviceSigner(); val keyB=TestDeviceSigner()
            val signedA=transport(server.base,"a",keyA,http); val signedB=transport(server.base,"b",keyB,http)
            val apiA=HttpCloudControlPlane(signedA); val apiB=HttpCloudControlPlane(signedB)
            val proof=testPair(keyA,keyB); apiA.registerDevice(); apiB.registerDevice(); apiA.registerPair(proof)
            val tickets=java.util.Collections.synchronizedList(mutableListOf<GmeTicket>())
            val releasing=AtomicInteger(); val release=CompletableDeferred<Unit>()
            fun co(api: HttpCloudControlPlane,signed: ControlPlaneClient,peer: String)=DefaultCloudSessionCoordinator(api,
                CloudControlSocket(server.base.replace("http://","ws://"),http,true),MemoryCloudStore(listOf(CloudPairRecord(peer,proof,true))),
                {System.currentTimeMillis()-1},{true},{true},{true},{it},
                {approved ->
                    releasing.incrementAndGet(); release.await()
                    tickets.add(GmeTicketClient(signed).fetch(approved)); true
                },{},serverNow=api::serverNow,dispatcher=dispatcher,canReplaceLocal={it.reason==CloudEntryReason.LOCAL_FAILURE})
            val a=co(apiA,signedA,"b"); val b=co(apiB,signedB,"a")
            try {
                a.start(); b.start(); withTimeout(10_000) { a.online.first {it}; b.online.first {it} }
                a.attachEstablishedLocal("b","lease_a") {true}
                b.attachEstablishedLocal("a","lease_b") {true}
                a.refresh(); b.refresh()
                val ride=a.ride.value!!
                assertEquals(ride.rideId,b.ride.value!!.rideId)
                assertEquals(RideState.LOCAL_ACTIVE,ride.state); assertEquals(0,ticketCount(server,http))
                val loss=async { a.establishedLocalLost("lease_a") }
                until { releasing.get()==2 }
                assertEquals(0,ticketCount(server,http)); assertTrue(tickets.isEmpty())
                release.complete(Unit); loss.await(); until {tickets.size==2}
                assertTrue(tickets.all {it.roomId==ride.roomId && it.rideId==ride.rideId})
                a.cancelCurrent(); until {b.ride.value==null}
            } finally {
                a.close(); b.close(); dispatcher.close()
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun policyChangeWhileSigningPreventsRequestEnqueue() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1); val wifi=AtomicBoolean(true)
        val requests=AtomicInteger(); val delegate=TestDeviceSigner()
        val signer=object : DeviceSigner {
            override fun publicKeyBase64()=delegate.publicKeyBase64()
            override fun sign(bytes: ByteArray): String { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); return delegate.sign(bytes) }
        }
        val http=cloudHttpClient().newBuilder().addInterceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) }.build()
        try {
            val client=ControlPlaneClient("https://api.example.test","a",signer,http,wifiAllowed=wifi::get)
            val result=async(Dispatchers.Default) { runCatching { client.post("/v1/ws-token","{}") }.exceptionOrNull() }
            assertTrue(entered.await(5,TimeUnit.SECONDS)); wifi.set(false); release.countDown()
            assertEquals("WIFI_REQUIRED",(result.await() as ControlPlaneException).code)
            assertEquals(0,requests.get())
        } finally { release.countDown(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun stableLocalSwitchCommitsBothClientsAndRecreatedClientRequiresExplicitResumeInTheSameRoom()=runBlocking {
        Server().use {server ->
            val http=cloudHttpClient();val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val ak=TestDeviceSigner();val bk=TestDeviceSigner()
            val signedA=transport(server.base,"a",ak,http);val signedB=transport(server.base,"b",bk,http)
            val a=HttpCloudControlPlane(signedA);val b=HttpCloudControlPlane(signedB);val proof=testPair(ak,bk)
            a.registerDevice();b.registerDevice();a.registerPair(proof)
            val ride=a.startRide("pair_test",java.util.UUID.randomUUID().toString())
            a.localEstablished(ride.rideId);b.localEstablished(ride.rideId);a.fallback(ride.rideId)
            a.cloudStatus(ride.rideId,true);b.cloudStatus(ride.rideId,true)
            var switching=a.ride(ride.rideId);val probe="a".repeat(64)
            repeat(11) {index ->
                if(index>0)delay(1_050)
                a.localProbe(ride.rideId,probe,true);switching=b.localProbe(ride.rideId,probe,true)
            }
            assertEquals(RideState.SWITCHING_LOCAL,switching.state)
            val switch=switching.localSwitch!!
            a.localSwitch(ride.rideId,switch.switchId,probe,"PREPARED")
            b.localSwitch(ride.rideId,switch.switchId,probe,"PREPARED")
            a.localSwitch(ride.rideId,switch.switchId,probe,"COMPLETE")
            val local=b.localSwitch(ride.rideId,switch.switchId,probe,"COMPLETE")
            assertEquals(RideState.LOCAL_ACTIVE,local.state);assertNull(local.cloudStartedAt)
            assertEquals(ride.roomId,local.roomId);assertEquals(0,ticketCount(server,http))
            val tickets=mutableListOf<GmeTicket>()
            val co=DefaultCloudSessionCoordinator(a,CloudControlSocket(server.base.replace("http://","ws://"),http,true),
                MemoryCloudStore(listOf(CloudPairRecord("b",proof,true))),{System.currentTimeMillis()-1},{true},{true},{false},{it},
                {approved ->tickets.add(GmeTicketClient(signedA).fetch(approved));true},{},serverNow=a::serverNow,dispatcher=dispatcher)
            try {
                co.start();until {co.state.value is CloudIntercomState.ResumeAvailable}
                assertTrue(tickets.isEmpty());co.refresh();assertTrue(tickets.isEmpty())
                co.resumeManually(ride.rideId)
                assertEquals(1,tickets.size);assertEquals(ride.roomId,tickets.single().roomId)
                assertEquals(local.cloudUsedMs,a.ride(ride.rideId).cloudUsedMs)
            } finally {co.close();dispatcher.close();http.dispatcher.cancelAll();http.connectionPool.evictAll();http.dispatcher.executorService.shutdown()}
        }
    }
}
