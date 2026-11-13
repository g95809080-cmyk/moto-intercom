# KUM-46 non-device audit evidence (2026-08-10)

## Source and build

- Working branch: `feat/auto-reconnect-control`.
- Base for this follow-up: `32b185c4`.
- `JAVA_HOME=F:\Android\jbr`.
- Gradle commands ran from the existing ASCII `N:` mapping; no `clean` task
  was run.
- `testDebugUnitTest`: 66 suites, 529 tests, 0 failures, 0 errors, 0 skipped.
- `lintDebug`: 0 errors, 58 warnings.
- `assembleDebug`: pass.
- `assembleDebugAndroidTest`: pass.

## Instrumentation

- Command: `connectedDebugAndroidTest --console=plain`.
- Device: `MotoIntercom_Graphics_API36`, serial `emulator-5554`.
- Result: 18 tests, 16 passed, 2 skipped, 0 failures/errors.
- Skipped tests were `SharedNetworkNsdTest` and `SyntheticAudioNetworkTest`,
  whose shared-network preconditions are unavailable on this emulator.
- Crash buffer after the run was empty.

## Android CLI smoke evidence

- v1.1.0 debug APK deployed and `MainActivity` activated successfully.
- Layout tree: `tmp/android-cli/v110-layout.json`.
- Screenshot: `tmp/android-cli/v110-home.png`.
- The tree contains `home_scroll` and the expected MotoCom title; no crash or
  fatal exception was reported by this smoke command.

## Explicitly deferred

No populated two-device Presence/Connect, LAN/Wi-Fi Direct/WebRTC/Bluetooth
hardware session, RF/battery/thermal result, physical TalkBack result, visual
pixel judgment, or manual listening result is claimed. Those remain
`Deferred / Not Run`.
