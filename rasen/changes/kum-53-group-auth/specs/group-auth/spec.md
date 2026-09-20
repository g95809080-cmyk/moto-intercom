## ADDED Requirements

### Requirement: Complete password confirmation
The implementation SHALL use all three J-PAKE rounds, bind room and endpoint context, and permit exactly one secure channel export only after peer key confirmation. It MUST reject expired, cancelled, malformed or out-of-order exchanges.

#### Scenario: Incorrect code
- **WHEN** two participants use different six-digit codes
- **THEN** confirmation fails and no channel or admission proof is returned

#### Scenario: Different room context
- **WHEN** a proof is replayed under a different room/runtime/handshake context
- **THEN** verification fails closed

### Requirement: Bounded directional encryption
The channel SHALL use distinct directional keys and monotonically unique nonces. It MUST reject tampering, replay, oversized messages and use after close.

#### Scenario: Replay
- **WHEN** a valid encrypted message is received a second time
- **THEN** the channel closes without releasing plaintext
