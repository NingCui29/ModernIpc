package com.cn.ipc

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import java.io.File

/** Explicit diagnostic recording only. Disabled by default; no wire fields or per-event logs. */
object IpcRequestTrace {
    data class Stats(val events: Int, val dropped: Int)
    private data class Event(val side: String, val stage: String, val requestId: Long,
        val generation: Long, val callback: Int, val ns: Long, val tid: Int)
    private class Session(val run: String) {
        val events = ArrayList<Event>(CAPACITY)
        var dropped = 0
    }
    private const val CAPACITY = 16_384
    @Volatile private var session: Session? = null

    @Synchronized fun start(run: String) {
        require(run.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        check(session == null) { "IPC request trace already running" }
        session = Session(run)
    }

    fun nowIfEnabled(): Long = if (session == null) 0L else SystemClock.elapsedRealtimeNanos()
    fun threadIfEnabled(): Int = if (session == null) 0 else Process.myTid()

    fun recordAt(side: String, stage: String, requestId: Long, generation: Long = 0L,
                 callback: IBinder? = null, ns: Long = nowIfEnabled(), tid: Int = 0) {
        if (ns == 0L) return
        val current = session ?: return
        val event = Event(side, stage, requestId, generation, System.identityHashCode(callback),
            ns, if (tid == 0) Process.myTid() else tid)
        synchronized(current) {
            if (session !== current) return
            if (current.events.size < CAPACITY) current.events.add(event) else current.dropped++
        }
        // A same-thread marker provides context in Perfetto. Retroactive ns stays in the buffered event.
        Trace.beginSection("MIPC:$stage:$requestId")
        Trace.endSection()
    }

    fun stopAndFlush(output: File? = null): Stats {
        val current = synchronized(this) { session.also { session = null } } ?: return Stats(0, 0)
        val events: List<Event>
        val dropped: Int
        synchronized(current) { events = current.events.toList(); dropped = current.dropped }
        val pid = Process.myPid()
        fun message(event: Event): String = "EVT run=${current.run} side=${event.side} stage=${event.stage} " +
                "ns=${event.ns} pid=$pid tid=${event.tid} requestId=${event.requestId} " +
                "generation=${event.generation} callback=${event.callback}"
        val end = "END run=${current.run} pid=$pid events=${events.size} dropped=$dropped"
        if (output == null) events.forEach { Log.i("IpcReqTrace", message(it)) }
        else output.bufferedWriter().use { writer ->
            events.forEach { writer.appendLine("IpcReqTrace: ${message(it)}") }
            writer.appendLine("IpcReqTrace: $end")
        }
        Log.i("IpcReqTrace", end)
        return Stats(events.size, dropped)
    }
}
