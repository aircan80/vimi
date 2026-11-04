# Opt-in cold World child WMO integration

Status: concrete API/CLI/status/owner contract for review before production edits. Native pass/neutral source adapter through8a65ce8 and actual source→adapter→packet→native proof are separate approved/evidence checkpoints. This proposal wires only the immutable cold child, not the GUI/source facade, global instances or hot v2 mode.

## CLI and one actual base

Add optional `--wmo-annex <absolutePrivatePath>` only alongside `--terrain-scene`, existing `--frames`/`--parent-pid` and optional `--terrain-textures`/`--backend`. Reject duplicate/unknown keys, model/headless-smoke mixing and WMO+hot-v2 arguments. The v2 dispatcher/parser remains unchanged and rejects this unsupported key; no WMO hot capability follows. Legacy behavior/options/statuses stay unchanged when the flag is absent.

If terrain textures OR WMO is requested, `GpuViewerMain` reads the actual terrain base exactly ONCE with `GpuTerrainSceneFile.readBound`. Reuse its SAME scene/digest for texture-annex parsing and `BgfxWorldWmoPass.readBound(wmoPath, sameBaseDecoded, stopping)`. No second geometry decode or caller SHA/neutral DTO replaces that actual proof. Existing no-annex terrain/model loading stays on the old path. The native pass itself performs bounded WMO input/pairing before GPU allocation and retains only the source-neutral privately decoded presentation data.

Add one package-internal `GpuFramePipeline` overload/factory accepting the actual base Decoded, optional texture annex and an owned `ColdWorldWmoOwner`; preserve current constructors. Pipeline's world-submit path calls terrain first, then the owner's initialized WMO pass with the EXACT same `GpuWorldRequest.camera/width/height`, before the existing blit/readback/publication. WMO never configures/clears another view/framebuffer/context, and consumes no terrain slot or new camera. Existing dimensions800×500 and readback ring stay unchanged.

## Enforced one-attempt context reservation

`GpuViewerMain` creates ONE `ColdWorldWmoOwner` inside the successful bgfx context lifetime only when WMO is requested, then passes that same owner to its sole immutable pipeline. Owner records its native thread and has states NEW/ATTEMPTED/CLOSED/CONTEXT_DRAINED. Its attempt atomically in owner-thread metadata marks ATTEMPTED and spends the fixed6175744byte conservative envelope BEFORE WMO read/upload; any second attempt throws before IO, including after unavailable/close. There is no reset/retry/replacement API. An expected failed prefix remains charged, not treated as newly free room.

Owner close rejects future work and issues pass destruction once; it does not label native GPU buffers/copies drained or release the conservative charge. Pipeline closes it along with existing renderer/readback ownership. Main's finally also closes idempotently, calls actual `bgfx_shutdown`, then and ONLY then records CONTEXT_DRAINED/release. If shutdown does not return, no fake drained/CLOSED signal or retry is produced. Existing parent/EOF watchdog semantics remain; this change makes no stronger runtime watchdog claim.

Pipeline construction must place optional owner attempt inside its existing failure-cleanup scope, so unknown failure/cancellation after terrain/readback initialization closes those already-created owners too. WMO constructor already destroys partial native handles and frees direct staging in finally. Pending readback pointers drain through the existing ready-frame path before their CPU buffers are freed; shader/texture/copy destruction remains charged through actual context teardown. No fallback object retry is allowed inside that context. At most current baseline121893172+6175744=128068916bytes of the reviewed logical envelope; no cap increase/extra target/whole packet copy.

## Expected optional failure, strict unexpected failure

Expected `IOException` from WMO packet input/pairing/capability/shader/native creation yields an explicit optional unavailable state and continues the existing terrain (and whatever existing texture status it already has). Use one honest fixed LOAD code covering read/binding/native initialization; do not guess cause by exception-message text or claim water/source corruption/height fallback. No partial WMO is drawn after failure. Cancellation propagates to the existing lifetime drain. Unknown implementation/runtime failures remain fatal to this child rather than silently turning into WMO unavailable. The enclosing context still owns and retires every failed upload prefix.

A bad terrain base/mapping/model input preserves existing fatal behavior. A good WMO decoded packet remains structural safety only; child state does not authenticate game sources. Source positivity and final latest-view/Map/ALL-binding checks remain in the private source-worker factory and trusted unadvertised producer, outside this child proposal.

## Bounded additive status protocol

New optional `WorldWmoStatus` has exact ASCII line format (six tokens):

```
VIMI_GPU WORLD_WMO <LOADING|INITIALIZED|READY|UNAVAILABLE> <NONE|LOAD> <images> <batches>
```

Counts are0..6. LOADING/NONE starts0/0 and may report each of at most six accepted texture uploads with batches0. INITIALIZED/NONE has positive actual image/batch counts only after the whole native constructor succeeds. That line is **not displayed pixels** and does not end the application's first-frame activity. READY/NONE with the same positive counts is emitted ONLY at the first successfully published mapped frame boundary, immediately before the existing generic READY for that same frame. If WMO is unavailable, emit UNAVAILABLE/LOAD/0/0 and later generic READY means the usable base world; never also emit WMO READY.

The fixed startup-only sequence is at most ten short lines, no per-camera/frame/status flood, arbitrary text/paths/URLs or new stderr payload. No WMO lines are emitted without the new flag. Existing legacy HELLO/texture/READY/CLOSED ordering/meaning otherwise remains; existing stdout writer/watchdog behavior is not relabeled as the newer v2 status-writer qualification. The future parent parser/GUI must keep initialized vs accepted-frame presentation separate and expose the explicit BASIC approximation/optional failure. No GUI code is changed in this milestone.

## Reviewable implementation and acceptance

Own `GpuViewerMain` legacy branch, `GpuFramePipeline` optional path, NEW owner/status files/tests and only small pass count getters. Hot actor/renderer/parent files, source factory/partial witness and f6codec remain untouched. Add authored CLI/status malformed/mixed/count fixtures, pure one-attempt/charge/close oracle checks using missing input before native allocation, and actual owned-child controls/mapped frames for WMO source-independent packet/camera/resize/EOF/close/optional malformed annex/legacy no-flag behavior. The mapped frame must visibly compose terrain+WMO and maintain accepted revision while camera/resize reuses the same PID/context/resources; no READY before initialization/publication. Confirm actual owned child exit before deleting private packet/frame files.

Native source→adapter→packet→child pixels and the future production source/GUI facade remain separate proofs. No public source-positive DTO, new required BuildWorld method, invented asset IDs, altered current view distance or claim of full cities/instances/cross-backend/FPS is introduced here.
