package com.infamousvague.devkeyboard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * The trainer's screen's line to the trainer ([Trainer]; lib/trainer/trainer_store.dart): a channel of its own,
 * `dev_keyboard/trainer`, beside the settings'. The screen asks for [Trainer.state], starts a run, says when its field
 * changed, and ends the run; the trainer tells it where a run stands after each key (`progress`) and when a run was
 * dropped under it (`left`).
 *
 * A run does not outlive the screen it is typed on: when the activity is paused (the app left, the screen off) or
 * gone, the run in hand is dropped and the keyboard is back on the person's own layout.
 */
object TrainerChannel {
    const val NAME = "dev_keyboard/trainer"

    fun register(activity: Activity, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, NAME)
        val app = activity.applicationContext
        Trainer.onProgress = { channel.invokeMethod("progress", it) }
        Trainer.onLeft = { channel.invokeMethod("left", null) }
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    // The first asking reads the trainer's file, off this thread; every one after is answered at once.
                    "state" -> if (Trainer.loaded) result.success(Trainer.state(app)) else Thread {
                        val state = runCatching { Trainer.state(app) }
                        activity.runOnUiThread { state.onSuccess { result.success(it) }.onFailure { result.error("trainer", it.message, null) } }
                    }.start()
                    "setTrials" -> { Trainer.setTrials(app, call.argument<Boolean>("on") == true); result.success(Trainer.state(app)) }
                    "start" -> result.success(Trainer.start(app, call.argument<Boolean>("practice") == true))
                    "look" -> { Trainer.look(); result.success(null) }
                    "finish" -> result.success(Trainer.finish(app))
                    "leave" -> { Trainer.leave(told = true); result.success(null) }
                    "apply" -> {
                        val lines = Trainer.apply(app, call.argument<Boolean>("aims") == true, call.argument<Boolean>("height") == true)
                        result.success(Trainer.state(app) + mapOf("said" to lines))
                    }
                    "again" -> { Trainer.again(app); result.success(Trainer.state(app)) }
                    "forget" -> { Trainer.forget(app); result.success(Trainer.state(app)) }
                    else -> result.notImplemented()
                }
            } catch (t: Throwable) {
                android.util.Log.e("GhostKeyboard", "the trainer could not ${call.method}", t)
                result.error("trainer", t.message ?: "Something went wrong", null)
            }
        }
        activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { if (a === activity) Trainer.leave() }
            override fun onActivityDestroyed(a: Activity) {
                if (a !== activity) return
                Trainer.leave(told = true)
                Trainer.onProgress = null
                Trainer.onLeft = null
                activity.application.unregisterActivityLifecycleCallbacks(this)
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) = Unit
        })
    }
}
