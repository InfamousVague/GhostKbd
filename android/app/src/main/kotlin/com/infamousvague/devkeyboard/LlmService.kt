package com.infamousvague.devkeyboard

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.Executors

/**
 * The on-phone language model's own process (":llm" in the manifest). It holds the model and runs one request at a
 * time for LocalAi, which binds to it from the keyboard's process and lets go a few minutes after the last request.
 *
 * It is a process of its own for the keyboard's sake. A model is one to three gigabytes held in memory by native code
 * that ends the process when something is wrong (llama.cpp aborts on a failed check): when Android wants that memory
 * back, or the model's code fails, this process goes and the keyboard is still there to say so. Nothing here opens
 * a connection, and no text is written to the log.
 */
class LlmService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llm").apply { isDaemon = true } }
    private var handle = 0L
    private var path: String? = null

    /** The request being run, and those told to stop before their turn came. */
    @Volatile private var running = -1
    private val dropped = HashSet<Int>()

    private val inbox = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            RUN -> run(msg.data ?: Bundle(), msg.replyTo)
            STOP -> msg.arg1.let { id -> if (id == running) Llm.nativeStop() else synchronized(dropped) { dropped += id } }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = inbox.binder

    private fun run(data: Bundle, reply: Messenger?) {
        val id = data.getInt(ID)
        worker.execute {
            val answer = Bundle().apply { putInt(ID, id) }
            val ok = runCatching { work(id, data, answer) }.getOrElse { answer.putString(ERROR, "The phone's model failed."); false }
            running = -1
            runCatching { reply?.send(Message.obtain(null, if (ok) DONE else FAILED).apply { this.data = answer }) }
        }
    }

    /** One request on the worker thread: the model read if it is not the one in hand, then run. True with TEXT set, false with ERROR. */
    private fun work(id: Int, data: Bundle, answer: Bundle): Boolean {
        fun fail(why: String): Boolean { answer.putString(ERROR, why); return false }
        if (!Llm.load()) return fail("This version of Ghost Keyboard has no on-phone model in it.")
        val file = data.getString(MODEL).orEmpty()
        if (path != file) {
            if (handle != 0L) { Llm.nativeFree(handle); handle = 0L; path = null }
            handle = Llm.nativeLoad(file)
            if (handle == 0L) return fail(String(Llm.nativeError(), Charsets.UTF_8).ifBlank { "The model could not be read." })
            path = file
        }
        // Stopped while it waited its turn, or while the model was being read.
        if (synchronized(dropped) { dropped.remove(id) }) return fail(STOPPED)
        running = id
        Llm.nativeGo()
        // A little behind the keys' own threads, so typing stays smooth while it writes.
        runCatching { android.os.Process.setThreadPriority(4) }
        val shots = data.getStringArray(SHOTS).orEmpty().map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val out = Llm.nativeComplete(
            handle, data.getString(SYSTEM).orEmpty().toByteArray(Charsets.UTF_8), shots, data.getString(USER).orEmpty().toByteArray(Charsets.UTF_8),
            data.getInt(MAX_TOKENS, 256), data.getFloat(TEMPERATURE, 0.3f), data.getInt(SEED, 42), threads(),
        ) ?: return fail(String(Llm.nativeError(), Charsets.UTF_8).ifBlank { "The model gave no answer." })
        val stats = Llm.nativeStats()
        if (stats.getOrElse(5) { 0 } == 1) return fail(STOPPED)
        answer.putString(TEXT, String(out, Charsets.UTF_8))
        answer.putIntArray(STATS, stats)
        return true
    }

    /** The model's threads: the phone's cores less one for the keys, between two and six (Ghost.md's rule for the same models). */
    private fun threads() = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)

    override fun onDestroy() {
        super.onDestroy()
        // The model's memory goes back to the phone at once, not whenever Android gets round to this process.
        Llm.nativeStop()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        const val RUN = 1
        const val STOP = 2
        const val DONE = 3
        const val FAILED = 4
        const val ID = "id"
        const val MODEL = "model"
        const val SYSTEM = "system"
        const val SHOTS = "shots"
        const val USER = "user"
        const val MAX_TOKENS = "maxTokens"
        const val TEMPERATURE = "temperature"
        const val SEED = "seed"
        const val TEXT = "text"
        const val ERROR = "error"
        const val STATS = "stats"
        /** What a request that was told to stop answers. */
        const val STOPPED = "Stopped."
    }
}
