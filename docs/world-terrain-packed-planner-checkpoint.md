# Packed planner core checkpoint

Status: preservation/API pilot while current-area UI takes priority. The compact DTO checkpoint passed independent root review. This core is **not yet a completed production acceptance checkpoint**: full49-source refinement/stitch/cap/adversarial coverage and budget measurements remain required before integration.

The worker-only core uses two packed fine-cell byte arrays, bounded source descriptors, a shared ErrorWork allowance, transient one-MCNK height reconstruction, reusable145sample count scratch and bounded per-chunk count caches. It does not allocate geometry, perform source IO or change a renderer/UI. Successful scoped results expose scalar leaf traversal and checked source-position copy; close releases packed/source references. Unknown all-source coverage cannot become an empty success.

Current authored tests cover exact central source counts37120vertices/196608indices/one batch, original float source-position copy, scoped close, known absence versus unavailable coverage, preallocation budget refusal and cancellation. `:rendering-world:check` passes. Those tests do not establish full neighborhood balancing/refinement correctness or performance; the design's remaining gates still apply. Source/result constructors do not authenticate receipts.
