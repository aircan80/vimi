# Verified area navigation review

## Scope and approvals

[Contract](world-area-navigation-proposal.md) corrected at4381d16 has independent
domain approval and acceptance scope approval. Root implements backend; acceptance
implements GUI. Each author excludes their own implementation from review.

- Domain approved shared geometry/metadata parser34fd186: exact unsigned offset52,
  all derived-position validation, native height/hole-aware entry and unchanged
  geometry admission. Raw originZ remains a finite height base, not a bounded
  ground coordinate.
- Acceptance independently reviewed helper30221f5 and entry verification1dd8326:
  one worker/latest pending, source receipt checks, cancellation/drain, bounded
  diagnostics and borrowed source lifetime. New service tests645d374 verify stale
  source rejection despite old cached geometry and close ordering.
- Root reviewed GUI26b11fb plus correctioneb82aa8 (integrated5c4bd97/2a7eba2).
  A discovered cancellation deadlock was fixed with a separate bounded cleanup
  worker and a synchronous-blocking provider regression. Progress is coalesced,
  stale generations cannot clear newer activity, and areaTile verifies entry
  evidence before the GUI uses its source-derived camera point.

## Executed checks

Root `check assemble :gui:installDist` passed at2a7eba2. Metadata/parser and helper
component checks passed. Authored JavaFX World checks passed in the GUI author's
isolated tree; the integrated FX check is a separate delivery gate. Tests cover
exact source-byte offsets, mixed areas, hole masks, malformed unrelated heights,
blocked reads, cancellation, latest pending, source changes, bounded diagnostics,
verification before opening, stale progress and borrowed handle close ordering.

Actual Forever1.60.1.70755 Map0 inventory has736 tiles. A scratch/read-only proof
checked ADT777947 at32,32:256 chunks,37120 vertices, seven distinct area IDs.
Exact area279 matched one chunk; a fresh bound open verified CKey and entry point
identity against an actual native mesh vertex. This is not a whole-continent
scan or general zone membership certification. Original library stayed read-only;
only a missing source tile was acquired into private scratch. No game assets or
screenshots are committed.

[Authored UI screenshot](images/world-area-navigation-authored.png) contains no
game data. Hosted native CI and all five packaged checks gate merge/publication.
World terrain remains CPU rendered in this release; GPU terrain is independent.
