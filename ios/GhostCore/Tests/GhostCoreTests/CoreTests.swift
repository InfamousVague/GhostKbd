import XCTest
@testable import GhostCore

func makePrefs(_ values: [String: Any] = [:], secrets: [String: String] = [:]) -> (KeyboardPrefs, MemoryPrefsStore, MemorySecretStore) {
    let store = MemoryPrefsStore(values)
    let secret = MemorySecretStore(secrets)
    return (KeyboardPrefs(store: store, secrets: secret), store, secret)
}

final class LayoutsTests: XCTestCase {
    private func pages() -> [(String, [KeyRow])] {
        [("letters", Layouts.letters(numberRow: false)), ("letters+numbers", Layouts.letters(numberRow: true)),
         ("dev", Layouts.dev()), ("symbols", Layouts.symbols()), ("more", Layouts.more())]
    }

    func testEveryRowIsTenUnits() {
        for (name, rows) in pages() {
            for variant in [rows, Layouts.withMic(rows), Layouts.withGlobe(rows), Layouts.withGlobe(Layouts.withMic(rows))] {
                for (i, row) in variant.enumerated() { XCTAssertEqual(row.units, 10, accuracy: 0.0001, "\(name) row \(i)") }
            }
        }
    }

    func testRowCounts() {
        XCTAssertEqual(Layouts.letters(numberRow: false).count, 4)
        XCTAssertEqual(Layouts.letters(numberRow: true).count, 5)
        XCTAssertEqual(Layouts.letters(numberRow: true)[0].keys.map { $0.text }.joined(), "1234567890")
        XCTAssertEqual(Layouts.rows(for: .dev, numberRow: false), Layouts.dev())
    }

    func testHintsAndAlts() {
        let rows = Layouts.letters(numberRow: false)
        let q = rows[0].keys[0], e = rows[0].keys[2], a = rows[1].keys[0], z = rows[2].keys[1]
        XCTAssertEqual(q.text, "q"); XCTAssertEqual(q.hint, "1"); XCTAssertEqual(q.alts, ["1"])
        XCTAssertEqual(e.hint, "3"); XCTAssertEqual(e.alts, ["3", "è", "é", "ê", "ë", "ē", "ę", "ė"])
        XCTAssertEqual(a.hint, "@"); XCTAssertEqual(a.alts.first, "@"); XCTAssertTrue(a.alts.contains("ä"))
        XCTAssertEqual(z.hint, "*")
        XCTAssertEqual(rows[1].inset, 0.5)
        // With the number row the digits leave the corners.
        let e2 = Layouts.letters(numberRow: true)[1].keys[2]
        XCTAssertNil(e2.hint); XCTAssertEqual(e2.alts.first, "è")
        XCTAssertEqual(rows[2].keys.first?.type, .shift); XCTAssertEqual(rows[2].keys.last?.type, .delete)
        XCTAssertTrue(rows[2].keys[0].functional); XCTAssertFalse(q.functional)
    }

    func testBottomRows() {
        let bottom = Layouts.letters(numberRow: false).last!.keys
        XCTAssertEqual(bottom.map { $0.type }, [.mode, .mode, .space, .char, .enter])
        XCTAssertEqual(bottom[0].label, "?123"); XCTAssertEqual(bottom[0].page, .symbols); XCTAssertTrue(bottom[0].pill)
        XCTAssertEqual(bottom[1].label, "{ }"); XCTAssertEqual(bottom[1].page, .dev); XCTAssertFalse(bottom[1].pill)
        XCTAssertEqual(bottom[2].width, 5); XCTAssertEqual(bottom[3].alts.count, 16)
        let dev = Layouts.dev().last!.keys
        XCTAssertEqual(dev[1].text, ","); XCTAssertEqual(dev[1].page, .letters)
        XCTAssertEqual(Layouts.symbols()[2].keys[0].page, .more)
        XCTAssertEqual(Layouts.more()[2].keys[0].page, .symbols)
        XCTAssertEqual(Layouts.symbols()[1].keys[7].alts, ["<", "[", "{"])
    }

    func testGlobeAndMic() {
        let rows = Layouts.letters(numberRow: false)
        let globe = Layouts.withGlobe(rows).last!.keys
        XCTAssertEqual(globe.map { $0.type }, [.mode, .globe, .mode, .space, .char, .enter])
        XCTAssertEqual(globe[3].width, 4); XCTAssertEqual(globe[1].width, 1); XCTAssertTrue(globe[1].functional)
        let mic = Layouts.withMic(rows).last!.keys
        XCTAssertEqual(mic.map { $0.type }, [.mode, .mode, .mic, .space, .char, .enter])
        XCTAssertEqual(mic[3].width, 4)
        let both = Layouts.withGlobe(Layouts.withMic(rows)).last!.keys
        XCTAssertEqual(both.map { $0.type }, [.mode, .globe, .mode, .mic, .space, .char, .enter])
        XCTAssertEqual(both[4].width, 3)
        // The rows above are untouched.
        XCTAssertEqual(Array(Layouts.withGlobe(rows).dropLast()), Array(rows.dropLast()))
    }

    func testDevPageCaretMarks() {
        let dev = Layouts.dev()
        let top = dev[0].keys
        XCTAssertEqual(top[0].label, "( )"); XCTAssertEqual(top[0].text, "(¦)"); XCTAssertEqual(top[0].alts, ["(", ")"])
        XCTAssertEqual(top[2].alts.last, "{\n  ¦\n}")
        XCTAssertEqual(top[7].label, "${ }"); XCTAssertEqual(top[7].text, "${¦}")
        XCTAssertEqual(top[9].text, "function ¦() {}")
        for key in top.prefix(8) { XCTAssertEqual(key.text.filter { $0 == Key.caretMark }.count, 1, key.label) }
        XCTAssertEqual(dev[1].keys[3].alts.last, "/*¦*/")
        XCTAssertEqual(dev[2].keys[0].text, "\t"); XCTAssertEqual(dev[2].keys[0].label, "⇥"); XCTAssertEqual(dev[2].keys[0].width, 1.5)
    }
}

final class ThemeTests: XCTestCase {
    func testParseCustom() {
        let t = KeyboardTheme.parseCustom("#0A0B0C, #232528,#16181A,#51FCFB,E8EDED")
        XCTAssertNotNil(t)
        XCTAssertEqual(t?.background, 0xFF0A0B0C); XCTAssertEqual(t?.key, 0xFF232528); XCTAssertEqual(t?.functionKey, 0xFF16181A)
        XCTAssertEqual(t?.accent, 0xFF51FCFB); XCTAssertEqual(t?.text, 0xFFE8EDED); XCTAssertEqual(t?.dark, true)
        XCTAssertNil(KeyboardTheme.parseCustom("#000000,#111111"))
        XCTAssertNil(KeyboardTheme.parseCustom("#000000,#111111,#222222,#333333,nope"))
        XCTAssertNil(KeyboardTheme.parseCustom(""))
    }

    func testFromBaseDarkFlagAndDerived() {
        let dark = KeyboardTheme.fromBase(background: 0xFF000000, key: 0xFF1A1A1A, functionKey: 0xFF0D0D0D, accent: 0xFF8AB4F8, text: 0xFFFFFFFF)
        XCTAssertTrue(dark.dark); XCTAssertEqual(dark.popupShadow, 0x66000000); XCTAssertEqual(dark.onAccent, 0xFF111111)
        XCTAssertEqual(dark.popupSelected, dark.accent)
        let light = KeyboardTheme.fromBase(background: 0xFFF4EFE6, key: 0xFFFFFCF6, functionKey: 0xFFE4DCCD, accent: 0xFFB5522B, text: 0xFF2B2622)
        XCTAssertFalse(light.dark); XCTAssertEqual(light.popupShadow, 0x33000000); XCTAssertEqual(light.onAccent, 0xFFFFFFFF)
        XCTAssertEqual(light.popup, light.key)
        XCTAssertEqual(light.keyPressed, KeyboardTheme.mix(0xFFFFFCF6, 0xFF2B2622, 0.18))
    }

    func testMixAndLuminance() {
        XCTAssertEqual(KeyboardTheme.mix(0xFF000000, 0xFFFFFFFF, 0.5), 0xFF7F7F7F)
        XCTAssertEqual(KeyboardTheme.mix(0x00102030, 0xFF405060, 0), 0xFF102030)
        XCTAssertEqual(KeyboardTheme.mix(0xFF102030, 0xFF405060, 1), 0xFF405060)
        XCTAssertEqual(KeyboardTheme.mix(0xFFFF0000, 0xFF0000FF, 0.25), 0xFFBF003F)
        XCTAssertEqual(KeyboardTheme.luminance(0xFFFFFFFF), 1, accuracy: 0.0001)
        XCTAssertEqual(KeyboardTheme.luminance(0xFF000000), 0, accuracy: 0.0001)
    }

    func testPresetsAndResolve() {
        XCTAssertEqual(Set(KeyboardTheme.presets.keys), Set(KeyboardTheme.presetIds))
        let retro = KeyboardTheme.presets["retro"]!
        XCTAssertTrue(retro.mechanical); XCTAssertEqual(retro.hint, 0xFF7A715F); XCTAssertEqual(retro.toolbarIcon, 0xFFD8CFBB)
        XCTAssertFalse(KeyboardTheme.presets["paper"]!.dark)

        let (prefs, _, _) = makePrefs()
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: false), .dark)   // the default theme is "dark"
        prefs.set(key: KeyboardPrefs.theme, value: "system")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: false), .light)
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), .dark)
        prefs.set(key: KeyboardPrefs.theme, value: "light")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), .light)
        // A preset counts only with the themes plugin added.
        prefs.set(key: KeyboardPrefs.themePreset, value: "retro")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), .light)
        Plugins.set(prefs, Plugins.themes, on: true)
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), retro)
        prefs.set(key: KeyboardPrefs.themePreset, value: "custom")
        prefs.set(key: KeyboardPrefs.customTheme, value: "#FFFFFF,#EEEEEE,#DDDDDD,#0000FF,#000000")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true).background, 0xFFFFFFFF)
        prefs.set(key: KeyboardPrefs.customTheme, value: "garbage")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), .light)
        prefs.set(key: KeyboardPrefs.themePreset, value: "ghost")
        XCTAssertEqual(KeyboardTheme.resolve(prefs: prefs, systemDark: true), .light)
    }
}

final class BacklightTests: XCTestCase {
    func testFlareDecays() {
        let b = Backlight(effect: "reactive", colour: "accent", strength: 0.6)
        XCTAssertEqual(b.light(id: "a", x: 0, y: 0, width: 300, now: 1000), 0)
        XCTAssertFalse(b.animating(now: 1000))
        b.press(id: "a", x: 10, y: 10, now: 1000)
        XCTAssertEqual(b.light(id: "a", x: 10, y: 10, width: 300, now: 1000), 1, accuracy: 0.0001)
        XCTAssertEqual(b.light(id: "a", x: 10, y: 10, width: 300, now: 1600), 0.25, accuracy: 0.0001)
        XCTAssertEqual(b.light(id: "a", x: 10, y: 10, width: 300, now: 2200), 0)
        XCTAssertEqual(b.light(id: "b", x: 40, y: 10, width: 300, now: 1100), 0)
        XCTAssertTrue(b.animating(now: 2100)); XCTAssertFalse(b.animating(now: 2200))
        XCTAssertEqual(b.frameDelay(), 16)
    }

    func testFadeSetting() {
        let slow = Backlight(effect: "reactive", colour: "accent", strength: 0.6, fade: Backlight.fadeOf("slow"))
        slow.press(id: "a", x: 0, y: 0, now: 0)
        XCTAssertEqual(slow.light(id: "a", x: 0, y: 0, width: 300, now: 1200), 0.25, accuracy: 0.0001)
        XCTAssertEqual(Backlight.fadeOf("fast"), 0.5); XCTAssertEqual(Backlight.strengthOf("low"), 0.35)
        XCTAssertEqual(Backlight.strengthOf("high"), 0.9); XCTAssertEqual(Backlight.strengthOf("x"), 0.6)
        XCTAssertTrue(Backlight(effect: "reactive", colour: "accent", strength: 0.6, mode: "dim").dims)
    }

    func testRipple() {
        let b = Backlight(effect: "ripple", colour: "accent", strength: 0.6)
        b.press(id: "a", x: 0, y: 0, now: 0)
        // After half a second the ring has run 0.5 × 0.6 × 300 = 90 points out.
        let onRing = b.light(id: "far", x: 90, y: 0, width: 300, now: 500)
        XCTAssertEqual(onRing, 1 - 0.5 / 1.6, accuracy: 0.001)
        XCTAssertLessThan(b.light(id: "far", x: 250, y: 0, width: 300, now: 500), 0.01)
        XCTAssertLessThan(b.light(id: "far", x: 90, y: 0, width: 300, now: 1000), onRing)
        XCTAssertTrue(b.animating(now: 1500)); XCTAssertFalse(b.animating(now: 1700))
        XCTAssertEqual(b.light(id: "far", x: 90, y: 0, width: 300, now: 5000), 0)
    }

    func testHeatmapAndAmbientEffects() {
        let h = Backlight(effect: "heatmap", colour: "accent", strength: 0.6)
        h.press(id: "a", x: 0, y: 0, now: 0); h.press(id: "a", x: 0, y: 0, now: 10); h.press(id: "b", x: 0, y: 0, now: 20)
        XCTAssertEqual(h.light(id: "a", x: 0, y: 0, width: 300, now: 5000), 0.85, accuracy: 0.001)
        XCTAssertEqual(h.light(id: "b", x: 0, y: 0, width: 300, now: 5000), 0.425, accuracy: 0.01)
        XCTAssertEqual(h.frameDelay(), 120)
        XCTAssertEqual(Backlight(effect: "rainbow", colour: "accent", strength: 0.6).light(id: "a", x: 0, y: 0, width: 300, now: 0), 0.38, accuracy: 0.0001)
        let breathe = Backlight(effect: "breathe", colour: "accent", strength: 0.6).light(id: "a", x: 0, y: 0, width: 300, now: 0)
        XCTAssertEqual(breathe, 0.19, accuracy: 0.0001)
        XCTAssertTrue(Backlight(effect: "starlight", colour: "accent", strength: 0.6).animating(now: 0))
    }

    func testColours() {
        let b = Backlight(effect: "reactive", colour: "accent", strength: 0.6)
        XCTAssertEqual(b.colourAt(x: 0, width: 300, now: 0, accent: 0xFF123456), 0xFF123456)
        XCTAssertEqual(Backlight(effect: "reactive", colour: "rose", strength: 0.6).colourAt(x: 0, width: 300, now: 0, accent: 0), 0xFFFF5C8A)
        XCTAssertEqual(Backlight.hsvToArgb(h: 0, s: 1, v: 1), 0xFFFF0000)
        XCTAssertEqual(Backlight.hsvToArgb(h: 120, s: 1, v: 1), 0xFF00FF00)
        XCTAssertEqual(Backlight.hsvToArgb(h: 240, s: 1, v: 1), 0xFF0000FF)
        XCTAssertEqual(Backlight.hsvToArgb(h: 0, s: 0, v: 1), 0xFFFFFFFF)
        XCTAssertEqual(Backlight(effect: "rainbow", colour: "accent", strength: 0.6).colourAt(x: 0, width: 300, now: 0, accent: 0), 0xFFFF4040)
        XCTAssertEqual("hello".javaHashCode, 99162322)
        let (prefs, _, _) = makePrefs()
        XCTAssertEqual(Backlight(prefs: prefs)?.effect, "reactive")
        prefs.set(key: KeyboardPrefs.backlight, value: false)
        XCTAssertNil(Backlight(prefs: prefs))
    }
}

final class PrefsTests: XCTestCase {
    func testDefaults() {
        let (prefs, _, _) = makePrefs()
        XCTAssertEqual(prefs.theme, "dark"); XCTAssertFalse(prefs.numberRow); XCTAssertTrue(prefs.keyPopup)
        XCTAssertTrue(prefs.autocorrect); XCTAssertEqual(prefs.languages, ["en"]); XCTAssertEqual(prefs.plugins, "autocorrect,swipe,clipboard,undo,emoji")
        XCTAssertEqual(prefs.soundVolume, 0.35, accuracy: 0.0001); XCTAssertEqual(prefs.emojiSkin, 0); XCTAssertEqual(prefs.longPressMs, 300)
        XCTAssertEqual(prefs.aiProvider, "anthropic"); XCTAssertEqual(prefs.rewriteLanguage, "Spanish"); XCTAssertEqual(prefs.wideLayout, "split")
        XCTAssertFalse(prefs.aiConsent); XCTAssertTrue(prefs.ghostwriterAuto); XCTAssertFalse(prefs.ghostwriterClipboard)
        XCTAssertEqual(prefs.aiKey("openai"), ""); XCTAssertEqual(prefs.githubToken, ""); XCTAssertEqual(prefs.learnedWords, [])
        XCTAssertEqual(KeyboardPrefs.defaultKeys.first, "theme")
        XCTAssertEqual(KeyboardPrefs.defaultKeys.count, KeyboardPrefs.defaults.count)
        XCTAssertEqual(KeyboardPrefs.defaultKeys.count, 65 + 16)
        XCTAssertEqual(Array(KeyboardPrefs.defaultKeys.suffix(2)), ["ai_key_custom", "ai_model_custom"])
        let all = prefs.all()
        XCTAssertEqual(Set(all.keys), Set(KeyboardPrefs.defaultKeys))
        XCTAssertEqual(all["theme"] as? String, "dark"); XCTAssertEqual(all["ai_consent"] as? Bool, false); XCTAssertEqual(all["wide_layout"] as? String, "split")
        XCTAssertEqual(KeyboardPrefs.appGroup, "group.com.infamousvague.ghostkbd")
        XCTAssertEqual(KeyboardPrefs.keychainService, "com.infamousvague.ghostkbd.secrets")
    }

    func testSetRejectsUnknownKeysAndTypes() {
        let (prefs, store, _) = makePrefs()
        XCTAssertFalse(prefs.set(key: "nope", value: true))
        XCTAssertFalse(prefs.set(key: KeyboardPrefs.numberRow, value: "yes"))
        XCTAssertFalse(prefs.set(key: KeyboardPrefs.numberRow, value: 1))
        XCTAssertFalse(prefs.set(key: KeyboardPrefs.theme, value: true))
        XCTAssertFalse(prefs.set(key: KeyboardPrefs.theme, value: nil))
        XCTAssertTrue(store.keys.isEmpty)
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.numberRow, value: true))
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.theme, value: "light"))
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.aiConsent, value: true))
        XCTAssertTrue(prefs.numberRow); XCTAssertEqual(prefs.theme, "light"); XCTAssertTrue(prefs.aiConsent)
        XCTAssertEqual(prefs.all()["number_row"] as? Bool, true)
    }

    func testTypedGetters() {
        let (prefs, _, _) = makePrefs([
            "sound_volume": "250", "emoji_skin": "3", "long_press": "slow", "languages": " en , es,,", "split_keyboard": false,
            "ai_model_openai": "gpt-x",
        ])
        XCTAssertEqual(prefs.soundVolume, 1); XCTAssertEqual(prefs.emojiSkin, 3); XCTAssertEqual(prefs.longPressMs, 500)
        XCTAssertEqual(prefs.languages, ["en", "es"]); XCTAssertEqual(prefs.wideLayout, "full"); XCTAssertEqual(prefs.aiModel("openai"), "gpt-x")
        XCTAssertEqual(prefs.all()["wide_layout"] as? String, "full")
        prefs.set(key: KeyboardPrefs.wideLayout, value: "left")
        XCTAssertEqual(prefs.wideLayout, "left")
    }

    func testSecretsGoToTheSecretStore() {
        let (prefs, store, secrets) = makePrefs()
        XCTAssertTrue(prefs.set(key: "ai_key_anthropic", value: "sk-ant-1"))
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.githubToken, value: "ghp_1"))
        XCTAssertTrue(prefs.set(key: "ai_model_anthropic", value: "claude-x"))
        XCTAssertFalse(prefs.set(key: "ai_key_anthropic", value: true))
        XCTAssertFalse(prefs.set(key: "ai_key_nobody", value: "x"))
        XCTAssertNil(store.object(forKey: "ai_key_anthropic")); XCTAssertNil(store.object(forKey: "github_token"))
        XCTAssertEqual(store.keys, ["ai_model_anthropic"])
        XCTAssertEqual(secrets.get("ai_key_anthropic"), "sk-ant-1"); XCTAssertEqual(secrets.get("github_token"), "ghp_1")
        XCTAssertEqual(prefs.aiKey("anthropic"), "sk-ant-1"); XCTAssertEqual(prefs.githubToken, "ghp_1")
        let all = prefs.all()
        XCTAssertEqual(all["ai_key_anthropic"] as? String, "sk-ant-1"); XCTAssertEqual(all["github_token"] as? String, "ghp_1")
        XCTAssertEqual(all["ai_key_openai"] as? String, "")
        for p in KeyboardPrefs.aiProviders { XCTAssertTrue(KeyboardPrefs.isSecret("ai_key_\(p)")); XCTAssertFalse(KeyboardPrefs.isSecret("ai_model_\(p)")) }
        XCTAssertTrue(prefs.set(key: "ai_key_anthropic", value: ""))
        XCTAssertEqual(secrets.keys, ["github_token"])
        // A secret someone left in the prefs store is never read from there.
        store.set("leaked", forKey: "ai_key_openai")
        XCTAssertEqual(prefs.aiKey("openai"), "")
    }

    func testLearn() {
        let (prefs, store, _) = makePrefs()
        prefs.learn("Zebra"); prefs.learn("apple"); prefs.learn("zebra")
        XCTAssertEqual(prefs.learnedWords, ["apple", "zebra"])
        XCTAssertEqual(store.object(forKey: "learned_words") as? String, "apple\nzebra")
    }

    func testPlugins() {
        let (prefs, _, _) = makePrefs()
        XCTAssertEqual(Plugins.enabled(prefs), ["autocorrect", "swipe", "clipboard", "undo", "emoji"])
        XCTAssertTrue(Plugins.isOn(prefs, Plugins.swipe)); XCTAssertFalse(Plugins.isOn(prefs, Plugins.ghostwriter))
        XCTAssertFalse(Plugins.set(prefs, "nope", on: true))
        XCTAssertTrue(Plugins.set(prefs, Plugins.ghostwriter, on: true))
        XCTAssertTrue(Plugins.set(prefs, Plugins.swipe, on: false))
        // Kept in Plugins.all's order.
        XCTAssertEqual(prefs.plugins, "autocorrect,clipboard,undo,emoji,ghostwriter")
        prefs.set(key: KeyboardPrefs.plugins, value: "emoji, bogus ,themes")
        XCTAssertEqual(Plugins.enabled(prefs), ["emoji", "themes"])
        XCTAssertEqual(Plugins.all.count, 17)
    }

    func testAddNewDefaults() {
        let (fresh, freshStore, _) = makePrefs()
        fresh.addNewDefaults()
        XCTAssertNil(freshStore.object(forKey: "plugins")); XCTAssertEqual(freshStore.object(forKey: "defaults_085") as? Bool, true)
        let (old, _, _) = makePrefs(["plugins": "themes,autocorrect"])
        old.addNewDefaults()
        XCTAssertEqual(old.plugins, "autocorrect,themes,swipe,clipboard,undo,emoji")
        Plugins.set(old, Plugins.emoji, on: false)
        old.addNewDefaults()
        XCTAssertFalse(Plugins.isOn(old, Plugins.emoji))
    }

    func testUserDefaultsIsAStore() {
        let name = "ghostcore-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let prefs = KeyboardPrefs(store: defaults, secrets: MemorySecretStore())
        XCTAssertFalse(prefs.numberRow)
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.numberRow, value: true))
        XCTAssertTrue(prefs.set(key: KeyboardPrefs.theme, value: "light"))
        XCTAssertTrue(prefs.numberRow); XCTAssertEqual(prefs.theme, "light")
        XCTAssertEqual(prefs.all()["number_row"] as? Bool, true)
    }
}

final class AdaptiveKeysTests: XCTestCase {
    func testMinSamplesGate() {
        let keys = AdaptiveKeys(store: MemoryPrefsStore())
        for _ in 0..<(AdaptiveKeys.minSamples - 1) { keys.hit("a", dx: 0.2, dy: -0.1) }
        XCTAssertEqual(keys.offset("a").dx, 0); XCTAssertEqual(keys.learnedKeys(), 0)
        keys.hit("A", dx: 0.2, dy: -0.1)
        XCTAssertEqual(keys.offset("a").dx, 0.2, accuracy: 0.0001); XCTAssertEqual(keys.offset("A").dy, -0.1, accuracy: 0.0001)
        XCTAssertEqual(keys.learnedKeys(), 1)
        XCTAssertEqual(keys.offset("b").dx, 0)
    }

    func testMaxShiftClampAndRejects() {
        let keys = AdaptiveKeys(store: MemoryPrefsStore())
        for _ in 0..<40 { keys.hit("k", dx: 1.2, dy: -1.2) }
        XCTAssertEqual(keys.offset("k").dx, AdaptiveKeys.maxShift); XCTAssertEqual(keys.offset("k").dy, -AdaptiveKeys.maxShift)
        keys.hit("z", dx: 1.6, dy: 0); keys.hit("z", dx: .nan, dy: 0); keys.hit("1", dx: 0.1, dy: 0.1)
        XCTAssertTrue(keys.aims()["z"] == nil); XCTAssertTrue(keys.aims()["1"] == nil)
    }

    func testMistakeWeight() {
        let keys = AdaptiveKeys(store: MemoryPrefsStore())
        keys.mistake(pressed: "W", meant: "e", dx: -0.4, dy: 0.1, x: 0.25, y: 0.1)
        XCTAssertEqual(keys.aims()["e"]?.n, AdaptiveKeys.mistakeWeight)
        XCTAssertEqual(keys.aims()["e"]!.dx, -0.4, accuracy: 0.0001)
        XCTAssertEqual(keys.mistakes, [AdaptiveKeys.Mistake(x: 0.25, y: 0.1, pressed: "w", meant: "e")])
        // Five mistakes are fifteen touches: enough for the aim to count.
        for _ in 0..<4 { keys.mistake(pressed: "w", meant: "e", dx: -0.4, dy: 0.1, x: 0.25, y: 0.1) }
        XCTAssertEqual(keys.offset("e").dx, -AdaptiveKeys.maxShift)
        for _ in 0..<500 { keys.mistake(pressed: "w", meant: "e", dx: 0, dy: 0, x: 0, y: 0) }
        XCTAssertEqual(keys.mistakes.count, AdaptiveKeys.maxMistakes)
    }

    func testRunningMean() {
        let keys = AdaptiveKeys(store: MemoryPrefsStore())
        keys.hit("a", dx: 0.3, dy: 0); keys.hit("a", dx: 0.1, dy: 0)
        XCTAssertEqual(keys.aims()["a"]!.dx, 0.2, accuracy: 0.0001)
    }

    func testSaveLoadRoundTrip() {
        let store = MemoryPrefsStore()
        let keys = AdaptiveKeys(store: store)
        keys.save()
        XCTAssertNil(store.object(forKey: AdaptiveKeys.storeKey))   // nothing changed, nothing written
        for _ in 0..<20 { keys.hit("a", dx: 0.2, dy: -0.1) }
        keys.mistake(pressed: "s", meant: "a", dx: 0.5, dy: 0, x: 0.1, y: 0.5)
        keys.save()
        let json = store.object(forKey: AdaptiveKeys.storeKey) as? String
        XCTAssertNotNil(json)
        let again = AdaptiveKeys(store: store)
        XCTAssertEqual(again.aims()["a"]?.n, 23)
        XCTAssertEqual(again.aims()["a"]!.dx, keys.aims()["a"]!.dx, accuracy: 0.000001)
        XCTAssertEqual(again.offset("a").dy, keys.offset("a").dy, accuracy: 0.000001)
        XCTAssertEqual(again.mistakes, keys.mistakes)
        // Android's JSON reads as it is.
        let android = MemoryPrefsStore([AdaptiveKeys.storeKey: "{\"aims\":{\"q\":[0.25,-0.5,30]},\"mistakes\":[[0.1,0.2,\"w\",\"q\"]]}"])
        let ported = AdaptiveKeys(store: android)
        XCTAssertEqual(ported.offset("q").dx, 0.25); XCTAssertEqual(ported.offset("q").dy, -AdaptiveKeys.maxShift)
        XCTAssertEqual(ported.mistakes.first?.meant, "q")
        again.reset()
        XCTAssertNil(store.object(forKey: AdaptiveKeys.storeKey)); XCTAssertTrue(again.aims().isEmpty)
    }

    func testImports() {
        let keys = AdaptiveKeys(store: MemoryPrefsStore())
        keys.importAim("a", dx: 0.3, dy: 0.1, samples: 50)
        keys.importAim("a", dx: 0.1, dy: 0.1, samples: 50)
        XCTAssertEqual(keys.aims()["a"]!.dx, 0.2, accuracy: 0.0001); XCTAssertEqual(keys.aims()["a"]?.n, 100)
        keys.importSlip(pressed: "w", meant: "e", count: 2, x: 0.3, y: 2)
        XCTAssertEqual(keys.aims()["e"]?.n, 6); XCTAssertEqual(keys.aims()["e"]!.dx, -0.6, accuracy: 0.0001)
        XCTAssertEqual(keys.mistakes.count, 2); XCTAssertEqual(keys.mistakes[0].y, 1)
        keys.importSlip(pressed: "1", meant: "e", count: 2, x: nil, y: nil)
        XCTAssertEqual(keys.aims()["e"]?.n, 6)
    }
}

final class ClipHistoryTests: XCTestCase {
    func testRecordOrderDedupeAndLimits() {
        let store = MemoryPrefsStore()
        let clips = ClipHistory(store: store)
        XCTAssertTrue(clips.all().isEmpty)
        XCTAssertTrue(clips.record("  one  ", now: Date(timeIntervalSince1970: 1)))
        XCTAssertTrue(clips.record("two", now: Date(timeIntervalSince1970: 2)))
        XCTAssertTrue(clips.record("one", now: Date(timeIntervalSince1970: 3)))
        XCTAssertEqual(clips.all().map { $0.text }, ["one", "two"])
        XCTAssertEqual(clips.all()[0].at, 3000); XCTAssertEqual(clips.fresh?.text, "one")
        XCTAssertFalse(clips.record("   ")); XCTAssertFalse(clips.record(String(repeating: "x", count: 5001)))
        XCTAssertFalse(clips.record("secret", sensitive: true))
        XCTAssertTrue(clips.record(String(repeating: "x", count: 5000)))
        for i in 0..<40 { clips.record("clip \(i)") }
        XCTAssertEqual(clips.all().count, ClipHistory.max); XCTAssertEqual(clips.all().first?.text, "clip 39")
        // A second reader of the same store sees the same, in Android's JSON.
        let again = ClipHistory(store: store)
        XCTAssertEqual(again.all(), clips.all()); XCTAssertNil(again.fresh)
        XCTAssertTrue((store.object(forKey: ClipHistory.storeKey) as? String)?.contains("\"t\":\"clip 39\"") ?? false)
        clips.remove("clip 39")
        XCTAssertEqual(clips.all().first?.text, "clip 38"); XCTAssertNil(clips.fresh)
        again.reload()
        XCTAssertEqual(again.all().first?.text, "clip 38")
        clips.clear()
        XCTAssertTrue(clips.all().isEmpty)
        let android = ClipHistory(store: MemoryPrefsStore([ClipHistory.storeKey: "[{\"t\":\"hi\",\"at\":1700000000000}]"]))
        XCTAssertEqual(android.all(), [ClipHistory.Clip(text: "hi", at: 1_700_000_000_000)])
    }
}
