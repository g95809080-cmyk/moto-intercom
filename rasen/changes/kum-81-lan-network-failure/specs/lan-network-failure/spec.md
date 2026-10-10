## ADDED Requirements

### Requirement: Bind LAN opener to its Wi-Fi network
The LAN opener MUST bind its unconnected socket to a visible non-VPN Wi-Fi Network whose link address matches the local Wi-Fi IPv4 address before TCP connect. It MUST NOT change process-wide network binding.

#### Scenario: Cellular is default while Wi-Fi serves the peer
- **WHEN** cellular is the default network and the matching Wi-Fi network remains available
- **THEN** the actual LAN opener socket is bound to Wi-Fi before connect

#### Scenario: Wi-Fi is local-only or has no matching link
- **WHEN** the matching Wi-Fi has no INTERNET or VALIDATED capability
- **THEN** it remains usable for LAN
- **WHEN** no matching non-VPN Wi-Fi network exists
- **THEN** the LAN opener reports the exact attempt failure and closes its socket instead of using the default network

### Requirement: Report asynchronous failure for the owned attempt
LAN worker submission, binding, TCP and HELLO failures MUST report the original runtime/attempt/transport through the existing actor event. An attempt that failed MUST NOT be re-submitted by fresh discovery while its failure awaits Service delivery.

#### Scenario: Single LAN attempt fails before deadline
- **WHEN** the actual background LAN worker fails for the current attempt
- **THEN** Service forwards TargetedTransportOpenFailed and the actor records FAILED without waiting for the deadline
- **AND** the actual pending socket and lease are released

#### Scenario: Completion or delivery is late
- **WHEN** the runtime or attempt was closed or replaced before worker completion or queued Service delivery
- **THEN** that failure cannot end the replacement attempt or publish an error to its UI

#### Scenario: Other planned transport remains viable
- **WHEN** a current LAN branch fails while WIFI_DIRECT remains viable in the existing dual plan
- **THEN** the actor preserves the original target and deadline and allows the viable branch to continue

### Requirement: Distinguish diagnostic stages
Diagnostics MUST identify the original attempt, TCP destination, selected network and failure stage, and MUST log TCP listener readiness only after bind succeeded. Missing real-device evidence MUST remain recorded as unverified.
