# MotoIntercom

MotoIntercom is a one-to-one, offline-first motorcycle intercom for Android.
The app keeps product state in `SessionOrchestrator`; `IntercomService` owns
Android lifecycle, resources, and effects; the Activity and Compose screens
only render callbacks and dispatch user intent.

## Current release boundary

The current development target is v1.1.0. It includes the four in-app routes
(Home, Discover Riders, Settings, and Settings > Logs), real Presence selection,
incoming confirmation, audio/VOX settings, manual discovery refresh, bounded
session logs, automatic-reconnect preference semantics, and an in-app
help/feedback entry. Feedback opens an explicit system chooser and does not
attach logs, recordings, or URI data automatically.

LAN/Wi-Fi Direct, signaling, WebRTC, and Bluetooth behavior remain subject to
the existing product and device contracts. Two-device connection quality,
manual listening, hardware routing, RF behavior, battery/thermal behavior, and
release-device acceptance are not claimed by JVM or emulator tests.

## Build and test

This checkout is under a non-ASCII Windows path. The verified local build uses
the existing `N:` ASCII mapping and the bundled Android JDK:

```powershell
$env:JAVA_HOME = 'F:\Android\jbr'
N:
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

For a connected emulator with API 36:

```powershell
.\gradlew.bat connectedDebugAndroidTest --console=plain
```

Do not infer real-device or manual-listening acceptance from these commands.
The GitHub Actions workflow runs both the JVM/lint/build gate and an API 36
instrumentation job.

## Architecture boundaries

- `SessionOrchestrator` is the single product-state writer.
- `IntercomService` owns lifecycle, transport/audio resources, effects, and
  listener replay.
- `MainActivity` owns permissions, service binding, preferences, and system
  intents.
- Compose screens own presentation only and invoke callbacks supplied by the
  Activity/controller.
- Incoming actions are nonce/attempt/channel bound and use immutable
  `PendingIntent`s.

## Evidence policy

Automated test results, emulator UI trees, and screenshots are evidence for
their exact scope. They do not replace two-device, hardware, or human-listening
acceptance. Deferred checks remain explicitly marked `Not Run` or `Deferred` in
the product and Rasen evidence documents.
