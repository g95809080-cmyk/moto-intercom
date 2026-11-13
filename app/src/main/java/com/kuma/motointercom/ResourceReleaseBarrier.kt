package com.kuma.motointercom

// A completion signal means native teardown has run, never merely been queued.
internal class ResourceReleaseBarrier {
    private val lock=Any()
    private var result: Boolean?=null
    private val waiters=mutableListOf<(Boolean)->Unit>()
    fun whenReleased(callback: (Boolean)->Unit) {
        val ready=synchronized(lock) { result.also { if(it==null) waiters.add(callback) } }
        if(ready!=null) callback(ready)
    }
    fun complete(success: Boolean) {
        val callbacks=synchronized(lock) {
            if(result!=null) return
            result=success; waiters.toList().also { waiters.clear() }
        }
        callbacks.forEach { runCatching { it(success) } }
    }
}
