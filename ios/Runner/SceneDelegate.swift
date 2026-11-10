import Flutter
import UIKit

class SceneDelegate: FlutterSceneDelegate {
  // ghostkbd://settings: the keyboard's cog opens the app here.
  override func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
    super.scene(scene, willConnectTo: session, options: connectionOptions)
    if let url = connectionOptions.urlContexts.first?.url { SettingsChannel.shared.open(url) }
  }

  override func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
    super.scene(scene, openURLContexts: URLContexts)
    if let url = URLContexts.first?.url { SettingsChannel.shared.open(url) }
  }
}
