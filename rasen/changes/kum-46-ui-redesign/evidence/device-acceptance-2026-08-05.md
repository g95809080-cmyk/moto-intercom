# KUM-46 physical-device acceptance

Date: 2026-08-05

APK under test: `app-debug.apk`, version `1.0` / versionCode `1`, SHA-256 `847bdb27904b0a288c80017327a9af92290c81d281677db5b41539510bd11ef1`.

## Devices

| Serial | Model | Android SDK | APK install/hash | TalkBack precondition |
| --- | --- | ---: | --- | --- |
| `9688fa60` | MI 6 | 28 | Installed successfully; exact APK hash matched | TalkBack package installed, `accessibility_enabled=0`, no enabled accessibility service |
| `efcb9031` | 2211133C | 36 | Installed successfully; exact APK hash matched | TalkBack package installed, but only the automatic-clicker accessibility service was enabled |

## Results

Correction: the initial rows below reflect the first incomplete Discover inspection. The corrected secondary-page rerun in `Corrected secondary-page connection rerun` supersedes the initial failed/blocked Connect rows for the paired-card path.

| Acceptance item | Result | Evidence and boundary |
| --- | --- | --- |
| Launch current UI on both devices | Pass | Both devices resumed `MainActivity`; current multi-page XML UI was visible after the Splash transition. |
| Start from Offline | Pass | Both devices entered `Offline -> Discovering` after tapping the real Home primary action. |
| Physical MotoCom discovery | Partial / failed at UI boundary | Both devices published the MotoCom service. Logcat recorded P2P service-instance receipt and mutual `MotoCom v2` identity acceptance. The UI status reached `已发现车友`, but no `RiderPresence` card was exposed. See `device-9688-discovery.log` and `device-efcb-discovery.log`. |
| Discover page with selectable rider and Connect | Failed / blocked | `device-9688-route.png` and `device-efcb-discover.png` show the current Discover page with the truthful empty state and no selectable card/Connect action. No fake target or hidden direct-connect action was used. |
| Stop and cleanup | Pass with platform warnings | Both devices completed `Discovering -> Stopping -> Offline`; `status running=false`, `clearServiceRequests success`, `clearLocalServices success`, and `close cleanup finished` were recorded. Android Wi-Fi P2P returned transient `busy` warnings during cleanup, but the final Offline state was reached. See `device-9688-acceptance.log`, `device-efcb-acceptance.log`, `device-9688-stop.png`, and `device-efcb-stop-final.png`. |
| Incoming confirmation | Blocked | No selectable connection was available, so no real incoming request could be generated. No dialog was injected. |
| Connected WebRTC/audio state | Blocked | No real connection was established; both devices remained truthful `未连接`/standby after Stop. |
| Manual listening | Not run / blocked | Requires a real connected audio session and human listening; neither prerequisite was available. |
| TalkBack runtime | Not run | TalkBack was not the active service on either device. UI-tree labels and content descriptions were still inspected as supporting evidence, but this is not a TalkBack pass. |
| Source changes during acceptance | None | This run changed only generated evidence artifacts; no production or test source file was edited. |

## Conclusion

The real-device run proves install parity, current UI launch, physical MotoCom service discovery/identity exchange, and truthful Start/Stop cleanup on two Android versions. It does not prove the complete two-device product flow because the transport-layer discovery result is not surfaced as a selectable UI Presence candidate. The next engineering gate is to trace why accepted P2P/NSD identities do not reach `RiderPresence`/`onPresencesChanged`; only after that can Connect, incoming confirmation, and manual listening be rerun.

## Corrected secondary-page connection rerun

The initial empty Discover result above was incomplete: the candidate was on the lower part of the Discover secondary page. `device-9688-discover-paired.png`/`.xml` show the real paired card `22 / 2211133C / Wi-Fi Direct / 已配对` with a `连接` button.

The real rerun produced:

- `9688fa60` (MI 6) initiated a `USER` connection.
- `efcb9031` (2211133C) received an `INBOUND` connection.
- Wi-Fi Direct group validation passed; signaling ANSWER/CANDIDATE frames were exchanged.
- Both WebRTC and ICE reached `CONNECTED`; both microphone capture and speaker playout started.
- `device-9688-connected.png` and `device-efcb-connected.png` show the Connected UI.
- The first primary action disconnected the current peer and returned both sessions to Discovering. A second Stop action completed `Discovering -> Stopping -> Offline` on both and released audio resources/services.

This supersedes the earlier failed/blocked result for the paired-card path. Because the target was already paired, it auto-accepted as `INBOUND`; an unpaired incoming-confirmation dialog run is still not represented. Human listening remains an operator task and is not claimed from logcat alone.
