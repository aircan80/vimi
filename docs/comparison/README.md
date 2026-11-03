# Build Comparison design and implementation gate

Proposal for #117, based on application APIs at `e0bbdb2`. Four requested role
contributions are present:

- Business analyst: [requirements](requirements.md).
- WoW domain expert: [domain semantics](wow-domain.md).
- WoW data miner: [identity, coverage and API contracts](data-contracts.md).
- UI expert: [screens and interaction](ui.md), with an
  [authored mockup](../images/build-comparison-design.svg).

The Foundation/inventory design received independent approval before coding;
its implementation was FF-merged in #131 after native unit/integration checks on
Linux, macOS and Windows and storage compatibility checks. See [review.md](review.md)
for exact approval revisions and [view.md](view.md) for the delivered UI contract.
The role documents below preserve the reviewed proposal. Their later phases remain
gated; inventory delivery does not complete #117.

## Delivered foundation

Open **Compare**, select two locally prepared builds of the same product/locale,
choose **Files** or **Tables**, and select **Compare local data**. Merely selecting
endpoints or browsing results starts no acquisition. Missing local inputs explain
what needs preparation through the ordinary build workflow.

Files assesses content keys and decoded sizes; names and current storage presence
are unassessed, and membership coverage remains unknown. Tables restores an exact
cached known-definition preparation inventory with full schemas unassessed. It
does not compare table rows. Other areas remain explicitly Not implemented.

Findings are session-only, paged and bounded. Search/status filters apply to the
current page. Known-equal records are omitted; scanned identities and findings
are distinct. Coverage reasons and captured dependency evidence are shown.
Leaving Compare cancels/drains both dedicated sessions before navigation proceeds.
There is no persistent comparison report or resume capability.

## Product decisions

1. Add **Compare** beside Builds in the build-work part of application navigation,
   before the separator for Data Sources and Settings. It is a separate workspace,
   not another view that steals the currently opened build's handles.
2. Initial semantic comparisons require the same product and locale. Cross-product
   identity and cross-locale interpretation are later explicit capabilities, not
   name/ID guessing or silent locale fallback. Incompatible areas show an
   unsupported explanation, never a zero-change total.
3. Opening/selecting a pair performs no acquisition. **Compare local data** uses
   prepared/cached inputs. Preparing missing inputs is a separate explicit action;
   it does not block useful comparisons of readable partial inputs.
4. Present an entry for every current area from the outset. An adapter is marked
   Not implemented/Unsupported/Unavailable until it exists. The first delivery is
   an inventory slice, not completion of #117 or a claim of full gameplay diffs.
5. Closing/leaving the comparison cancels work and drains its sessions. It does
   not leave hidden unlimited locks or silently continue a background run. Completed
   saved reports can be reopened only after persistent-result support is delivered;
   initial session-only results are labelled accordingly. No durable Pause/Resume
   control until a checkpoint contract has been implemented and reviewed.
6. Summary/list labels distinguish confirmed membership/field changes, source
   evidence, interpretation and readability. Added/removed always names its
   assessed source scope. NPC absence is not a claim that Blizzard removed an NPC
   from the whole game. Asset byte changes are not automatically gameplay changes.

## Component and lifetime boundary

Create `:concepts-comparison` as a separate class library with its own tests. It
owns canonical comparison types, merge/classification, bounded work and result
paging. It borrows immutable input interfaces; JavaFX, SQL and acquisition clients
are not part of its public contract. Existing concept libraries do not depend on
comparison types. GUI composition constructs input adapters and renders DTOs.

The application must open dedicated comparison sessions for A and B, with
independent build/files/tables/concept handles sharing the existing library and
immutable input bytes. Do not obtain a preparation Job and later close a handle
owned by the browsed build. Factor/reuse the preparation context's capability
construction rather than duplicating storage policy. Default sessions use a
local-only ObjectSource; missing bytes remain typed unavailable facts.

Existing APIs must not be mistaken for frozen/local-only sessions:
`BuildFilesHandle.Listing.fill()` asks its NamesSource for current names per batch.
Inject a captured immutable name source, or mark changing naming evidence
unassessed. Capture storage presence separately from immutable content facts.
`NativePreparationContexts.Context` currently builds a network-capable opener;
the comparison path must explicitly use local-only opening as well as local-only
reads, tested with unavailable metadata and a forbidden network client.

A run pins ordered endpoint identities and a dependency manifest before scanning.
Provider snapshots, mappings, definitions, parser/projection revisions and coverage
are inputs. Process-local revision counters are stale-result guards, not cache
identities. Acquire source-retention leases before claiming a reproducible provider
capture; if the repository cannot retain a source, that adapter remains unsupported
until its lease contract exists. A source deleted during an unleased capture
cannot yield an apparently complete report.

Cancellation drains comparison tasks, then owned concept adapters, tables, files,
and build sessions in dependency order. The shared object library, provider
repositories and separately browsed build remain application-owned. Shutdown and
close races require deterministic tests before GUI wiring.

## Canonical evidence and bounded execution

Each area declares its identity namespace, comparable field semantics, source
scope and coverage. Field values retain typed known/empty/unknown/protected/
unsupported states and provenance. Schema-incompatible columns have schema deltas;
matching column ordinals/names alone is not a semantic crosswalk. Exact same-layout
raw rows are the first admissible row-comparison case.

Enumerate stable ID-ordered pages and merge the two sides. Validate ordering,
uniqueness and page identity. Exhaustion of a readable prefix is not authoritative
absence unless that adapter certifies complete membership in the declared scope.
A known field change can coexist with other unresolved fields. Preserve both.

Initial resource policy, subject to measured tests:

- One active comparison; at most two concurrent source reads and one merge worker.
- Input pages up to256records, result pages up to100records, queued commands up to8.
- Per-result/canonical-record serialized bound1MiB; oversized fields/rows remain
  typed limited/unknown while other readable facts survive.
- At most32MiB retained comparison working buffers and512MiB temporary result
  staging per run. Existing source-table parser allocations have their own limits;
  these numbers must not pretend to cap memory inside an unbounded borrowed API.
- Up to5million observed identities per area before a clearly labelled limit.
  Quota/limit/cancellation prevents exhaustive-scope claims and added/removed
  conclusions that require the unprocessed remainder.

Use bounded sorted staging when a source cannot provide the required order; do
not silently materialize two complete million-file catalogues. Temporary staging
is owned by the comparison component/coordinator and cleaned after close or failed
run recovery; it is not a second permanent library/catalog. An implementation may
choose an existing suitable bounded primitive after the independent review, but
must keep these externally visible resource/coverage contracts.

## Persistent results and build accounting

Initial inventory delivery may be session-only with explicit UI wording. Persistent
complete reports are required before the overall epic can close. Store immutable
versioned pages/manifests through the existing generated/object-store boundary;
no GUI SQL or second blob library. A complete-cache identity includes the ordered
pair, locale, policy and full captured dependency/coverage identities.

Persistent publication must not precede a reviewed two-build ownership protocol.
The proposed final policy is that comparison-derived objects belong to both build
owners; deleting a comparison releases only its own derived references, never
original build objects or another comparison's/shared references. Forgetting an
endpoint invalidates its retained pair report according to the reviewed deletion
policy. A per-pair unbounded proliferation of catalogue producer sources must not
bypass the catalogue's source limits.

Existing owner-bound puts commit one owner's association. They are not an atomic
two-owner publication API. Before the persistence phase, approve and implement a
small catalog/publication capability with a complete-result marker/recovery rule
that cannot expose a half-published pair. This is a concrete phase gate, not an
assumption that raw GeneratedStore.put already solves ownership. Until that gate
passes, no persisted comparison objects are written.

Pairwise storage figures (A-only/B-only/shared) are optional after semantic
comparison and need their own scoped catalogue query. Existing build unique/shared
figures are relative to all retained builds and must not be relabelled pairwise.
Storage availability/accounting changes remain separate from client-data changes.

## Ordered delivery plan and completion

1. **Foundation and inventory UI:** typed snapshots/evidence/coverage, dedicated
   local-only sessions, bounded comparison jobs, Files selected-locale inventory
   and table/schema inventories, summaries/paging/details, honest area capability
   rail. No durable-resume or all-area-complete claim.
2. **Raw table records:** exact compatible-schema row/field changes, copy identity,
   protected/unknown/exact-absence evidence, readable partial sources.
3. **Client concepts:** add reviewed adapters for Items, Creatures, Classic Talents,
   Races, Dungeons, Mounts, Professions/Recipes and World metadata/WDT references.
   Keep per-table join/set coverage and shared referenced spells/assets explicit.
4. **External evidence:** add typed source completeness and source retention,
   frozen NPC/Quest/provider/location observations and merged winner changes.
5. **Persistent reports:** independent review/implementation of dual-build ownership,
   deletion/recovery and immutable complete-result reuse. Durable partial resume
   remains optional and separately labelled until proven.
6. **End-to-end acceptance:** all current areas have their agreed adapters or an
   explicitly reviewed unsupported semantic scope; actual read-only paired-input
   proof, authored responsive UI review, native CI and prerelease publication.
   Raw table fallback alone does not finish a promised concept adapter. Close #117
   only when the agreed full scope is complete; release honest interim slices.

Models/audio/terrain visual diffs, cross-product identity crosswalks, cross-locale
semantics, exports and pairwise accounting are follow-up capabilities rather than
hidden requirements for the initial inventory slice. Every new capability gets
specific evidence and tests; no automatic download/render of all assets.

## Independent review record

Pending: independent architecture/resource/lifetime review and independent WoW/
comparison truthfulness review. Findings, resolutions, exact reviewed revision and
approval will be recorded in `review.md` before implementation begins. The user's
standing instruction is to continue autonomously and retain nonblocking questions;
no separate user approval is inferred when peer approval satisfies this gate.
