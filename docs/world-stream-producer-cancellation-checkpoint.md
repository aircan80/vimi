# Parent source-cancellation request lane checkpoint

Package-private WorldStreamProducerCancellation implements the reviewed PacketDestination
registration semantics only. It does not create the public transport/destination, source
factory, file MOVE, protocol or GUI. Full Session must run this as its sixth fixed worker and
connect its stop flag; no per-registration watcher or callback thread is created.

Both Producer.stop (per tile) and stopSession/lifecycle-owner stopping schedule the same action
at most once. One active producer permits exactly one registration, including after an earlier
registration was sealed. Escaped/finished producer registrations reject. Already-stopping
registrations still dispatch asynchronously. Scheduling/sealing are metadata-only; work invokes
the cancellation request outside all locks. Sealing before scheduling drops the callback;
already scheduled/running callbacks still execute and clear their source closure only in
actual finally. Producer completion and callback completion are independent. No replacement
producer can start before both retire, even if registration.close and producer.finished already
ran. The lane observes the configured lifecycle owner's cheap stop flag during its own bounded
20ms metadata wait, outside locks, so unexpected child/session close also cancels blocked source
work without a seventh watcher.

The action is the operation-local source Cancellation.cancel request, never a join on producer
or parent drain. Callback failure becomes a detached bounded failure/stopped lane, not material
fallback or permission for another producer. Full Session must propagate this failure into its
own terminal state; the lifecycle owner still waits the producer's actual worker finally.

Six authored tests cover per-tile already-stop, session already-stop, repeated request coalescing,
scheduled-even-sealed versus seal-before-schedule, duplicate/escaped registration, callback
failure and producer reuse while a cancellation body is physically held. The integrated case
uses the real access.Cancellation mechanism with a held lower listener, the real parent lifecycle
worker wrapper and real temporary bootstrap/mapping/files. Closing only the lifecycle owner
(not manually stopping this lane) promptly triggers the off-caller token cancellation. Bounded
close fails while lower callback/producer are held; frame files remain, no new producer or
actual drain succeeds. Releasing the lower callback lets its real finally, producer finally,
child/worker/stream/file cleanup complete. No Future.cancel is used as a lower-drain signal.

Removing only the callback-completion condition from producer retirement makes the held-per-tile
assertion fail. Restored full rendering-bgfx check passes. This is not an authenticated BuildFiles
read, full public bridge or GPU process integration test, and it makes no native/FPS claim.
