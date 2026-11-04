# Metadata validity of checked terrain outputs

This narrow checkpoint builds on combined source de2f7f6 and cleanup follow-up
f7f1ae3. It adds no packet/public facade/GUI wiring or per-frame source IO.

WorldStreamScopes.isKnown is a synchronized metadata lookup: only this engine's
currently retained current OR replacement token qualifies. A closed engine, foreign
token, discarded replacement or old token after activation is unknown. It performs
no capture/stat/read/preparation. WrittenTile retains only its detached small Scope
token, not another proof/MAID owner. Its isOpen, new payload callback and existing
internal written-resource getter require that token to remain known as well as the
source/output validity gate. The public facade's upcoming resource-borrow/handoff
and controller's current-frame gate will use this same metadata validity rule.

Activation can make an old output historical without releasing its base owner:
last-presented imagery/metadata may still be held until presentation retirement,
but new current-positive borrows fail. Existing entered geometry/atlas callbacks
remain pinned and read their already captured metadata/root/area/pixel references
through finally. They make no midcallback cache/source lookup. Output close still
releases the counted base claim only after real callback/resource cleanup.

Both initial and final withRecordedTile Map/WDT proof validation now invalidate the
associated registry proof on a noncanceled validation failure, preserving the
original failure. Cancellation preserves healthy current proof. Final WDT-positive
binding or WDT-observation failure also invalidates that scope, rather than escaping
before the final Map/WDT check and leaving it known. Other tile/material failures
do not claim the Map/WDT inventory itself changed. recorded uses the same proof
validation, including consistent actual WDT consumed length. Invalidating a failed
old proof never removes a different replacement/current token.

Authored real-native fixtures cover current+replacement both known, activation
retiring the old token, a held historical payload continuing after activation, and
new payload/resource access rejecting without any extra source stat/open/capture.
Initial and final Map stamp/WDT CK changes invalidate existing outputs; cancellation
keeps current usable and permits a fresh subsequent preparation. Historical atlas
claims stay counted until explicit owner retirement. Full concepts-world checks
pass97 tests; no frame-rate/native-renderer claim is made.

The later bridge proposal uses the exact planned parent type
WorldStreamTransport.StopRegistration. Its stopping request triggers for BOTH
reservation cancellation and Session stop, with coalesced off-caller callback/drain
rules already specified by the parent transport/controller design.
