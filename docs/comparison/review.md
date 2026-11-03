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

Pending final confirmation of the clarification-only revision. No comparison
implementation has started. Approval of the initial slice does not authorize
claims about the later adapters or durable functionality.
