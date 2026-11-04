# Actual terrain source to persistent GPU proof

On Linux Vulkan, integrated public source → closed GUI source bridge → fixed packet
writer → public transport COMMIT → acknowledged detached frame succeeds for three
actual Map0 tiles in one child process. This is a private command-line proof, not
WorldView enablement or a movement performance result.

| Tile | Actual appearance | Covered atlas rectangles | Content revision | Producer start to first matching frame |
| --- | --- | --- | --- | --- |
| 32,32 | TEXTURED | 256/256 | 1 | 1328 ms |
| 32,31 | TEXTURED, partial coverage | 223/256 | 2 | 620 ms |
| 33,32 | TEXTURED | 256/256 | 3 | 422 ms |

All commits retained earlier exact opaque residents. Camera updates between commits
produced subsequent frames; one launch/PID774942 remained alive throughout, and the
last accepted sequence was9. Root inspected the first and three-tile images: actual
textured terrain and roads are visible. Shutdown waited actual process/worker/file
retirement, then source holders closed and atlas claims returned to zero.

Evidence and images are private under
`/home/aircan/.cache/vimi-tmp/world-stream-real-proof/`:
`ActualStreamingProof.java`, `actual-network.log`, and `run-2/terrain-{1,2,3}.png`.
The original and prior libraries were READONLY. Network acquisition used a fresh
writable proof cache (297MiB), not the original library. Initial Map/build setup and
cache acquisition precede the per-tile timing above; these numbers are not startup
latency, FPS, or a representative sustained movement benchmark. The initial local-only
run rendered tile32,32 but correctly refused uncached32,31 with SOURCE_INPUT; that
failure was not translated into fabricated height geometry.

The later reviewed7234325 fixes persistent commit-file cleanup failure without hot
retry. Parent clean-close051e99a also preserves fatal dirty cleanup. Those negative
paths have separate regression evidence; successful pixels alone do not qualify them.


## Inline movement acceptance

Frozen application branch7432cb7 on Linux Vulkan passes actual WorldView movement,
with source controller4e34703, forward-neighbor ordering504f573 and owned material
cancellation fix46eb7e5. It uses the application GPU service and real native child,
actual selected-build source files, original/prior READONLY stores and only the
private proof cache for writes. The diagnostic injects map selection/catalog UI
metadata, not source qualification; its empty area catalog is not zone evidence.

During45 forced16-yard forward steps at40ms intervals,46 images publish over1838ms.
After stopping and resizing, the accepted view reaches9/9 textured tiles (4partial,
0height fallback). The image contains2997 sampled terrain-colored pixels and root
inspected actual textured hills at the formerly sky-only viewpoint. Cleanup waits
actual child/source retirement and returns atlas claims0. These event counts are
not a sustained FPS benchmark or a guarantee for arbitrary hardware/source coverage.

Private evidence: `inline-historical-cancellation-native.log`,
`inline-run-7/inline-world.png`, `ActualInlineWorldProof.java` and the immutable
`inline-frozen-7432cb7/manifest.json`, all beneath the private directory above.
No acquired artwork or diagnostic caches are committed. Earlier `inline-run-1` and
`inline-run-3` sky-only frames and `inline-run-4/5` cancellation failures are NOT
accepted passes; they motivated the subsequent causal fixes.

Independent authored native-source lifecycle proofbf0a2d2 covers hidden initial
qualification before seed, software/GPU transition, hide/resume and clean close.
Permanent-sky regression4e34703 proves progress to all9 new tiles without a fake
presentation ACK; retry89a4b72 holds real commit-file cleanup to verify bounded
retry with immutable membership. These are separate from the actual-image proof.
Larger distance, city rendering, liquids and Metal/D3D runtime behavior remain open.
Published alpha20 and its frozen launcher are unchanged; inline enablement currently
exists on the integration branch pending full release gates.


## Final release-candidate confirmation

Frozen integrated9dbad51 repeats the accepted inline movement against the final
source await/EOF guards, decoder cancellation fix, fast-exit lifecycle correction
and coherent CPU-owner stamp. PID959420 remains unchanged during45forced steps;
46images publish over1832ms, final9textured tiles (4partial) contain2997terrain
samples, and shutdown leaves no native children and zero atlas claims. Root inspected
the candidate image. The final record is private `inline-release-candidate-native.log`
and `inline-run-9-candidate/inline-world.png`; runtime hashes are frozen in
`inline-frozen-9dbad51/manifest.json`. Full local checks pass485tasks; the bundled
launcher/UI/native smoke passes40shaders using a verified stock packaging JDK.
Hosted and five-platform publication gates are still required before claiming release.
