# Authored persistent World precision acceptance

Tests-only checkpoint based on `24e4074` + `4e415e9`. The test author also authored the
spike harness; the expected ray/plane, texel and depth values are independently derived
without production projection, shading or UV helpers, but root must independently review
this acceptance before approving production work.

Run `./gradlew :rendering-bgfx:nativeGpuWorldStreamAcceptance` on qualified Vulkan hardware.
The separate spike-shader verification dependency must pass after matching Windows DX11
artifacts are installed. The initial local run excluded only `verifyWorldStreamSpikeShaders`
while those two offline blobs were pending; production shader verification remained enabled.

## Observed result

Actual Linux Vulkan passes **71 frames and 1,042,972 analytic pixel comparisons**, with both
compiled-default frame latency and test-only runtime latency one. Full rendering-bgfx unit,
format and Checkstyle checks pass. No production source or shader changes are included.

- Four source origins: 0, 10,000, +999,000 and −999,000.
- Each textured plane is split across two resident slots, with a shared diagonal and identical
  gradient atlases. Every pixel in a 193×193 interior is checked against an independent world
  ray/constant-native-X-plane intersection, point-sampled 512² atlas and explicit UV formula.
  This catches cracks, cleared pixels, orientation, interpolation and rebase discontinuities.
- Seven camera offsets, including .030/.031/.032/.034 and negative values, cross float rebase
  boundaries. Camera yaw/pitch are nonzero. Pixels may differ by one final 8-bit channel unit
  to account for the existing texture-to-float/floor output path. This is a color oracle,
  not a measurement that UV error is universally below 1/32 texel.
- Identical coplanar geometry in two slots has different anchors and differently colored
  atlas owners. Both source orders retain the first source at all four origins and four
  rebase offsets with nonzero yaw/pitch. This proves the tested identical-geometry case;
  arbitrary differently tessellated coplanar geometry is not claimed to have bit-identical
  interpolated depth on all hardware.
- Near .49 rejection, exact .5 acceptance, near crossing, all-far rejection above2200 and
  exact2200 acceptance retain the original whole-triangle policy and flat fog colors.
- Independent overlapping triangles separated by .01 and .1 around eye depth1999.9 choose
  the nearer surface in both orders. A far-straddling triangle survives because one original
  corner is at2000; its visible region near95333 remains nearer than a homothetic farther
  triangle in both orders. Expected colors are fixed independently from the fog equation.
- All output is opaque; public render tickets match the requested camera and committed world
  revisions; all upload references drain, and ten fixed dynamic buffers remain allocated.

## Limits

Large-coordinate inputs are the original source floats, not hypothetical exact doubles.
At approximately one million, float spacing is .0625: a .01 difference already lost in the
source data cannot be recovered by rebasing or reversed depth. Close-depth tests therefore
use representable source coordinates near the origin; the large-coordinate cases test
camera precision relative to unchanged representable geometry.

This is a synchronous authored native harness, not a JavaFX, network, packet parser,
credit state-machine, camera/input latency, frame-rate or asynchronous readback proof. The
mapped-frame World v2 content-revision contract is not implemented here. Vulkan results
must not be reported as Metal, D3D11 or OpenGL runtime qualification. Cross-profile source
compilation remains necessary and separate from hardware execution.

## Root verification

Root reviewed the complete acceptance and shader paths, then reran the opt-in
acceptance with all spike shader verification enabled after importing the matching
Windows artifacts22391a1. Vulkan passes71 frames and1,042,972 samples. Fog retains
distinct source colors at the tested depth cases. The constant-X test plane holds
one UV coordinate constant; these checks do not replace future sloped-surface
precision coverage or universal UV-error bounds. This approval is for the authored
feasibility scope, not a production hot-stream implementation.

## Sloped-surface extension

Root added a two-slot sloped plane x-origin=100+.25*(y-origin), with independently
intersected per-pixel world rays. Both atlas coordinates vary across the surface;
all four origins and seven rebase offsets retain the exhaustive193×193 interior
checks. Updated Vulkan acceptance passes99 frames and2,085,944 analytic samples,
with all shader verification and full rendering-bgfx checks enabled. This retains
the documented final-channel tolerance and backend/protocol limitations.
