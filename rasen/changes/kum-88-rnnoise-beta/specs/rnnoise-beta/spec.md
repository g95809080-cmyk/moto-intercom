## ADDED Requirements

### Requirement: Real native noise reduction
The Beta SHALL process authorized outgoing PCM with the pinned RNNoise model when enabled and SHALL disable WebRTC software NS in this mode while retaining AEC/AGC.

#### Scenario: Selected RNNoise captures valid PCM
- **WHEN** a current producer supplies one PCM16 mono 48 kHz 480-sample frame with the transmit gate open
- **THEN** RNNoise modifies the actual native input buffer before encoding.

#### Scenario: Unsupported capture format
- **WHEN** the RNNoise callback receives a different actual format
- **THEN** it clears outgoing PCM and reports an error rather than claiming RNNoise processed it.

### Requirement: History and ownership isolation
The system MUST reject stale producers/revisions before advancing the model, and MUST clear history across mute, VOX close, interruption and new ownership.

#### Scenario: Muted stream resumes
- **WHEN** a stream is muted and later unmuted
- **THEN** suppressed speech from the previous stream cannot appear in resumed model output.

#### Scenario: Revoked producer returns
- **WHEN** an old callback resumes after revocation
- **THEN** it clears its buffer and cannot mutate the current RNNoise state.

### Requirement: Offline comparison and independent installation
The Beta SHALL persist the selected mode, allow changes only while Offline, apply it to the next runtime, and install independently with a clear Beta identity.

#### Scenario: User selects WebRTC and restarts
- **WHEN** the user stops MotoCom, disables RNNoise and starts again
- **THEN** the new source uses WebRTC NS without invoking RNNoise.

#### Scenario: Compare during a session
- **WHEN** MotoCom is online
- **THEN** the switch is disabled and the active source's mode remains fixed.
