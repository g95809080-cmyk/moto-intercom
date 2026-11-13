package com.kuma.motointercom

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class CloudPairRecord(val peerDeviceId: String, val proof: PairingProof, val registered: Boolean)

internal interface CloudSessionStore {
    suspend fun pairs(): List<CloudPairRecord>
    suspend fun epoch(peer: String): Long
    suspend fun putProof(peer: String, proof: PairingProof, expectedEpoch: Long): Boolean
    suspend fun noteProposal(peer: String, pairId: String, expectedEpoch: Long): Boolean
    suspend fun markRegistered(peer: String, pairId: String)
    suspend fun forget(peer: String)
    suspend fun forgetIfCurrent(peer: String, pairId: String, expectedEpoch: Long): Boolean
    suspend fun revocations(): Set<String>
    suspend fun completeRevocation(pairId: String)
    suspend fun savedRide(): RideSession?
    suspend fun saveRide(ride: RideSession?)
    suspend fun clearRide(rideId: String)
    suspend fun queueEnd(rideId: String)
    suspend fun pendingEnds(): Set<String>
    suspend fun completeEnd(rideId: String)
    suspend fun startRequest(pairId: String): String
    suspend fun completeStart(pairId: String, requestId: String? = null)
    suspend fun cancelStarts()
    suspend fun cancelledStarts(): Map<String, String>
    suspend fun completeCancelledStart(requestId: String)
}

// Independent of the legacy Room schema. Contains public pairing proofs and ride
// metadata only; no GME tickets, websocket tokens, or private key material.
internal class RideSessionStore(context: Context) : CloudSessionStore {
    private val prefs = context.applicationContext.getSharedPreferences("gme_cloud_state", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val gson = Gson()
    private suspend fun <T> access(write: Boolean = false, action: (JsonObject) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = try { JsonParser.parseString(prefs.getString("state", "{}")).asJsonObject }
                catch (_: Exception) { throw ControlPlaneException(0, "LOCAL_STATE_UNAVAILABLE") }
            for (name in listOf("pairs", "epochs", "starts", "proposals", "cancelledStarts")) if (!root.has(name)) root.add(name, JsonObject())
            for (name in listOf("revocations", "ends", "closedRides")) if (!root.has(name)) root.add(name, JsonArray())
            val result = action(root)
            if (write) check(prefs.edit().putString("state", root.toString()).commit())
            result
        }
    }
    override suspend fun pairs(): List<CloudPairRecord> = access { root ->
        root.getAsJsonObject("pairs").entrySet().map { (peer, value) ->
            val r = value.asJsonObject
            CloudPairRecord(peer, PairingProof.parse(r["proof"].toString()), r["registered"].asBoolean)
        }
    }
    override suspend fun epoch(peer: String): Long = access { it.getAsJsonObject("epochs")[peer]?.asLong ?: 0 }
    override suspend fun putProof(peer: String, proof: PairingProof, expectedEpoch: Long): Boolean = access(true) { root ->
        val epoch = root.getAsJsonObject("epochs")[peer]?.asLong ?: 0
        if (epoch != expectedEpoch || !proof.verified()) return@access false
        root.getAsJsonObject("pairs").add(peer, JsonObject().apply { add("proof", proof.json()); addProperty("registered", false) })
        true
    }
    override suspend fun markRegistered(peer: String, pairId: String) { access(true) { root ->
        val r = root.getAsJsonObject("pairs")[peer]?.asJsonObject
        if (r?.getAsJsonObject("proof")?.getAsJsonObject("transcript")?.get("pairId")?.asString == pairId) r.addProperty("registered", true)
    } }
    override suspend fun noteProposal(peer: String, pairId: String, expectedEpoch: Long): Boolean = access(true) { root ->
        if ((root.getAsJsonObject("epochs")[peer]?.asLong ?: 0) != expectedEpoch) return@access false
        val proposals = root.getAsJsonObject("proposals")
        val list = proposals[peer]?.asJsonArray ?: JsonArray().also { proposals.add(peer,it) }
        addUnique(list,pairId); true
    }
    override suspend fun forget(peer: String) { access(true) { root -> forgetRecord(root,peer) } }
    override suspend fun forgetIfCurrent(peer: String, pairId: String, expectedEpoch: Long): Boolean = access(true) { root ->
        val current = root.getAsJsonObject("pairs")[peer]?.asJsonObject?.getAsJsonObject("proof")?.getAsJsonObject("transcript")?.get("pairId")?.asString
        if (current != pairId || (root.getAsJsonObject("epochs")[peer]?.asLong ?: 0) != expectedEpoch) false
        else { forgetRecord(root,peer); true }
    }
    private fun forgetRecord(root: JsonObject,peer: String) {
        val pairs = root.getAsJsonObject("pairs")
        val ids = mutableSetOf<String>()
        pairs.remove(peer)?.asJsonObject?.getAsJsonObject("proof")?.getAsJsonObject("transcript")?.get("pairId")?.asString?.let {
            ids.add(it)
            addUnique(root.getAsJsonArray("revocations"), it)
        }
        root.getAsJsonObject("proposals").remove(peer)?.asJsonArray?.forEach {
            ids.add(it.asString); addUnique(root.getAsJsonArray("revocations"),it.asString)
        }
        val starts = root.getAsJsonObject("starts")
        ids.forEach { pair -> starts.remove(pair)?.let { root.getAsJsonObject("cancelledStarts").addProperty(it.asString,pair) } }
        root["ride"]?.takeUnless { it.isJsonNull }?.asJsonObject?.let { ride ->
            if (ride.getAsJsonArray("members").any { it.asString == peer }) {
                addUnique(root.getAsJsonArray("ends"),ride["rideId"].asString); root.remove("ride")
            }
        }
        val epochs = root.getAsJsonObject("epochs"); epochs.addProperty(peer, (epochs[peer]?.asLong ?: 0) + 1)
    }
    override suspend fun revocations(): Set<String> = access { strings(it.getAsJsonArray("revocations")) }
    override suspend fun completeRevocation(pairId: String) { access(true) { remove(it.getAsJsonArray("revocations"), pairId) } }
    override suspend fun savedRide(): RideSession? = access {
        it["ride"]?.takeUnless { r -> r.isJsonNull }?.let { r -> gson.fromJson(r, RideSession::class.java) }
    }
    override suspend fun saveRide(ride: RideSession?) { access(true) { root ->
        if (ride == null) root.remove("ride")
        else if (ride.state != RideState.ENDED && ride.rideId !in strings(root.getAsJsonArray("ends")) &&
            ride.rideId !in strings(root.getAsJsonArray("closedRides")) &&
            root.getAsJsonObject("pairs").entrySet().any { (_,p) ->
                p.asJsonObject["registered"].asBoolean && p.asJsonObject.getAsJsonObject("proof")
                    .getAsJsonObject("transcript")["pairId"].asString == ride.pairId
            }) root.add("ride", gson.toJsonTree(ride))
        Unit
    } }
    override suspend fun queueEnd(rideId: String) { access(true) { root ->
        addUnique(root.getAsJsonArray("ends"), rideId)
        val cached = root["ride"]?.takeUnless { it.isJsonNull }?.asJsonObject
        if (cached?.get("rideId")?.asString == rideId) {
            val pair = cached["pairId"].asString
            root.getAsJsonObject("starts").remove(pair)?.let { root.getAsJsonObject("cancelledStarts").addProperty(it.asString,pair) }
            root.remove("ride")
        }
    } }
    override suspend fun clearRide(rideId: String) { access(true) {
        if(it["ride"]?.takeUnless { r -> r.isJsonNull }?.asJsonObject?.get("rideId")?.asString==rideId) it.remove("ride")
        Unit
    } }
    override suspend fun pendingEnds(): Set<String> = access { strings(it.getAsJsonArray("ends")) }
    override suspend fun completeEnd(rideId: String) { access(true) {
        remove(it.getAsJsonArray("ends"), rideId)
        val closed = it.getAsJsonArray("closedRides"); addUnique(closed,rideId)
        while (closed.size() > 128) closed.remove(0)
    } }
    override suspend fun startRequest(pairId: String): String = access(true) { root ->
        if (root.getAsJsonObject("cancelledStarts").size() >= 32) throw ControlPlaneException(0,"PENDING_COMMANDS")
        val starts = root.getAsJsonObject("starts")
        starts[pairId]?.asString ?: UUID.randomUUID().toString().also { starts.addProperty(pairId, it) }
    }
    override suspend fun completeStart(pairId: String, requestId: String?) { access(true) {
        val starts = it.getAsJsonObject("starts")
        if (requestId == null || starts[pairId]?.asString == requestId) starts.remove(pairId)
    } }
    override suspend fun cancelStarts() { access(true) { root ->
        root.getAsJsonObject("starts").entrySet().forEach { (pair,id) -> root.getAsJsonObject("cancelledStarts").addProperty(id.asString,pair) }
        root.add("starts",JsonObject())
    } }
    override suspend fun cancelledStarts(): Map<String,String> = access {
        it.getAsJsonObject("cancelledStarts").entrySet().associate { (id,pair) -> id to pair.asString }
    }
    override suspend fun completeCancelledStart(requestId: String) { access(true) { it.getAsJsonObject("cancelledStarts").remove(requestId); Unit } }
    private fun strings(array: JsonArray): Set<String> = array.map { it.asString }.toSet()
    private fun addUnique(array: JsonArray, value: String) { if (array.none { it.asString == value }) array.add(value) }
    private fun remove(array: JsonArray, value: String) { for (i in array.size() - 1 downTo 0) if (array[i].asString == value) array.remove(i) }
}
