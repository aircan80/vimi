# Reliable World status deadline oracle

`WorldStatusDeadline` is a pure metadata component for the reviewed status-pipe
watchdog contract. It reserves at most16 reliable statuses INCLUDING the status
currently being written. Idle pipes do not time out. Five seconds with outstanding
reliable messages and no completed reliable write requests orderly close once;
a further five seconds without ownership drain requests termination. Enqueueing,
partial writes or later progress after close do not postpone that drain deadline.
A drained owner disarms termination. Clock arithmetic supports signed nanoTime
wrap and rejects regressions over the normal bounded sampling interval.

Five deterministic fixtures cover idle/new work, enqueue starvation, completion
progress, exact deadlines, capacity/in-flight accounting, drain, clock wrap and
invalid completion. Full rendering-bgfx checks pass38tasks. This is an oracle,
not a running watchdog: the independent timer, bounded status writer, close action
and process-termination callback still need runtime integration and blocked-pipe
proof. It does not make existing production watchdogs status-aware by itself.

Independent review approves99b87e7 as a pure oracle; all five focused fixtures
executed separately. Runtime overloads now sample System.nanoTime INSIDE the
short oracle monitor, preventing concurrent writer/watchdog timestamps from
arriving out of call order. Explicit-time overloads are deterministic ordered-time
seams; runtime must use the live-clock overloads. No IO or user callback runs
under the oracle lock. Runtime transport/watchdog proof remains pending.
