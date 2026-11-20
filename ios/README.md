# SmokeLog for iPhone

The iPhone version of SmokeLog: same features as the Android app, built with SwiftUI and Swift Charts. Requires iOS 17 or newer.

## Build and install on your iPhone

You need a Mac with **Xcode 16 or newer** (free from the Mac App Store) and a free Apple ID.

1. Open `ios/SmokeLog.xcodeproj` in Xcode.
2. Select the **SmokeLog** target → **Signing & Capabilities**, tick **Automatically manage signing**, and choose your **Team** (your Apple ID; add it under Xcode → Settings → Accounts if needed). If Xcode says the bundle identifier is taken, change `com.smokelog.ios` to something unique, such as `com.yourname.smokelog`.
3. Plug in your iPhone, unlock it, and tap **Trust** when asked. On iOS 16+ turn on **Settings → Privacy & Security → Developer Mode** (the phone restarts).
4. Pick your iPhone as the run destination at the top of Xcode and press **Run** (⌘R).
5. The first time, open **Settings → General → VPN & Device Management** on the phone, tap your Apple ID and **Trust** it, then open SmokeLog.

With a free Apple ID the app must be reinstalled from Xcode every 7 days. A paid Apple Developer account removes that limit and allows TestFlight.

To change the project layout, edit `project.yml` and run `xcodegen generate` ([XcodeGen](https://github.com/yonaskolb/XcodeGen)).

## Differences from Android

- **Recording keeps the screen on.** iOS doesn't let apps hold a network connection open in the background, so SmokeLog records live Traeger temperatures while it's open and keeps the screen awake during recording. For a long cook, leave the phone on a charger with SmokeLog open.
- **Claude app hand-off uses the share sheet.** Tap **Ask Claude**, pick **Claude** in the share sheet and send, then copy Claude's reply and tap **Paste** in SmokeLog.
- **Dictation** uses the microphone key on the iOS keyboard.
- Data is stored as one JSON file per cook in the app's Application Support folder; credentials are in the iOS Keychain.

Cook files are the same format on both platforms, so a cook shared from Android opens on iPhone and vice versa.

## Layout

```
SmokeLog/
  App/        app entry point, iOS-only helpers
  Model/      cook data, meat catalog, formatting
  Services/   storage, settings, Traeger client (with a small MQTT-over-WebSocket client), recorder, sharing, AI
  Views/      SwiftUI screens and the temperature chart
```
