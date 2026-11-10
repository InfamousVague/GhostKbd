/// Which phone the app is on. Ghost Keyboard began on Android, and everything reads as it does there unless a part
/// asks [isIOS]: on an iPhone or iPad the app is the containing app of a keyboard extension (ios/GhostKeyboard), which
/// answers the same `dev_keyboard/settings` channel (docs/IOS.md).
library;

import 'package:flutter/foundation.dart';

bool get isIOS => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;
