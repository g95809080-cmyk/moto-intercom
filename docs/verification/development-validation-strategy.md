# MotoIntercom Development Validation Strategy

Status: Active from 2026-07-19

## Development gate order

1. Deterministic JVM tests.
2. Fake monotonic clock, transports, callbacks, and resource ownership.
3. Single-emulator Android instrumentation.
4. Two-to-three emulator shared-network integration.
5. Gradle/CI gates and fixed-SHA architecture review.

Development does not require a physical Android device. Missing hardware must
not reduce deterministic coverage or be replaced by a human assertion.

## Evidence vocabulary

- `PASS`: the named automated or emulator check ran at the bound revision and
  met its assertions.
- `FAIL`: the named check ran and violated an assertion or bounded timeout.
- `NOT_RUN`: the check did not execute.
- `DEFERRED_TO_RELEASE_CANDIDATE`: the check requires real hardware or human
  perception and remains mandatory before production release.

Deferred evidence is never called passed. It does not block an intermediate
checkpoint, Issue, PR, or Sprint after all applicable automated and emulator
gates pass.

## Emulator evidence boundary

Android Emulator 36.5+ shared networking is used for LAN reachability,
instrumentation, process lifecycle, deterministic network delay/offline
recovery, and synthetic PCM transfer. Emulator instances use explicit ADB
ports and `-shared-net-id`; scripts resolve each node's actual shared `wlan0`
address and never mix in a physical ADB serial implicitly.

Wi-Fi Direct group formation and OEM-specific behavior may be unavailable or
non-deterministic in the emulator. The project validates their state, deadline,
callback, and cleanup contracts with fakes and records the hardware behavior in
the Release Candidate queue.

Default local sequence:

```powershell
.\scripts\emulator\check-version.ps1
.\scripts\emulator\start-cluster.ps1 -Count 3
.\gradlew.bat assembleDebug assembleDebugAndroidTest
.\scripts\emulator\install-cluster.ps1
.\scripts\emulator\run-scenario.ps1 -Scenario all
.\scripts\emulator\collect-results.ps1
.\scripts\emulator\stop-cluster.ps1
```

## Test-only audio

`SyntheticAudioSource` and `TestAudioSink` live under `app/src/androidTest`.
They verify frame count, RMS, dominant frequency, sequence loss, first-frame
latency, recovery, exactly-one active stream, and stop behavior. They do not use
the real microphone, speaker, Bluetooth SCO, WebRTC acoustic processing, or
release runtime path.

## Intermediate merge gate

An Issue PR may become Ready and merge with a merge commit after:

- all applicable deterministic and emulator scenarios pass;
- CI passes;
- architecture review is APPROVED with P0=0 and P1=0;
- the working tree and remote are synchronized;
- Linear and PR evidence are complete; and
- all physical-only rows are explicitly deferred with a final procedure.

Remote branches are retained. Force push, production signing, deployment, and
store release are forbidden.
