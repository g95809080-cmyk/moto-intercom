## ADDED Requirements

### Requirement: Background cannot hold VOX open indefinitely
Background estimation SHALL not freeze solely because VOX is OPEN or HANGOVER. Stable background alone SHALL not indefinitely renew the hangover. Sensitivity SHALL affect actual opening and closing decisions without imposing a maximum speech duration.

This change recognizes a continuous stationary plateau after an accepted foreground energy peak falls substantially, within a bounded distance of the learned floor. It SHALL NOT claim semantic speech/noise discrimination: sudden steady noise without a falling foreground and soft sustained speech with indistinguishable energy remain listening-validation limitations.

#### Scenario: Speech returns to a stronger stable background
- **WHEN** accepted speech stops and the remaining stable background exceeds the old closing threshold
- **THEN** background learning and finite hangover return to LISTENING, while new and continuous speech remain detectable.

#### Scenario: Pulse or capture gap cannot establish a background window
- **WHEN** a brief high-energy pulse, a capture gap, reversed time, or a new foreground segment is observed
- **THEN** unaccepted pulse peaks and discontinuous/old windows cannot relabel the current foreground; learned floor is retained.

### Requirement: Native capture and control ownership remain authoritative
Capture SHALL continue while VOX or manual mute silences transmitted PCM. Old producer, gate revision, settings revision or closed-engine callbacks SHALL not reopen transmission or mutate replacement detector state.

#### Scenario: Old capture arrives after pause and replacement
- **WHEN** a delayed old producer returns after a new recorder becomes current
- **THEN** its frame is silenced and cannot change the replacement detector or current mute decision.

### Requirement: Noise processing must be verified on actual decoded output
NS/AGC changes SHALL be supported by same-input native capture-to-decoded-output measurements in the pinned SDK. AEC, duplex and media mixing SHALL be preserved. Synthetic metrics SHALL not be presented as real-road intelligibility or complete horn removal.

#### Scenario: Compare processing combinations
- **WHEN** deterministic identical noise and modulated voice-like PCM traverse NS/AGC on/off combinations
- **THEN** actual decoded samples and measurable processing results are recorded, with physical listening separately deferred.
