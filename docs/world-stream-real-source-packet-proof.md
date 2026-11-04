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

At integration002eeb8, Commit/Frame cleanup-failure review remains open: a persistent
commit-file deletion failure must not hot retry indefinitely. This successful-path
proof does not approve that failure path, enable the GUI, or establish Metal/D3D
runtime behavior. Published alpha20 and its frozen launcher remain unchanged.
