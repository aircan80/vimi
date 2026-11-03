# Independent comparison design review

The four design contributions were authored by the BA/coordinator, WoW domain
expert, data miner and UI expert. Two fresh independent reviewers authored none
of those proposals and reviewed the consolidated contract and repository APIs.

## Initial independent decisions

- Architecture/resource/lifetime reviewer: **APPROVE Foundation/inventory only**.
  Reviewed application baseline `e0bbdb25d0d09ebe1f070a6a17c3f65bde3231ad` and
  original consolidation SHA-256
  `fbf0b92e300f587b8fdf8df5e15f6e1995fa5f43d060c3f71ead0be8d9be4417`.
- Independent WoW/domain truthfulness reviewer: **APPROVE phased design and the
  Foundation/inventory slice**, with the same bounded initial scope and explicit
  future gates. No blocking truthfulness findings.

Both approvals cover separate typed comparison library, dedicated local-only
same-product/same-locale sessions, selected-locale Files metadata and table/schema
inventory, honest per-area capability/coverage, bounded session-only paging,
provenance, cancellation and dependency-ordered cleanup. They do not close #117.

## Findings incorporated before final approval

1. Files metadata enumeration resolves current names per batch; inject a pinned
   source or mark naming evidence unassessed. Local storage observations are a
   separate mutable dimension. Explicitly recorded in the consolidated contract.
2. Existing preparation contexts use network-capable openers. Factor capabilities
   with a truly local-only opening/read path and forbidden-network tests; do not
   reuse the old Job/open path and assume it is local-only.
3. Enforce queue/page/record/buffer/staging bounds; do not present the comparison
   buffer budget as a bound on an existing source parser's allocations.
4. Exhausted pages/READY do not alone prove complete membership. Each adapter
   certifies source scope/absence or produces observed-only outcomes.
5. Normalize role prose: session-local paging/restart rather than durable resume;
   inventory foundation before semantic adapters; global Compare navigation and
   the user's close-on-leave build policy reflected in UI prose/mockup.

## Required deferred gates

- Each semantic adapter: reviewed lineage, fields, joins, units and completeness.
- External evidence: typed provider coverage, retained immutable snapshots and
  per-field source/winner provenance.
- Persistence: independently reviewed two-build publication, scoped reference
  deletion, complete-result markers and crash recovery. Raw GeneratedStore.put
  does not supply these semantics. No persistent comparison writes before this.
- Durable resume, pairwise accounting and visual/audio/terrain comparisons need
  separate specific reviewed contracts.

## Final revision confirmation

Both independent reviewers confirmed **APPROVED** at exact design revision
`64196bf7d4621a4988177f59e2e0e95fbaaec941` after the clarifications above.
No blocking findings remain. Foundation/inventory implementation may begin under
the consolidated contract. This approval does not authorize persistent comparison
publication, unreviewed semantic adapters, durable resume or closing #117.

This follow-up commit records those confirmations and does not alter the approved
five design documents or authored SVG. Approval was obtained before comparison
implementation began.

## Foundation implementation review

Two independent reviewers approved composition revision
`4360d6138f6810941b41d4bf8d8e7fbe76c67950` with no blocking findings.
Architecture review covered admission ownership, cancellation/opening races,
ordered drain, local-only sources/openers, navigation/shutdown and narrowly scoped
stale temporary-file cleanup. Domain review covered captured key/policy/receipt
inputs, exact cached table inventories and honest file membership coverage.

The architecture reviewer independently ran coordinator, native comparison and
comparison component checks successfully. Native fixtures use forbidden CDN and
listfile clients and exercise missing local metadata, exact receipt reuse,
changed-key rejection and independence from an existing browsed context. The
composition also passed the complete GUI check and authored JavaFX comparison
screen checks. A subsequent label clarification identifies the captured community
key fingerprint separately from the effective table preparation identity.

Approvals retain the Foundation scope and all deferred gates above. Other area
entries are capability placeholders; this does not complete #117.

## Selected raw-table design approval

Independent architecture and WoW/data-contract reviewers both approved exact
proposal revision `e825f2e31e85fea7f14bc19e2e2edb720ab01546` before raw-table
implementation. See [the concrete contract](raw-tables-proposal.md).

Approval covers one selected exact-layout/document table pair, a nonrepair
prepared-local captured reader with BEST_EFFORT_INVALIDATING source lifetime,
length-prefixed structural fingerprint, explicit issue-channel/cell states,
bounded ID sorting and one shared memory/disk budget across sorting and core.
Sealed scratch owns subsequent inputs. Source loss invalidates capture; no
retention or COMPLETE-membership claim is permitted. Ordinary build-owner read
bookkeeping is allowed. Required corruption, deletion, cancellation, issue-channel,
fingerprint and combined-quota fixtures remain implementation acceptance checks.

This gate authorizes that narrow implementation. It excludes acquisition, missing
table rebuilds, repair/deletion fallback, cross-layout mapping, semantic gameplay
claims, persistent reports and completion of #117.

## Content-inventory optimization review

Independent architecture review approved
`d2e1daf946900a8c5eb6e55a56b58604331bf93a` (including `24b6ab5`). The new native
content cursor preserves locale/ID/size/CKey semantics and avoids names, storage
presence and payload queries. Explicit unsupported fallback retains the slower
metadata path without upgrading coverage.

Review caught admission-rejection and returned-future cancellation serialization;
private completion cleanup and a returned observer copy now preserve the busy
flag until the actual worker drains. Deterministic fixtures exercise saturation,
cancellation, retry and ID order. The reviewer independently ran Files and
comparison checks successfully.

## Selected raw-table implementation reviews

Independent architecture and domain reviewers both approved integrated revision
`a794415b8c11dcb2113e48e1faad89780391fa55`. Component approvals include the captured
reader cancellation fix `fdd0930`, bounded source/codec `c70896e`, prepared-local
bootstrap `f0ce742`, shared core/actual-sorter composition through `e7ed681` and
standalone delivery ownership fix `204dc9a`.

Domain review found an ID-cell mismatch with the actual decoder: inline and
noninline IDs are emitted as unsigned row identity even when DBD metadata says
signed32. The required correction `874d85a` gives these cells explicit unsigned32
semantics and includes that policy in admission. Ordinary signed scalar constraints
remain unchanged. Inline/noninline and native signed-Item fixtures cover maximum
row ID, mismatched cells and rejected negative aliases.

Both descriptors are validated before sorting. The application verifies actual
BuildRef/locale/FileDataID, uses captured-only key bootstrap and prepared graphs,
and drains inputs/contexts before closing the shared budget. Native proof uses
SQLite/pack with authored cached rows and an unreadable original payload; it
asserts missing-graph rejection, zero acquisition/object writes and unchanged build
status. Architecture independently reran coordinator and native raw integration
checks. Domain reviewed the code/fixtures without a separate test run.

Approvals remain limited to one selected compatible table, raw verified cells,
observed-only membership and session-only results. They do not authorize semantic
claims, COMPLETE membership, cross-layout mappings or persistent reports.

## Recorded Items increment-A contract approval

Both independent architecture and domain reviewers approved exact contract
`a5b9414b8eff1b731c8dc4276772cab6d4beade0` before implementation. See
[the Items proposal](items-proposal.md). Approval covers a pure two-source
Item/ItemSparse projector, independent source/field admission, source-qualified
facts and uncertainty, bounded stream unions/joins and session-only findings.

Compact metadata remains charged through scanning and paging. The explicit core
plan and final candidate reader must leave that headroom; the existing raw
sorter's 14 MiB reader cannot be called with a 12 MiB allowance. Acceptance needs
an actual joined-items/core/paging fixture with metadata retained throughout,
plus legacy compatibility, partial-source, maximum-ID and cancellation tests.

This authorizes increment A only. Effects/use-text increment B, certified
membership, cross-layout mapping, assets and persistent reports remain gated.


## Pure Items projector review

Both independent reviewers approved exact projector revision
`70bafa934bef981adb1ea139edf48a160264b7f4`. Architecture independently ran
`:concepts-items:check`; domain reviewed the code and fixtures. The fix retains
presence certificates, rejects inconsistent public DTO provenance and ambiguous
row shapes, preserves typed limited tails and valid siblings, distinguishes text
issues and checks localized field scope. Null issue channels remain UNKNOWN.
This approval covers only pure projection; captured staging, joins, core paging
and native/UI composition require separate integration validation and review.


## Bounded Items prerequisites

Root independently reviewed reader revision
`49a97614c4f1eb8de768473f4261f29aff0d4b68`: same-budget sealed file adoption,
structural preflight, rewind without pending DTO retention, masked-identity
filtering and private delivery cleanup satisfy the prerequisite contract. This
approval is conditional on the concrete pure projector enforcing its declared
3 MiB allocation plan; it is not full Items integration approval.

An independent architecture reviewer approved root's stream union and coordinator
final-scope changes at `d6d81529b449ba7b0fcc1b6071e80fff27172d31`, excluding their
own reader. Heads remain charged through actual read delivery, successors seal
before dependency deletion and paired scopes are rechecked before core admission.
The reviewer ran comparison checks. A redundant unsigned-domain guard was added
after review; sorted projector inputs already enforce unsigned32 identities.

The actual authored sort/join/candidate/core/all-pages fixture retains the 1 MiB
metadata allowance through scanning and paging. Concrete compact metadata/codecs,
captured adapters and native/UI composition remain separately gated.


## Items increment-A integration review

Both reviewers approved compact metadata/codec revision
`572947cf75f422658d3ea685af8cc20878c799f5` after fixing transactional registration,
preallocation bounds, full certificates and Unicode boundaries. Architecture
independently ran comparison checks; domain reviewed the authored fixtures.

Architecture approved assembled integration through
`03238333a6a56a69427d7705d0f83ce14018cc87`, including the captured source adapter,
sequential heavy captures, shared quotas, successor-before-input deletion, final
paired admission and metadata retention through paging/close. Its own reader was
excluded, with root's separate prerequisite review recorded above. Independent
comparison, coordinator and native raw/Items checks passed.

Domain independently approved the same integration revision after Coverage details
were made available without a result selection. It independently reran the native
SQLite/pack proof. Its authored source adapter was excluded from its approval and
covered by architecture's independent review. No remaining review blocker is open.

Approval covers recorded increment A, prepared-local inputs and session-only
findings. Effects, complete membership, persistent reports and native text-issue
certification remain separate capabilities. Live-data observations and native CI
are recorded separately; they must not expand this semantic scope.


## Native issue certificate contract approval

Both independent architecture and WoW-data reviewers approved exact design
`09533d58b41e825999ce392c4317e4ba3b6a60a9` before implementation. The contract
binds audited native producer evidence to the actual selected scoped/unscoped
graph identity, retains old/custom UNKNOWN channels, separates provenance from
semantic field admission and preserves current LIMITED-row cell semantics.
Implementation must still prove actual native reader/store/reopen/capture round
trips, unchanged legacy/C# bytes, no local repair and no membership promotion.


## Linux implementation checkpoint — 2026-11-03

Revision `f0a6d5b` implements the reviewed native issue-certificate contract, including
producer capability/sealed content, distinct native identities and metadata,
actual scoped/unscoped certificate validation, native-first/legacy-on-absence
selection and capture-only null normalization. Compact raw/Items evidence metadata
is separate from semantic field admission. Legacy identity/RowCodec/C# goldens are
unchanged. `GeneratedTableContent` representation changes are documented in the
tables README.

Root Linux `check assemble` passed; final affected checks plus table C# interop
passed with 110 table unit, 2 disk/pack pipeline, 3 interoperability and 156 comparison
tests, all without failures/errors/skips. This is author validation, **not independent
implementation approval**. Architecture/domain implementation acceptance and hosted
CI remain required before integration. No live-library test or new release is claimed.


## Native issue certificate implementation approval

Independent architecture and domain reviewers approved exact implementation
`66700ad2d39b75a315bca3d8c0324aa6a84521b0`, including the native publication fix
`6c24cac` and authored acceptance fixtures. Architecture reproduced and blocked
publication before a subsequent layout failure; final native publication now
follows stream completion, manifest readback, owner cleanup and layout/alias writes.
Lost final acknowledgements reconcile only the exact committed graph without
repair or deletion. Unavailable reconciliation leaves an explicitly uncertain
storage outcome; no incomplete stream gains a certificate.

Architecture independently forced fresh execution of 119 table unit, 2 native
pipeline and 156 comparison tests at the approved revision, all passing with zero
failures/errors/skips. Domain independently ran 39 focused capture/certificate/
publication tests at the approved revision, also passing, and previously reran
110 table plus 156 comparison tests before the final acceptance additions.
The real PARTIAL/protected localized text and resolved string-copy fixture,
nonsparse strict malformed UTF-8, legacy explicit-empty UNKNOWN, exact 65,536-byte
issue budget and scoped/unscoped digest exchange cases close the identified gaps.
Approval remains limited to prepared-local, session-only comparisons, legacy/custom
UNKNOWN evidence, LIMITED overrides, unchanged semantic admission and uncertified
membership. Hosted CI for this corrected revision is still required before merge.
