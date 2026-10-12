## ADDED Requirements

### Requirement: Preserve an accepted native owner during bounded transient loss
An accepted connected media owner with a live verified control channel SHALL retain its PeerConnection for a two-second DISCONNECTED observation. An entry-time CONNECTED receipt from that same native source SHALL cancel the observation without a new attempt, pairing write, or terminal SUCCESS.

#### Scenario: Native self-heal delivered after the observation timer
- **WHEN** that same native source captures CONNECTED within the observation window, while RTC/Main delivery is delayed
- **THEN** the final timer claim preserves the connected owner and cannot discard the source receipt.

### Requirement: Recovery budgets and authorization start at the original producer loss
The first recovery deadline SHALL be firstLossElapsed + 10 seconds, and fallback SHALL be firstLossElapsed + 3 seconds. Observation, delivery delay and repeated notifications SHALL NOT refresh time or reacquire adoption authorization.

#### Scenario: Repeated loss followed by actual owner EOF
- **WHEN** a repeated DISCONNECTED is followed by the selected control Socket closing
- **THEN** formal recovery starts once with the original deadline; non-owner sibling failure does not end the observation.

### Requirement: Serialize native group removal before replacement transport work
An outstanding removeGroup on the same manager/channel lifecycle SHALL prevent a replacement attempt from issuing connect, validating a new group, or opening a Socket until completion is acknowledged. Old receipts SHALL NOT mutate the replacement target or extend its deadline.

#### Scenario: New discovery while the old removal callback is held
- **WHEN** a fresh matching discovery arrives for the replacement attempt before old removeGroup completes
- **THEN** no SDK connect is issued; after completion only the still-authorized current attempt may run.
