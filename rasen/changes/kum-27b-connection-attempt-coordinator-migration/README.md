# kum-27b-connection-attempt-coordinator-migration

KUM-27B bounded migration checkpoints; B1 and B2 are complete. B3 atomically
moves immutable total-deadline ownership into the existing Coordinator and
separates pending inbound confirmation from a live connection attempt.
