package com.infamousvague.devkeyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * The settings app's host. One channel, `dev_keyboard/settings`, gives the Flutter side the keyboard's settings
 * ([KeyboardPrefs], read live by the service) and its setup state, and opens the system's keyboard screens.
 */
class MainActivity : FlutterActivity() {
    private var channel: MethodChannel? = null
    private val prefs by lazy { KeyboardPrefs(this) }
    private val imm by lazy { getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev_keyboard/settings").apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSettings" -> result.success(prefs.all())
                    "setSetting" -> {
                        val key = call.argument<String>("key")
                        val ok = key != null && prefs.set(key, call.argument<Any>("value"))
                        if (ok) result.success(null) else result.error("bad_setting", "Unknown setting or wrong type: $key", null)
                    }
                    "status" -> result.success(status())
                    "openInputSettings" -> {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(null)
                    }
                    "showPicker" -> {
                        imm.showInputMethodPicker()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
        }
    }

    private fun status(): Map<String, Boolean> {
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val selected = ComponentName.unflattenFromString(current)?.packageName == packageName
        return mapOf("enabled" to enabled, "selected" to selected)
    }

    /** Coming back from the system's keyboard list or the picker: tell the settings app to look again. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) channel?.invokeMethod("statusChanged", status())
    }

    override fun onResume() {
        super.onResume()
        channel?.invokeMethod("statusChanged", status())
    }
}
