# Authored warm-camera v1 inline baseline

Test-only diagnostic based on production main `05b1521`. No renderer, transport,
controller, WorldView or application settings change. Run on qualified Vulkan
hardware with an owned display:

```sh
DISPLAY=:0 XAUTHORITY=<existing-owned-test-display-auth-path> ./gradlew :gui:worldInlineCadenceProof
```

The opt-in task uses JavaFX software Prism, matching the authored proof environment.
It creates its own brief window and subprocess, synthetic native geometry and private
temporary scene/mapping files. It never opens a library, game asset, settings store,
existing application window or frozen launcher. Closure asserts every owned child has
terminated. Source construction happens before FX initialization.

## What is measured

The unchanged `GpuViewerMain`, `GpuPreviewService` and `TerrainGpuPreview` render nine
full synthetic ADTs (589,824 triangles), height shading, at800×500. An AnimationTimer
continuously changes camera position/yaw/pitch for2seconds of actual motion warm-up,
then samples8seconds. It calls the real controller, real bounded stdin controls, real
native pipeline/readback, existing v1 mmap reader/CRC and real FX WritableImage
`setPixels`. The output image is reused at stable dimensions.

No production instrumentation API was necessary. Existing test seams permit wrapping
only the frame factory and pixel sink. A separate statistics-only observer reads three
8-byte stamps from the private v1 frame file at a nominal2ms cadence, without copying
pixels or performing file IO on FX. It observes native published sequence advancement;
this is **not** a count of submitted draws or GPU-completed commands. Endpoint stamp
observation can lag due to scheduling; no hard2ms polling guarantee is claimed.

The request→acceptance timestamp ends after FX `setPixels`, and revision lag compares
that frame's revision to the latest requested revision at that moment. Acceptance
means this sink accepted the pixels, not compositor/display scanout or a hardware fence.
The sink records at most4096samples and request timestamps; this is opt-in profiling,
not a CI timing threshold. Outputs are numeric `cadence.txt` and `accepted.csv`.

## One observed Linux Vulkan run

After2seconds of continuous motion warm-up, the8,004.5ms sample retained one live child:

| Counter | Observed |
| --- | ---: |
| Camera requests |496 |
| Native mapped sequence advancement |232 |
| Service frame reads |229 |
| FX accepted pixel uploads |229 |

| Duration / lag | p50 | p95 | maximum |
| --- | ---: | ---: | ---: |
| Accepted-frame interval, ms |35.183 |36.189 |39.867 |
| Request age at FX acceptance, ms |45.039 |61.982 |69.524 |
| Requested camera revision lag |2 |3 |4 |
| Bridge read/copy, ms |1.972 |2.574 |6.715 |
| FX setPixels, ms |1.749 |2.867 |3.086 |

The full GUI check and this native diagnostic passed together (125 tasks). These are
single-machine observations, not universal FPS or latency guarantees. The native
sequence counter can reveal publications skipped by latest-frame polling, while the
service and sink counts distinguish mapped-frame consumption from FX acceptance.

## Cadence implications and limits

Current production source explicitly sleeps15ms between coalesced control writes,
limits native submissions to33,333,333ns, and waits33ms between parent frame polls.
A successful parent read adds measured copying/CRC time before its next33ms wait.
The observed roughly35ms median accepted interval is consistent with that structure;
this run does not isolate causation or justify changing every delay together.

Bridge read/copy includes mapping validation, detached int-buffer copying, CRC and the
small diagnostic wrapper. FX timing excludes the later scene pulse/compositor. There
are no GPU timestamps, allocation/GC traces, texture sampling, source acquisition,
WorldView/minimap/zone handling, simulated input dispatch, neighborhood replacement,
network work or simultaneous-data stress. Other GPU/backend/Prism configurations may
behave differently. The first stationary-context trial lacked motion warm-up and is
not the final steady-state baseline recorded above.

Next tuning experiments should retain this fixed workload and change one reviewed
factor at a time: parent poll cadence, native submission pacing, or bounded frame-owner
reuse. Persistent tile uploads must add camera-continuity and frame-content provenance
checks before any comparison is described as hot-streaming performance. No optimization
is included here.
