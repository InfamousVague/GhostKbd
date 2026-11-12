package com.infamousvague.devkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.View
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * The live preview in Settings (Matt, 2026-11-09: "live preview for the keyboard on settings"): the keyboard's own
 * [KeyboardView], drawn inside the settings app as a platform view (`ghost_keyboard_preview`,
 * lib/settings/keyboard_preview.dart). It reads the same settings file the keyboard does and listens to it, so a
 * theme, a key height, the number row, the backlight or a layout shows the moment its switch is flipped, with no
 * text field to tap and whether or not Ghost Keyboard is the keyboard in use.
 *
 * Its keys press, light, buzz and sound as the real ones do, and change pages and shift; they type nothing.
 */
class KeyboardPreview(context: Context) : PlatformView, SharedPreferences.OnSharedPreferenceChangeListener {
    private val app = context.applicationContext
    private val prefs = KeyboardPrefs(app)

    private val listener = object : KeyboardListener {
        override fun onText(text: String, caretBack: Int) = Unit
        override fun onDelete() = Unit
        override fun onSpace() = Unit
        override fun onEnter() = Unit
        override fun onCursor(steps: Int) = Unit
        override fun onShiftChanged() = Unit
        override fun onShowPicker() = Unit
        override fun onOpenSettings() = Unit
        override fun onHideKeyboard() = Unit
        override fun onMic() = Unit

        /** The globe key and the space bar's list work here as on the keyboard: the layout chosen is kept, and both follow. */
        override fun onLayout(id: String) { prefs.set(KeyboardPrefs.LAYOUT, id) }

        /** The key's buzz and sound as Settings has them, so Key press & sound can be tried here too. */
        override fun onKeyFeedback(key: Key, haptic: Boolean) {
            if (haptic && prefs.vibrate) keys.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (prefs.sound && prefs.soundVolume > 0f) prefs.stockSound.takeIf { it in SoundPacks.STOCK }?.let { SoundPacks.play(app, it, key.type, prefs.soundVolume) }
        }
    }

    private val keys: KeyboardView = KeyboardView(context, listener, embedded = true)

    init {
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        apply()
    }

    override fun getView(): View = keys

    override fun dispose() {
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = apply()

    /** The settings that change how the keys look and feel, as DevKeyboardService.applySettings gives them to its view. */
    private fun apply() {
        keys.theme = KeyboardTheme.resolve(keys.context, prefs)
        keys.micKey = Plugins.isOn(prefs, Plugins.DICTATION) && WhisperModel.isReady(app)
        keys.configure(prefs.numberRow, prefs.keyHeight, prefs.toolbar && !prefs.hideRow)
        val language = prefs.languageState()
        keys.setLanguage(language.spec, language.extras, language.spaceLabel, language.spaceShort, prefs.globeKey && language.stops.size > 1, language.choices, language.locale)
        keys.swipeTyping = Plugins.isOn(prefs, Plugins.SWIPE) && language.spec.swipe
        keys.wideLayout = prefs.wideLayout
        keys.oneHanded = prefs.oneHanded
        keys.adaptive = if (Plugins.isOn(prefs, Plugins.ADAPTIVE)) AdaptiveKeys.get(app, language.spec.id) else null
        keys.adaptiveMap = Plugins.isOn(prefs, Plugins.ADAPTIVE) && prefs.adaptiveMap
        keys.backlight = if (!prefs.backlight) null else keys.backlight?.takeIf {
            it.effect == prefs.backlightEffect && it.colour == prefs.backlightColour && it.strength == Backlight.strengthOf(prefs.backlightStrength) &&
                it.mode == prefs.backlightMode && it.fade == Backlight.fadeOf(prefs.backlightFade)
        } ?: Backlight(prefs.backlightEffect, prefs.backlightColour, Backlight.strengthOf(prefs.backlightStrength), prefs.backlightMode, Backlight.fadeOf(prefs.backlightFade))
        if (prefs.sound) prefs.stockSound.takeIf { it in SoundPacks.STOCK }?.let { SoundPacks.prepare(app, it) }
        keys.keyBorders = prefs.keyBorders
        keys.showPreview = prefs.keyPopup
        keys.showHints = prefs.hints
        keys.spaceCursor = prefs.spaceCursor
        keys.deleteSwipe = prefs.deleteSwipe
        keys.longPressMs = prefs.longPressMs
    }
}

/** Makes a [KeyboardPreview] for each `AndroidView(viewType: 'ghost_keyboard_preview')` the settings app draws. */
class KeyboardPreviewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView = KeyboardPreview(context)

    companion object {
        const val VIEW_TYPE = "ghost_keyboard_preview"
    }
}
