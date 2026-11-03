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
