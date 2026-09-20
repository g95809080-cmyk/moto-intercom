## ADDED Requirements
### Requirement: Bounded pre-IP message channel
BLE transport SHALL advertise only an independent service UUID and SHALL bound peers, attempts, fragments, pending requests and lifetime.
#### Scenario: Oversized or stale traffic
- **WHEN** fragment length/order is invalid or owner is closed
- **THEN** transport rejects traffic without application delivery and frees connection state.
#### Scenario: Multiple candidates
- **WHEN** several advertisers exist during the bounded window
- **THEN** transport returns up to sixteen distinct candidates and does not pick the first as an authenticated room.
### Requirement: Owned offline Wi-Fi network
Network adapter SHALL create a host GO or attach a client to supplied SSID and SHALL return only a validated local Network; credentials SHALL be redacted in diagnostics.
#### Scenario: Cancellation
- **WHEN** a request is cancelled or times out
- **THEN** old callbacks cannot publish success, and only owned network resources are released.
#### Scenario: Existing host group
- **WHEN** a P2P group already exists before host startup
- **THEN** adapter fails without deleting that group.
