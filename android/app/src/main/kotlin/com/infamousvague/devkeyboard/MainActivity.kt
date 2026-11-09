package com.infamousvague.devkeyboard

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.media.MediaPlayer
import io.flutter.FlutterInjector
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
    private val onUpdate: (Updater.State) -> Unit = { channel?.invokeMethod("updateChanged", it.toMap(this)) }
    private val onModel: (WhisperModel.State) -> Unit = { channel?.invokeMethod("dictationChanged", dictationState()) }

    /** A page the app was opened at (the keyboard's mic key, when the microphone is not allowed yet). */
    private var openAt: String? = null

    private fun dictationState(): Map<String, Any?> = WhisperModel.toMap(this) + mapOf(
        "language" to prefs.dictationLanguage,
        "languages" to WhisperModel.LANGUAGES.map { (code, name) -> mapOf("code" to code, "name" to name) },
        "microphone" to (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
    )

    private var player: MediaPlayer? = null

    /** The walkthrough's voice: one of the app's Flutter assets, played once; false where the file is not there. */
    private fun play(asset: String): Boolean {
        stopAudio()
        return runCatching {
            val key = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(asset)
            assets.openFd(key).use { fd ->
                player = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare()
                    start()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun stopAudio() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { channel?.invokeMethod("open", it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        channel?.invokeMethod("dictationChanged", dictationState())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.addListener(onUpdate)
        WhisperModel.refresh(this)
        WhisperModel.addListener(onModel)
        openAt = intent?.getStringExtra(EXTRA_OPEN)
        // Automatic updates: look each time the app opens, and install what is found.
        if (KeyboardPrefs(this).autoUpdate) Updater.check(this, install = true)
    }

    override fun onDestroy() {
        Updater.removeListener(onUpdate)
        WhisperModel.removeListener(onModel)
        super.onDestroy()
    }

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
                    "playAsset" -> result.success(play(call.argument<String>("asset") ?: ""))
                    "stopAudio" -> { stopAudio(); result.success(null) }
                    "openAt" -> { result.success(openAt); openAt = null }
                    "setPlugin" -> {
                        val id = call.argument<String>("id") ?: ""
                        val on = call.argument<Boolean>("on") == true
                        if (!Plugins.set(prefs, id, on)) { result.error("bad_plugin", "Unknown plugin: $id", null); return@setMethodCallHandler }
                        // Adding dictation fetches its model and asks for the microphone, in one go.
                        if (id == Plugins.DICTATION && on) {
                            WhisperModel.download(this@MainActivity)
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                            }
                        }
                        result.success(prefs.all())
                    }
                    "openAutofillSettings" -> {
                        // There is no public screen for picking the autofill service on every phone: try the
                        // Passwords & accounts screen some open with, then Settings itself.
                        val tries = listOf(
                            Intent("android.settings.AUTOFILL_SETTINGS"),
                            Intent(android.provider.Settings.ACTION_SYNC_SETTINGS),
                            Intent(android.provider.Settings.ACTION_SETTINGS),
                        )
                        for (intent in tries) {
                            if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
                        }
                        result.success(null)
                    }
                    "clipboardState" -> result.success(mapOf("count" to ClipHistory.all(this@MainActivity).size))
                    "clearClipboard" -> { ClipHistory.clear(this@MainActivity); result.success(mapOf("count" to 0)) }
                    "soundPacks" -> result.success(
                        SoundPacks.packs(this@MainActivity).filter { SoundPacks.available(this@MainActivity, it.key) }.map { mapOf("id" to it.key, "name" to it.value) },
                    )
                    "sampleSound" -> {
                        val pack = call.argument<String>("pack") ?: prefs.soundPack
                        SoundPacks.sample(this@MainActivity, pack, prefs.soundVolume.coerceAtLeast(0.15f))
                        result.success(null)
                    }
                    "dictationState" -> result.success(dictationState())
                    "downloadModel" -> {
                        val id = call.argument<String>("id")
                        WhisperModel.download(this@MainActivity, if (id == null) WhisperModel.active(this@MainActivity) else WhisperModel.spec(id))
                        result.success(null)
                    }
                    "deleteModel" -> {
                        val id = call.argument<String>("id")
                        WhisperModel.delete(this@MainActivity, if (id == null) WhisperModel.active(this@MainActivity) else WhisperModel.spec(id))
                        result.success(null)
                    }
                    "useModel" -> {
                        // Choosing a model downloads it if it is not here yet.
                        val spec = WhisperModel.spec(call.argument<String>("id"))
                        prefs.set(KeyboardPrefs.WHISPER_MODEL, spec.id)
                        WhisperModel.download(this@MainActivity, spec)
                        result.success(dictationState())
                    }
                    "setDictationLanguage" -> {
                        prefs.set(KeyboardPrefs.DICTATION_LANGUAGE, call.argument<String>("code") ?: "auto")
                        result.success(dictationState())
                    }
                    "requestMicrophone" -> {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                        result.success(null)
                    }
                    "updateState" -> result.success(Updater.state.toMap(this@MainActivity))
                    "checkUpdate" -> {
                        Updater.check(this@MainActivity, install = call.argument<Boolean>("install") == true)
                        result.success(null)
                    }
                    "installUpdate" -> {
                        if (!packageManager.canRequestPackageInstalls()) {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                        } else {
                            Updater.installNow(this@MainActivity)
                        }
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

    companion object {
        /** An extra naming the settings page to open at. */
        const val EXTRA_OPEN = "open"
        private const val REQUEST_MIC = 41
    }
}
