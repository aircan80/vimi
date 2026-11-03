# Comparison core

Session-only, local comparison contracts and a bounded ordered merge engine for
#117. Files/table inventory adapters live in `inputs/`; dedicated application sessions
and local-only capability composition belong to the GUI application module. No persisted reports, semantic adapters, resume, SQL, acquisition,
or `GeneratedStore` publications are implemented here.

`ComparisonService(Path temporaryParent)` owns an isolated temporary directory and
accepted `ComparisonInput` adapters. `start(a,b)` accepts one ordered pair with
matching product, locale, namespace and scope. Adapters pin their descriptors and
return strictly ordered, unique canonical keys (for example unsigned IDs padded
to20decimal digits). Input pages carry the same immutable snapshot identity.
A null continuation means exhaustion; it does not mean complete coverage. Only
exhausted COMPLETE opposite coverage confirms added/removed membership. Partial,
limited, canceled and failed runs preserve typed uncertainty. Fields retain
independent semantics, state, aspect and evidence, so one known change can coexist
with unknown/protected fields.

The default output is findings-only. BOTH-present rows whose assessed fields are
all known and equal are counted as observed but not staged. Member-only, changed,
unknown and incompatible rows remain. Unassessed names/storage fields should be
excluded from the declared field scope rather than manufactured as per-row unknown
fields. No complete unchanged-row listing is provided. Evidence annotations are explanatory
and are not directly compared. An adapter that assesses a changed winning provider,
source fact, or interpretation must project it as an explicit field with the
EXTERNAL_EVIDENCE or INTERPRETATION aspect. An endpoint-only evidence identity
change does not imply a finding for every otherwise equal row. Progress `observed` counts
scanned identities; result offsets count staged findings independently.

Wait for `start` to drain, then call `page(offset,limit)` with at most100rows;
use returned `nextOffset`, since byte budgets may shorten a page. `progress()` is
an immutable current snapshot. `cancel()` cooperates with pending input operations.
`closeAsync()` cancels, drains accepted commands and input reads, closes adapters,
and removes staging. Inputs must drain their own work when closed and leave
borrowed build APIs open. The application owns dedicated endpoint sessions and
closes them after this core has drained.

Bounds: one merge worker, two simultaneous source reads, queue8, input256,
result100, canonical frame1MiB,32MiB comparison-buffer policy,512MiB staging and
5million observed identities. Oversized fields become LIMITED while small facts
survive; unrepresentable inventories/keys or an input-page budget limit terminate
with LIMITED coverage. Exhausted canonical pages are released before requesting or allocating their
replacement. Each retained input page reserves14MiB using conservative
string/object estimates; result-page work reserves16MiB, and sparse checkpoints
are stored once per100rows (at most50,000). These limits do not bound allocations
inside borrowed source APIs, JVM/object implementation overhead, or pages retained
by callers after ownership transfer. Sources themselves must supply bounded pages;
GUI composition should retain one displayed result page rather than accumulate
all returned pages. Source name/storage observations are not automatically frozen.

Temporary files are private session framing, not a persistent format. Normal
close removes them even when an adapter close fails. If the process crashes, its
application-owned temporary parent needs a startup stale-session cleanup policy;
this core does not delete directories belonging to other active sessions. No
complete-cache marker or recovery/resume claim exists.

Run focused checks:

```sh
./gradlew :concepts-comparison:check
```

Offline tests cover exhaustive versus partial membership, known/unknown fields,
incompatible semantics/endpoints, stale/duplicate/order failures, quotas and field
limits, sparse paging, two in-flight reads, bounded command rejection and close
ordering/cleanup. No live credentials, network or application-library writes.
