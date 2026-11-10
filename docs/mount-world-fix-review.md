# Mount material and World grid corrections — 2026-11-03

Independent scopes: domain approved legacy version272 admission `7d59c1f`, World grid
`a5abe46` and GUI/native qualification original `1ec6ec2` (integrated `22f63c8`).
Architecture independently approved CPUqualification original `2b3472f`
(integrated `06915eb`). Production code and fixtures were reviewed independently.
The GPU-control repair original `af593d2` is integrated as `fdfc861`.

Full local check/assemble/installDist plus authored Mounts, World and GPU UI
checks pass. Focused model/renderer/native/GUI checks also pass after final
coverage wording. Tests preserve legacy renderer defaults, distinguish section
ordinals from duplicate geoset ID0, exercise texture-toggle qualification and raw
diagnostics, retain modern-version material guards, and verify asymmetric-grid
pixels, inverse clicks, zone-reference behavior and exact requested terrain file.

Private live 70755 proofs establish corrected cold/restored bindings for mount 28 and body textures for mount103. Actual CPU/Vulkan same-scene results: corrected mount 28 IoU0.999975, mean RGB
error0.488636; qualified mount 103 IoU1, error0.518237; qualified mount 79
IoU1, error0.504409.
These validate pipeline parity, not complete WoW rendering or native Mac/Windows
GPU qualification. Mount 79 ENV armor and additive billboard glow remain unsupported.

Reviewed primary legacy-field evidence:
[Blizzardry SKIN fields](https://github.com/wowserhq/blizzardry/blob/bef2d743bdbbfa66391238368f7274051adc3e66/src/lib/m2/skin.js#L23-L35),
[WMVx fields](https://github.com/Frostshake/WMVx/blob/90a4d9e7d4fffb4af483f2fe5d11489ae1d5d42c/src/core/modeling/M2Definitions.h#L297-L310),
[modern projection guard](https://github.com/Deamon87/WebWowViewerCpp/blob/1a8cccbeffc46231c6497e6b3f5bfbf3507d8071/wowViewerLib/src/engine/objects/m2/m2Object.cpp#L1791-L1793).
No game assets or user-window captures are committed.
