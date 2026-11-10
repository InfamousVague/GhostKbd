import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    // The keyboard's settings, over the same channel Android's MainActivity answers (SettingsChannel.swift).
    let registrar = engineBridge.applicationRegistrar
    SettingsChannel.shared.attach(messenger: registrar.messenger(), lookupAsset: { FlutterDartProject.lookupKey(forAsset: $0) })
  }

  override func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
    SettingsChannel.shared.open(url)
    return true
  }
}
