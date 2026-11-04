# Parent child/worker lifecycle checkpoint

Package-private WorldStreamLaunchOwner composes the prepared-file transfer helper, a provided
Starter, and a bounded attachment/worker-registration phase. It is not the public transport,
status/command/frame loop, source producer, startup-ready timer, or GUI/global arbiter adapter.
The existing application arbiter must still own the sole process permit until a successful
private resource-drain receipt. No initialization/pixel/source success is implied here.

Files transfer before the asynchronous Starter is scheduled. A successful Process return is
stored before onExit/attachment setup; any subsequent error still leaves that child owned.
A blocked Starter keeps files/ownership because it may yet return a child. Closing the old
preparation object cannot clean them. Starter must return every created handle or throw with
no child; callbacks locally clean any unreturned resources. Attachment failure is separately
covered after a returned child. New worker registration closes on stop or attachment finally;
at most six workers can register, each before scheduling. A worker's done bit is set only by its
actual finally, never by canceling a future.

closeAsync is a prompt metadata request, with one independent deadline task and one off-caller
close owner. The bounded result may fail while actual terminationAndDrain stays pending. Stop
signals use ProcessHandle instead of Process.destroy, which can synchronously close stream
monitors. The close owner requires actual Process.onExit completion, agrees that the child is
no longer alive, then waits for Starter/attachment and all registered worker finally flags.
Only then does it close parent pipes and request mapping/file cleanup. A still-alive or
unobserved child, blocked worker or blocked stream-close keeps files and cannot mint a receipt.
A late actual exit reuses the same drain owner; it does not launch another child or close task.

Failures retain detached text at most256 characters, not caller Throwable/cause/source objects.
Drain receipts retain only pid/exit scalars, never Process/pipes. A no-child failure has both
optionals empty; a returned child has both present only after actual drain. Cleanup failures
remain exceptional. Caller-canceled copied futures cannot create actual drain or cancel it.
The helper's provided Starter must return standard supported Process/ProcessHandle ownership;
unknown process/exit observation fails closed, with no cleanup/release success inferred.

Six tests execute held Starter plus old-preparation close, late child after bounded timeout,
no-child start and returned-child attachment failures, local unreturned-output try/finally,
held worker finally then independently blocked stream-close, unkillable fake then late exit,
canceled future copies, and fixed six-worker registration. One test starts a genuine authored
Java-only waiting child, reads READY in a registered worker, signals through its real handle,
and proves actual exit/worker EOF/file removal. It uses no GPU, application stores or network.
Removing only worker-drain waiting causally fails the held-stream assertion (close was called
while worker still held ownership); restored full rendering-bgfx check passes.

These are lifetime/resource-helper tests. Reliable status association, frame polling, pipe
backpressure in the parent, source admission, public API, shared application arbiter and real
World v2 child transport integration remain later checkpoints. No FPS or cross-platform native
renderer qualification follows from a Java-only subprocess.
