## ADDED Requirements

### Requirement: Same-target recovery glare converges deterministically

The Coordinator SHALL use canonical wire-request ordering when both peers
recover the same locked target simultaneously, keep one winning attempt,
reject the losing attempt, and start at most one media path.

#### Scenario: Both peers open recovery signaling

- **WHEN** opposite recovery requests for the same complete `TargetLock` meet
- **THEN** both peers choose the same winner and stale loser callbacks cannot
  start a second media path

### Requirement: First and second retries reuse eligible discovery adapters

For a delayed first or second recovery retry, Service SHALL retain active
discovery adapters and rebind them to the fresh attempt only when runtime,
complete target, planned transport, and live immutable deadline match.

#### Scenario: Eligible adapter is rebound

- **WHEN** a fresh recovery attempt has the same runtime and `TargetLock`,
  includes the adapter transport, and remains before T+10
- **THEN** attempt-owned signaling and sockets are cleaned while discovery is
  retained and bound to the fresh attempt

#### Scenario: Reuse admission fails

- **WHEN** the adapter is absent or inactive, the runtime/target/transport does
  not match, or the deadline is exhausted
- **THEN** Service uses the complete cleanup path and cannot partially continue
  under mixed attempt ownership

#### Scenario: Old callback arrives after rebinding

- **WHEN** a callback carries the replaced attempt identity or generation
- **THEN** it cannot claim the fresh signaling/media path or destroy a retained
  same-target group

### Requirement: Retry timing and reset ownership remain unchanged

Adapter reuse SHALL NOT create, replace, or rebase an attempt. The bounded
1.5-second retry delay SHALL consume the fresh immutable T+10 budget, and the
third consecutive final failure SHALL execute the complete KUM-34 wireless
reset.

#### Scenario: Retry resumes after bounded delay

- **WHEN** an eligible adapter has been prepared for the fresh attempt
- **THEN** targeted work resumes after the bounded delay only if that exact
  attempt is still current and before its deadline

#### Scenario: Third final failure occurs

- **WHEN** the same target reaches its third consecutive complete final failure
- **THEN** no adapter is reused and the ordered full wireless reset/rebuild runs
