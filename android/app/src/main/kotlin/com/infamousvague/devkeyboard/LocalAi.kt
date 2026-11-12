package com.infamousvague.devkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The on-phone language model, as the keyboard asks it (Matt, 2026-11-12: "Add support for local AI models to work in
 * the keyboard ... please support them for rewriting and grammar checking"). Rewrite, with its Fix, runs here in
 * place of the person's AI provider where a model is on the phone and Rewrite › On this phone is on ([serves]): no
 * key, no connection, nothing leaving the phone, and it works with Offline on.
 *
 * The model itself lives in a process of its own (LlmService.kt). This binds to it for a request, waits for the
 * answer on the caller's thread (never the main one), tells it to stop when the caller says so, and lets go of it
 * [IDLE_MS] after the last request, which ends that process and gives its memory back. If that process dies while a
 * request is in it (Android wanted the memory, or the model's code failed), the request fails with words for the row
 * and the keyboard goes on.
 */
object LocalAi {
    /**
     * This build has the model's library in it (android/llm's libdevkeyboard_llm.so): looked for once, in the app's
     * own file. Where it has not, nothing here is offered and Settings does not mention it.
     */
    fun built(context: Context): Boolean = present ?: runCatching {
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { apk -> apk.entries().asSequence().any { it.name.endsWith("/libdevkeyboard_llm.so") } }
    }.getOrDefault(false).also { present = it }

    @Volatile private var present: Boolean? = null

    /**
     * Whether Rewrite runs on the phone as Settings stand: the library is in this build, the Rewrite plugin is added,
     * its "On this phone" switch is on, and a model is here.
     */
    fun serves(context: Context, prefs: KeyboardPrefs): Boolean =
        built(context) && Plugins.isOn(prefs, Plugins.REWRITE) && prefs.rewriteLocal && LocalModels.active(context) != null

    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, (Boolean, String) -> Unit>()

    /** Answers come here, on a thread of their own: the caller's thread is waiting, and the main thread is the keys'. */
    private val replies: Messenger by lazy {
        val thread = HandlerThread("local-ai").apply { start() }
        Messenger(Handler(thread.looper) { msg ->
            val data = msg.data ?: Bundle()
            val done = waiting.remove(data.getInt(LlmService.ID))
            when (msg.what) {
                LlmService.DONE -> done?.invoke(true, data.getString(LlmService.TEXT).orEmpty())
                LlmService.FAILED -> done?.invoke(false, data.getString(LlmService.ERROR) ?: "The phone's model gave no answer.")
            }
            true
        })
    }

    @Volatile private var link: Messenger? = null
    @Volatile private var bound = false
    private var linked = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            link = binder?.let { Messenger(it) }
            linked.countDown()
        }

        /** The model's process is gone: whoever was waiting on it is told, and the next request starts it again. */
        override fun onServiceDisconnected(name: ComponentName?) {
            link = null
            for (id in waiting.keys.toList()) waiting.remove(id)?.invoke(false, "The phone's model stopped: the phone may be short of memory.")
        }
    }

    private var app: Context? = null
    private val letGo: Runnable = Runnable { release() }

    /** The model's process, started if it is not there. Null where it did not come up in [CONNECT_MS]. */
    private fun connect(context: Context): Messenger? {
        val ctx = context.applicationContext
        val latch = synchronized(this) {
            app = ctx
            main.removeCallbacks(letGo)
            if (!bound) {
                linked = CountDownLatch(1)
                bound = ctx.bindService(Intent(ctx, LlmService::class.java), connection, Context.BIND_AUTO_CREATE)
            }
            linked
        }
        if (!bound) return null
        latch.await(CONNECT_MS, TimeUnit.MILLISECONDS)
        return link
    }

    /** Lets the model's process go: its memory is the phone's again. Asked for [IDLE_MS] after the last request. */
    private fun release() {
        synchronized(this) {
            if (waiting.isNotEmpty()) { main.postDelayed(letGo, IDLE_MS); return }
            if (bound) runCatching { app?.unbindService(connection) }
            bound = false
            link = null
        }
    }

    /**
     * One request to the model [spec]: [system] says what to do, [shots] are worked examples, [user] is the text.
     * Blocking, and never to be called on the main thread. [stopped] is asked while it works; once it says yes the
     * model is told to stop and this throws. Every failure is an [AiClient.AiError] with words for the row.
     */
    fun complete(
        context: Context, spec: LocalModels.Spec?, system: String, shots: List<LocalRewrite.Shot>, user: String,
        maxTokens: Int, temperature: Float, stopped: () -> Boolean = { false },
    ): String {
        if (!built(context)) throw AiClient.AiError("This version of Ghost Keyboard has no on-phone model in it")
        if (Looper.myLooper() == Looper.getMainLooper()) throw AiClient.AiError("The phone's model was asked on the wrong thread")
        if (spec == null || !LocalModels.isReady(context, spec)) throw AiClient.AiError("Download a model in Settings › AI › Rewrite")
        val service = connect(context) ?: throw AiClient.AiError("The phone's model could not be started")
        val id = ids.incrementAndGet()
        val done = CountDownLatch(1)
        var text: String? = null
        var error: String? = null
        waiting[id] = { ok, value -> if (ok) text = value else error = value; done.countDown() }
        val data = Bundle().apply {
            putInt(LlmService.ID, id)
            putString(LlmService.MODEL, LocalModels.file(context, spec).path)
            putString(LlmService.SYSTEM, system)
            putStringArray(LlmService.SHOTS, shots.flatMap { listOf(it.text, it.made) }.toTypedArray())
            putString(LlmService.USER, user)
            putInt(LlmService.MAX_TOKENS, maxTokens)
            putFloat(LlmService.TEMPERATURE, temperature)
            // A retry is another try, not the same answer again.
            putInt(LlmService.SEED, (SystemClock.uptimeMillis() and 0x7fffffff).toInt())
        }
        try {
            service.send(Message.obtain(null, LlmService.RUN).apply { this.data = data; replyTo = replies })
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            var told = false
            while (!done.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                val late = SystemClock.uptimeMillis() > deadline
                if (!told && (late || stopped())) {
                    told = true
                    runCatching { service.send(Message.obtain(null, LlmService.STOP, id, 0)) }
                    if (late) { error = "The phone's model took too long"; break }
                }
            }
        } catch (e: android.os.RemoteException) {
            error = "The phone's model stopped: the phone may be short of memory."
        } finally {
            waiting.remove(id)
            main.removeCallbacks(letGo)
            main.postDelayed(letGo, IDLE_MS)
        }
        text?.let { return it }
        throw AiClient.AiError(error?.trimEnd('.') ?: "The phone's model gave no answer")
    }

    /** How long the model's process is kept after a request (ms): the next rewrite within that starts at once. */
    private const val IDLE_MS = 3 * 60_000L
    private const val CONNECT_MS = 10_000L
    private const val POLL_MS = 150L
    /** The longest one request may take (ms), reading the model from the phone's storage included. */
    private const val TIMEOUT_MS = 4 * 60_000L
}
