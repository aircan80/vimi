# Parent prepared-file ownership checkpoint

Package-private WorldStreamPreparedFiles is a resource helper for the approved parent facade,
not a public transport or process launcher. It writes an exact256-byte immutable bootstrap and
creates the existing4,800,256-byte frame mapping inside one newly owned private directory.
Arguments identify those actual bytes, the current parent PID and a validated backend name.
Caller supplies the already bounded internal Session; the future public Spec facade will own
fresh random nonce generation. No game-source evidence is created here.

claimForLaunch irreversibly transfers cleanup capability BEFORE asynchronous start is scheduled.
The old preparation handle then cannot unmap/delete those files. Only the returned LaunchFiles
owner can initiate that cleanup; the forthcoming Session lifecycle must prove child exit and
all local file/frame-worker drain before doing so. This helper does not itself prove those
conditions or authorize arbiter release. Duplicate transfer/transfer after preparation close
is refused. Never-launched preparation cleanup is asynchronous/idempotent; cleanup failure is
memoized exceptionally rather than becoming success on another close call.

Mapping close and file deletion execute outside the short metadata monitor on one cleanup task.
Only the known bootstrap/mapping are deleted; unregistered directory contents are preserved and
reported as cleanup failure. Constructor failure owns and cleans its private directory/partial
known outputs before returning. No recursive deletion, external callbacks, source objects,
geometry/atlas clones or full-packet byte arrays are introduced.

Three authored tests exercise real fresh bootstrap/SHA/frame identity and immutable flags,
transfer followed by a held asynchronous-start task while the old preparation closes, refused
second transfer, never-launched close, and persistent cleanup failure preserving an unregistered
file. These are resource-helper tests, not actual Process/Starter/kill/arbiter qualification;
those remain the next lifecycle-owner checkpoint. Full rendering-bgfx check passes.
