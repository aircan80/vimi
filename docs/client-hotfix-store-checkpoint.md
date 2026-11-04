# Private client hotfix import store

GUI-private ClientHotfixStore introduces no automatic discovery, remote hotfix
fetch, key logging, overlay policy or native DB2 authority. Constructor performs
no filesystem work. Import/open/disable mutations use one serial worker, with at
most8 accepted operations and8 registered snapshots. Scope pins actual BuildRef,
canonical locale and exact four-part version; scope identity includes product,
build/CDN config, region, locale and version. Numeric cache build must match.

An explicit user-selected original is read-only, regular and NOFOLLOW. Copying
uses64KiB scratch and a64MiB ceiling, with original file identity/length/mtime
rechecks. A complete private copy is parsed and whole SHA verified before any
binding publication. Source path is never persisted. Immutable content-addressed
imports use private owner-only POSIX0700 directories/0600 files where available;
Windows inherits the private application storage ACL. Symbolic link components
are refused. A lifetime store file lock prevents cooperating writers racing.

Each scope has an atomically replaced binding capped16KiB. Parsing, build
mismatch, original mutation, cancellation before the atomic commit and malformed
stored content preserve the prior binding. Content may remain unbound if a later
binding write fails; that retained private copy is never silently activated.
Disable clears only the binding and explicitly retains imported files. No purge
or modification of the user's game cache occurs.

Open returns null only for an unconfigured scope. A configured but unreadable or
invalid import fails explicitly. Every open re-parses the actual private file and
checks full SHA/count/length against the binding. Old snapshots remain immutable
through reimport. Snapshot.source is the exact literal source inspected by its
Index, preserving selected-body source/hash checks. Callers own the snapshot
through all table/binding work and close it separately from table handles.

Snapshot reads are worker-only synchronous positional file IO, returning a
completed/failed future. This avoids self-joining the serial store worker during
inspection. Read slices are capped64KiB and have at most8 actual read pins; no
monitor is held during IO. Close immediately rejects new reads and waits for
actual admitted pins before queuing channel retirement. Store close cancels and
drains accepted operations, attempts all snapshots and releases its file lock.
Cleanup errors stay sticky and prevent later healthy opens/reads. Future copies
do not transfer physical cancellation/deletion rights to observers.

The private Probe is only for authored lifecycle tests; production uses no-op
hooks. Tests use synthetic local caches, not acquired data: durable reopen,
locale isolation, import/disable retention, exact owner permissions, unchanged
original bytes, old/new snapshots after reimport, build mismatch/cancellation/
mutation preserving prior binding, bounded queues, actual held read/copy keeping
close pending, final symlink rejection, and attempt-all sticky cleanup errors.
The cleanup Error fixture runs after a real channel close; it is not a claim of
injected OS close failure. Symlink creation is skipped on Windows hosts lacking
that ordinary-user privilege.

Initial full GUI checks pass135 tasks (46 executed); final five-scenario focused
checks pass95 tasks. No JavaFX/GPU/network or game installation is required.
