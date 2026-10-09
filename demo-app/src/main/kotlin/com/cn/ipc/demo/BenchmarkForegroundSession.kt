package com.cn.ipc.demo

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.*
import java.io.File

/** Explicit demo benchmark only; observations qualify the run and never rewrite measured samples. */
class BenchmarkForegroundSession(
    private val activity: AppCompatActivity,
    private val runId: String
) {
    private val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val rows = mutableListOf("elapsedRealtimeMs,uptimeMs,event,lifecycle,focused,interactive,keyguardLocked,deviceLocked,valid")
    private var started = false
    private var completed = false
    private var finished = false
    private var invalidObservations = 0
    private var firstInvalidEvent: String? = null

    suspend fun run(block: suspend () -> Unit) = withContext(Dispatchers.Main.immediate) {
        val originallyKeptOn = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            // onCreate is not a foreground guarantee. Wait for the actual lifecycle/window/power gate.
            withTimeout(10_000) {
                var consecutiveReady = 0
                while (consecutiveReady < 3) {
                    val state = snapshot()
                    observe("WAIT_READY", state)
                    consecutiveReady = if (state.valid) consecutiveReady + 1 else 0
                    if (consecutiveReady < 3) delay(100)
                }
            }
            started = true
            observe("BEGIN", snapshot())
            coroutineScope {
                val monitor = launch {
                    while (isActive) {
                        delay(500)
                        observe("SAMPLE", snapshot())
                    }
                }
                try {
                    block()
                    completed = true
                } finally {
                    withContext(NonCancellable) { monitor.cancelAndJoin() }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                observe("END", snapshot())
                finished = true
                val valid = started && completed && invalidObservations == 0
                if (!originallyKeptOn) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val safeRunId = runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val output = File(activity.filesDir, "foreground-$safeRunId.csv")
                val contents = rows.joinToString("\n", postfix = "\n")
                withContext(Dispatchers.IO) { output.writeText(contents) }
                Log.i(TAG, "END run=$runId started=$started completed=$completed valid=$valid invalidObservations=$invalidObservations " +
                    "firstInvalidEvent=$firstInvalidEvent file=${output.name} scope=activity_events_and_500ms_app_samples")
            }
        }
    }

    fun onResume() = activityEvent("ON_RESUME")
    fun onPause() = activityEvent("ON_PAUSE", forceInvalid = true)
    fun onStop() = activityEvent("ON_STOP", forceInvalid = true)
    fun onWindowFocusChanged(focused: Boolean) =
        activityEvent(if (focused) "FOCUS_GAIN" else "FOCUS_LOSS", forceInvalid = !focused)

    private fun activityEvent(event: String, forceInvalid: Boolean = false) {
        if (!finished) observe(event, snapshot(), forceInvalid)
    }

    private fun observe(event: String, state: Snapshot, forceInvalid: Boolean = false) {
        val valid = state.valid && !forceInvalid
        if (started && !valid) {
            invalidObservations++
            if (firstInvalidEvent == null) firstInvalidEvent = event
        }
        rows.add("${state.elapsedRealtimeMs},${state.uptimeMs},$event,${state.lifecycle},${state.focused}," +
            "${state.interactive},${state.keyguardLocked},${state.deviceLocked},$valid")
        Log.i(TAG, "STATE run=$runId event=$event elapsedRealtimeMs=${state.elapsedRealtimeMs} uptimeMs=${state.uptimeMs} " +
            "lifecycle=${state.lifecycle} focused=${state.focused} interactive=${state.interactive} " +
            "keyguardLocked=${state.keyguardLocked} deviceLocked=${state.deviceLocked} valid=$valid measuring=$started")
    }

    private fun snapshot(): Snapshot = Snapshot(
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        uptimeMs = SystemClock.uptimeMillis(),
        lifecycle = activity.lifecycle.currentState,
        focused = activity.hasWindowFocus(),
        interactive = power.isInteractive,
        keyguardLocked = keyguard.isKeyguardLocked,
        deviceLocked = keyguard.isDeviceLocked
    )

    private data class Snapshot(
        val elapsedRealtimeMs: Long,
        val uptimeMs: Long,
        val lifecycle: Lifecycle.State,
        val focused: Boolean,
        val interactive: Boolean,
        val keyguardLocked: Boolean,
        val deviceLocked: Boolean
    ) {
        val valid: Boolean get() = lifecycle == Lifecycle.State.RESUMED && focused && interactive &&
            !keyguardLocked && !deviceLocked
    }

    private companion object {
        const val TAG = "IpcForeground"
    }
}
