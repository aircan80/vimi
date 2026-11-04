# Combined terrain source and guarded writer checkpoint

This next step composes the already approved source scopes, prepared geometry and
owned material operation. It adds no GUI/native renderer wiring or pool/cap changes.
The source service owns its engine and bounded prepared output registry; WorldService
joins engine, material request drain, cache and output lifetimes on close. An external
caller cannot supply a receipt, scene, pixels or purported positive source binding.

## Internal API and authority

The owning service exposes createScope/activate and a source-worker-only operation:

    <R extends AutoCloseable> WrittenTile<R> prepareAndWrite(
        Scope scope, Tile comparisonSelector, Cancellation cancel, Output<R> writer);

Output.writeUnadvertised receives a PRIVATE-constructed, synchronous scoped tile view
with checked root geometry and optional qualified material. It cannot retain/escape
that view, native geometry or pixel buffers. The callback writes before returning R;
R is an owned unadvertised resource, NOT a source-authority certificate. The callback
owns/cleans any unreturned R/scratch; the engine owns the returned aggregate through
final validation/transfer. No child starts and no packet is advertised in callback.

A closed packet bridge entry (separately implemented in the codec's owner module)
accepts this source service/scope/selector and metadata-only session/transaction/lease
parameters. Its fixed callback builds header and bytes ONLY from checked views and
actual structural codec output. It never accepts caller Header/Scene/Receipt as
positive authority. Only that path privately constructs a source-qualified packet
owner; structural codec DTO/Expected alone remains descriptive. Generic R success
alone cannot mint a qualified packet. This keeps concepts independent of bgfx/GUI.

WrittenTile owns ONE prepared geometry base owner, optional ONE atlas lease and R;
these owners MOVE through writing/staging/residency, not retain once per neighborhood.
It is registered before throwable work, with twelve total output/construction credits.
Source close invalidates its small validity gate immediately, detaches/closes outputs
outside monitors and waits for independent payload/channel callback pins to drain.
A synchronous withPayload borrow pins geometry and atlas before consumer code; close
rejects new borrows but cannot invalidate already-borrowed read-only data. No array or
RGBA copy is introduced. Expiring callback views retain no admission/service handle.

## Actual ordered operation

1. Reserve output credit before geometry/material work. Enter withRecordedTile;
   snapshot descriptive namespace from the live private admission, never a caller DTO.
2. Prepare root through WorldService's existing12-version cache. Preserve actual root
   ID/CK/consumed length/nullable declared length/source limit and geometry fingerprint
   as a private bounded observation. Input/parser temporaries retire before materials.
3. Borrow the WorldService's SAME configured material helper and application atlas
   pool. Missing helper/pool is unsupported capability, never fabricated fallback.
   Verify helper build/locale against admission BEFORE read. No second helper/cache.
4. Invoke readOwned for exactly ONE admitted tile with internally derived numeric WDT
   and map ID. Hold engine admission across result plus Operation.awaitDrained, even
   when result failed/canceled. Close acquired BatchResult on every failure path.
5. Require exact requested tile and actual receipt build/locale/map/WDT/root/tex0
   bindings. Retain one atlas lease then retire BatchResult. Match receipt geometry
   hash to prepared native tile and call TerrainTextureBinding.qualify/validatePixels
   with actual atlas mappings. No caller snapshot or receipt can qualify pixels.
6. READY/PARTIAL with valid binding becomes TEXTURED (coverage remains honest).
   Actual terminal UNAVAILABLE may become HEIGHT_FALLBACK only with checked geometry;
   transient POOL_BUSY, absent configured capability, CHANGED, cancel and unknown
   program/provider failures propagate. A positive receipt with a profile-only
   binding rejection may use QUALIFIED_BINDING_REJECTED only when source identity
   still matches; mismatch never falls back. Root/source failures never mint geometry.
7. Invoke fixed writer through scoped pinned tile/material views. Retain the single
   base geometry/atlas owners into the registered aggregate, with R; no view escapes.
8. After writer/channel drains, admission revalidates ALL observed/consumed bindings,
   Map stamp/EOF (partial private witness + fresh Map stat), WDT and owner/generation.
   Only successful transfer exposes WrittenTile to parent. Failure closes R/atlas/
   geometry/output credit without advertising or losing the primary exception.

## Necessary observed-binding completion

Existing positive Receipt.bindings covers successful material products, but a known
terminal parse failure can consume tex0/diffuse bytes and return no receipt. Therefore
readOwned must expose a PRIVATE-constructed bounded observation trace for the actual
operation, including sources attempted before format/profile rejection. It records
identity/observed length metadata only; it never authenticates parent ancestry or
qualifies a positive source tuple. The factory records that trace in the admission
and checks identity after the writer, including terminal-height paths.

Narrow readOwned to ONE tile (public legacy read remains1..9). Maximum unique trace
entries67 (WDT/root/tex0 plus64 diffuse), one immutable detached snapshot published
only after actual worker/cancellation drain. Register actual stream identity before
reading; merge duplicates only when CK/length facts agree. No successful-binding
receipt is invented for a failed read. For fallback packets, tex0 stays absent unless
there is actual qualified consumed evidence; a failure trace is not a positive tuple.
Trace overflow/CHANGED/program errors are nonterminal failures, never height fallback.
An actual helper Operation is required, not an injected public list/DTO.

The trace and twelve output controls fit the approved64KiB factory-control allowance
(67 bounded source observations plus12 owners/borrow records); existing actual material
receipts remain charged to the pool's existing metadata allowance. Cache/construction,
prepared-table open and4MiB native Map inspection remain their reviewed separate
components. Geometry<=12 versions; atlas<=18 unique slots; global material1active+
1latest; no third geometry owner set or repeated neighborhood atlas claims.

## Causal authored/actual gates

Actual native Map/WDT/root/tex0/BLP chain for full/partial/terminal and checked geometry;
no fake positive receipt constructors. Hold after material result then mutate tex0 or
any consumed diffuse, including a failed-profile source: final transfer rejects and
R/scratch/claims drain. Hold lower cancellation while result is already failed: no
next geometry I/O. Hold writer/payload and race source close; no early credit or stale
borrow. Unknown helper/provider error cannot become fallback; unsupported configured
pool and POOL_BUSY remain distinct. R allocated then writer throws before returning
must be cleaned by callback finally. Compare writer output with structural readback
under its actual session/lease/geometry/atlas metadata. Finally prove one actual Map0
source-qualified textured tile and owned cleanup using only private scratch assets;
no renderer/native promotion is implied by these source/writer tests.


## Material observation implementation checkpoint

The owned helper records at most 67 distinct numeric lookups for exactly one tile.
Its private-constructed immutable observation is not a positive FileBinding or a
source receipt. It retains lookup state, reported numeric ID, normalized valid CK
when present, initial nullable declared length, consistent observed length, actual
open state/length, and whether actual opened bytes reported holes. A NOT_FOUND or
UNBOUND observation retains the attempted numeric ID and typed absence/unbound
category without inventing a CK. Malformed inputs that were actually consumed
remain in the trace even though no positive material receipt was produced.

Observations become accessible only after actual owned worker/lower cancellation
retirement; exceptional retirement remains exceptional. Pending/rejected jobs have
an empty immutable trace. The admission copies only bounded immutable observation
references, never the operation, batch, or helper. The union of admitted positive
bindings and observation IDs must stay at most 67. After unadvertised writing,
final validation freshly stats every observed numeric ID, including absence and
unbound outcomes. Newly available input, changed CK/ID, contradictory known size,
or changed negative lookup identity rejects the transfer. Consistent unknown-to-
known length and display/storage metadata enrichment remain allowed. This trace
asserts observations only; the combined factory still must use the service's exact
configured helper and actual qualified receipt/pixel checks to mint its private
positive tile capability.

Packet-file retirement and resident payload retirement are separate. The returned
R owns only its unadvertised packet file/channel and may close idempotently at
INPUT_CONSUMED. WrittenTile owns the singular base geometry/atlas owners through
STAGED/current/presented residency and closes both base owners and R at final
retirement. A transport packet cannot retain WrittenTile or source handles. A
later closed GUI bridge will own one WrittenTile holder per opaque resident and
share metadata aliases across pending/current/presented tables; it cannot create
additional base retains. That bridge's late-cancellation handoff remains a
separate implementation/review checkpoint.
