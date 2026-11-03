# Static mount material implementation review

Production checkpoint: `4167c4c`, based on main `f10febd`.
Contract: [proposal](model-materials-proposal.md) and [validation](model-materials-validation.md).

## Independent scope approvals

- Architecture approved data/codec/CPU `f832f47`, `b3ef803`, including additive seam correction `92c01f1`; independently reproduced zero doubled diagonal pixels.
- Acceptance approved bind-pose facts `7224ec2` + `7081859`, including native own-track evidence.
- Domain approved native GPU `201a951`, style `cf47e4e` and DX11 artifacts `06f2de6`; this excludes the domain author's CPU implementation.
- Architecture and domain independently approved source admission `68c2048` + `4167c4c`, with authored negative/failure fixtures `486cd4b`. Optional fact failures preserve unaffected sections, propagate cancellation and suppress incomplete cache publication.

## Executed evidence

Root `check assemble :gui:installDist` passed with all eight pinned shader blobs.
Native material proof passed 30 authored Vulkan frames; native inline transport
proof passed. Four JavaFX tasks passed: `gpuPreviewUiTest`, `gpuInlineUiProof`,
`mountsUiTest`, `racesUiTest`. Source admission/failure tests and the additive
shared-diagonal regression passed.

Independent private read-only/scratch acceptance passed cold/restored appearance
byte identity and complete retained receipt checks for mounts 28, 79 and 103.
Across 36 actual Vulkan/CPU frames, minimum silhouette IoU was 0.99983689 and maximum
intersected RGB mean error was 0.522436/255. All output alpha was 255. Four-camera
ENV contribution and rigid-card facing were independently checked. Each mount
used one child for twelve camera requests with no restart and clean shutdown.
Private artifacts are under `/tmp/vimi-live-mount79-proof/final-rich/`; game assets
and screenshots are deliberately excluded from Git.

Linux Vulkan evidence does not qualify actual Metal/D3D rendering or establish a
performance speedup. Static bind/rest previews omit ancestor animation and other
unreviewed material profiles. Hosted native CI and packaged release checks remain
separate delivery gates.
