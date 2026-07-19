# kum-33-three-second-recovery-fallback

KUM-33: restore the original target on the last successful transport for an immutable three-second fast window, then attempt the alternate transport without changing target, attempt identity, total deadline, or ownership.

Execution contract: bounded full-feature-equivalent flow with one write worker,
read-only architecture review, deterministic JVM and emulator verification, no
auto-decompose, no automatic deployment, and merge only after the approved
intermediate gate.
