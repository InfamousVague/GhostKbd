import UIKit
import AudioToolbox

/// The keyboard's own input view: iOS plays its keyboard click only for a view that says it wants one.
final class GhostInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

/// A key's haptic and sound, within what iOS allows a keyboard: the click is the system's own (it follows the
/// phone's Keyboard Clicks switch and its silent switch), and haptics work only with Full Access.
final class Feedback {
    private var generators: [String: UIImpactFeedbackGenerator] = [:]

    private func generator(_ strength: String) -> UIImpactFeedbackGenerator {
        if let g = generators[strength] { return g }
        let style: UIImpactFeedbackGenerator.FeedbackStyle = strength == "light" ? .light : strength == "strong" ? .heavy : .medium
        let g = UIImpactFeedbackGenerator(style: style)
        generators[strength] = g
        return g
    }

    func key(_ type: KeyType, haptic: Bool, vibrate: Bool, strength: String, sound: Bool, fullAccess: Bool) {
        if haptic && vibrate && fullAccess {
            let g = generator(strength)
            g.impactOccurred()
            g.prepare()
        }
        if sound {
            if fullAccess {
                // iOS's own three keyboard sounds: a letter, delete, and the modifiers (space, return, shift).
                let id: SystemSoundID = type == .delete ? 1155 : (type == .char ? 1104 : 1156)
                AudioServicesPlaySystemSound(id)
            } else {
                UIDevice.current.playInputClick()
            }
        }
    }
}
