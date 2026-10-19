package com.kuma.motointercom

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real AMS probe, emulator only. The external observer retains crash/ANR evidence after runner death.
 * The ownership token selects the production busy branch; it does not simulate AMS or its timer.
 * A surviving runner alone is never a system-level PASS. */
@RunWith(AndroidJUnit4::class)
class C04AmsProbeTest {
    @Test fun ownerBusyWithoutBinding() = earlyReturnCase("busy-unbound", busy = true, binding = false)
    @Test fun ownerBusyWithAutoCreateBinding() = earlyReturnCase("busy-bound", busy = true, binding = true)
    @Test fun permissionMissingWithoutBinding() = earlyReturnCase("permission-unbound", busy = false, binding = false)
    @Test fun permissionMissingWithAutoCreateBinding() = earlyReturnCase("permission-bound", busy = false, binding = true)

    @Test fun legalForegroundStartPositiveControl() {
        val context = preconditions(permissionMissing = false)
        check(!LegacyRuntimeOwnership.hasOwner()) { "Existing runtime owner" }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            marker("BEGIN scenario=positive")
            scenario.onActivity { activity ->
                marker("CALL_BEGIN")
                activity.startForegroundService(startIntent(activity))
                marker("API_RETURN")
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            marker("MAIN_IDLE")
            val deadline = SystemClock.uptimeMillis() + 10_000L
            var promoted = false
            while (!promoted && SystemClock.uptimeMillis() < deadline) {
                promoted = foreground(context)
                if (!promoted) SystemClock.sleep(100)
            }
            assertTrue("Positive control did not observe successful foreground promotion", promoted)
            marker("POSITIVE_FOREGROUND")
            SystemClock.sleep(10_000)
            marker("WINDOW_COMPLETE scenario=positive")
        } finally {
            marker("CLEANUP_BEGIN")
            context.stopService(Intent(context, IntercomService::class.java))
            marker("AFTER_STOP")
            scenario.close()
            SystemClock.sleep(5_000)
            marker("END")
        }
    }

    private fun earlyReturnCase(case: String, busy: Boolean, binding: Boolean) {
        val context = preconditions(permissionMissing = !busy)
        check(!LegacyRuntimeOwnership.hasOwner()) { "Existing runtime owner" }
        val token = if (busy) checkNotNull(LegacyRuntimeOwnership.acquire()) else null
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { marker("BOUND") }
            override fun onServiceDisconnected(name: ComponentName?) { marker("SERVICE_DISCONNECTED") }
        }
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        try {
            val windowMs = InstrumentationRegistry.getArguments().getString("observationMs")?.toLong() ?: 85_000L
            require(windowMs in 1_000L..300_000L)
            marker("BEGIN scenario=$case owner=${LegacyRuntimeOwnership.hasOwner()} permissionMissing=${!busy} windowMs=$windowMs")
            scenario.onActivity { activity ->
                val intent = startIntent(activity)
                marker("CALL_BEGIN")
                try {
                    activity.startForegroundService(intent)
                    marker("API_RETURN")
                    if (binding) bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    marker("BIND_RETURN requested=$binding accepted=$bound")
                } catch (error: RuntimeException) {
                    Log.e("C04AmsProbe", "C04 START_EXCEPTION", error)
                    throw error
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // After a proven branch delivery, this is a causal upper bound for dispatch/arm,
            // never an exact timer.start timestamp. The observer checks the ordering in logcat.
            marker("MAIN_IDLE owner=${LegacyRuntimeOwnership.hasOwner()}")
            if (binding) assertTrue("Explicit AUTO_CREATE binding was rejected", bound)
            val until = SystemClock.uptimeMillis() + windowMs
            var previouslyForeground: Boolean? = null
            while (SystemClock.uptimeMillis() < until) {
                val current = foreground(context)
                if (current != previouslyForeground) {
                    marker("FOREGROUND_SAMPLE value=$current owner=${LegacyRuntimeOwnership.hasOwner()}")
                    previouslyForeground = current
                }
                SystemClock.sleep(100)
            }
            marker("WINDOW_COMPLETE scenario=$case owner=${LegacyRuntimeOwnership.hasOwner()}")
        } finally {
            marker("CLEANUP_BEGIN")
            if (bound) {
                scenario.onActivity { it.unbindService(connection) }
                marker("AFTER_UNBIND")
            }
            context.stopService(Intent(context, IntercomService::class.java))
            marker("AFTER_STOP")
            token?.let(LegacyRuntimeOwnership::release)
            scenario.close()
            SystemClock.sleep(5_000)
            marker("END")
        }
    }

    private fun startIntent(context: Context) = IntercomService.startIntent(context,
        riderName = "C04-system-probe", preferredAudioRoute = AudioRouteSelection.EARPIECE)

    @Suppress("DEPRECATION")
    private fun foreground(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any {
            it.service.className == IntercomService::class.java.name && it.foreground
        }

    private fun preconditions(permissionMissing: Boolean): Context {
        check(Build.VERSION.SDK_INT >= 26)
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator required" }
        check(!Debug.isDebuggerConnected() && !Debug.waitingForDebugger()) { "Debugger suppresses timeout" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        val expectedMissing = InstrumentationRegistry.getArguments().getString("missingPermission")
            ?: Manifest.permission.READ_PHONE_STATE
        require(expectedMissing in listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO))
        check(missing == if (permissionMissing) listOf(expectedMissing) else emptyList<String>()) {
            "Unexpected missing permissions: $missing"
        }
        marker("PRECONDITIONS sdk=${Build.VERSION.SDK_INT} debugger=false missing=$missing package=${context.packageName}")
        return context
    }

    private fun marker(value: String) {
        val run = InstrumentationRegistry.getArguments().getString("runId") ?: "manual"
        Log.i("C04AmsProbe", "C04 run=$run pid=${Process.myPid()} uptimeMs=${SystemClock.uptimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} $value")
    }
}
