## ADDED Requirements

### Requirement: Recovery remains locked to the original rider in the final matrix
The KUM-36 matrix SHALL model A connected to B, B becoming unavailable, C
responding first, and B returning. A SHALL retain B's immutable TargetLock,
attempt deadline, and visible recovery identity; C SHALL never own signaling,
media, persistence, or a replacement attempt.

#### Scenario: Third node responds before the target
- **WHEN** C is discovered or presents a control channel before B during A's
  target-locked recovery
- **THEN** C is rejected and cleaned while the current attempt, TargetLock,
  deadline, failure streak, and displayed rider remain bound to B

#### Scenario: Original target returns inside the fast window
- **WHEN** B becomes ready before T+3 on the preferred transport
- **THEN** B becomes the only media owner and the queued fallback is inert

#### Scenario: Original target requires fallback
- **WHEN** B is not ready until the T+3 fallback boundary
- **THEN** only the approved alternate transport opens, the T+10 total deadline is
  unchanged, and at most one B media owner can commit

### Requirement: Repeated final failures reset exactly once
Three complete same-target recovery failures SHALL enter visible `RESETTING`
exactly once, clear attempt/channel/pending ownership, execute the approved
wireless cleanup order, reject stale events, and return the same runtime to
`DISCOVERING` only for the exact failed-attempt reset completion.

#### Scenario: Three deadlines expire
- **WHEN** three target-locked recovery attempts reach their immutable deadlines
- **THEN** the first two create bounded retries with preserved TargetLock and the
  third emits one exact wireless-reset effect

#### Scenario: Stale callback races reset
- **WHEN** an older transport, channel, timeout, or reset-completion callback
  arrives during `RESETTING`
- **THEN** it cannot restore an attempt, switch target, claim media, increment the
  streak, or complete reset

#### Scenario: Exact reset completes
- **WHEN** cleanup completes for the exact exhausted attempt
- **THEN** the same runtime returns to idle discovery with no stale targeted
  Socket/group/task or duplicate media owner

### Requirement: User termination and runtime resources remain bounded
The final matrix SHALL prove that user cancellation stops recovery without
counting a final failure, active disconnect retains the online runtime, full Stop
owns complete teardown, and process restart creates no stale runtime takeover.

#### Scenario: User cancels recovery
- **WHEN** the user disconnects the exact recovering attempt
- **THEN** cancellation wins over queued timeout/failure events and returns the
  same runtime to `DISCOVERING`

#### Scenario: Process restarts after teardown
- **WHEN** the emulator process is force-stopped and relaunched
- **THEN** a new process starts successfully without relying on a stale attempt,
  control channel, media owner, or scheduled task

### Requirement: Synthetic audio evidence stays test-only and single-stream
The KUM-36 automated matrix SHALL verify deterministic PCM frame count, RMS,
dominant frequency, loss rate, first-frame latency, pause/recovery continuity,
single-stream enforcement, and no frames after stop. Synthetic source and sink
code SHALL remain under `androidTest` only.

#### Scenario: Synthetic stream recovers
- **WHEN** the test stream pauses for a simulated recovery gap and resumes
- **THEN** the same stream continues with bounded metrics and no second stream

#### Scenario: Stream stops
- **WHEN** the test sink is stopped
- **THEN** all later frames are rejected and no production source set contains the
  synthetic source or sink

### Requirement: Automated and physical evidence remain distinct
KUM-36 SHALL pass focused/full JVM, lint, debug/test APK, three-emulator focused
and full matrices, exact-Head CI, and fixed-SHA review with P0=0/P1=0. Hardware
checks unavailable during development SHALL remain explicitly deferred.

#### Scenario: Automated Sprint 4 gate passes
- **WHEN** deterministic, instrumentation, shared-network, fault/recovery,
  restart, evidence-scan, CI, Git, PR, Linear, and architecture records agree
- **THEN** KUM-36 and Sprint 4 may complete without connected physical devices

#### Scenario: Hardware checks are not run
- **WHEN** OEM Wi-Fi Direct/RF/background, Bluetooth SCO, real acoustics, power,
  thermal, or long-duration survival is unavailable
- **THEN** each row remains `DEFERRED_TO_RELEASE_CANDIDATE` and is not represented
  as passed by emulator or fake evidence
