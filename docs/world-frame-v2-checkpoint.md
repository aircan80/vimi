# Separate World frame v2 codec

This is an isolated mapping codec, not a production renderer or GUI integration.
The v1 model/terrain frame files and their consumers are unchanged.

The file is exactly4,800,256 bytes:64 immutable header bytes and three slots,
each with64 descriptor bytes plus1,600,000 pixel bytes. It binds the128-bit
session nonce and32-byte scope digest. Dimensions are fixed to at most800×500.
Descriptors carry captured camera/content/policy revisions and a monotonic
sequence; pixels are opaque BGRA8. CRC32C covers descriptor bytes8..55 and60..63,
then the exact payload, as specified by the reviewed hot protocol.

One producer and one latest-frame consumer own separate handles. Publication
release-stores the odd stamp, full-fences, copies metadata/pixels, then
release-stores the even stamp. The reader acquire-snapshots a candidate, checks
session, dimensions, revisions and an immutable acknowledged policy barrier BEFORE
detached image allocation, copies/checks pixels and CRC, full-fences and rechecks
the same even stamp. Skipped stale/corrupt slots cannot relabel their pixels.
Sequence exhaustion refuses reuse or wrapping. In-progress reserved sequences
cannot be reused after a failed producer.

The caller supplies immutable presentation bounds: current acknowledged policy
and dimensions, minimum policy/presented camera revision, maximum sent camera,
and minimum presented/maximum acknowledged content. A newer desired camera must
not raise the minimum merely because it was requested. Metadata eligibility does
not acknowledge content or end activity; accepted FX presentation remains a later
controller gate. The codec does not derive these bounds from untrusted frame data.

Readbacks must capture tickets at submission, never relabel older pixels at
completion. The later owner must discard out-of-order completed readbacks before
publication. No native owner, status protocol, upload credit, watchdog or source
authority is implemented here.

Frames own one detached int array exposed through read-only IntBuffer views.
Mappings never escape, and shared foreign arenas unmap deterministically on
close. A64KiB CRC scratch buffer is reused and reset between reads. Writer input
must remain stable until write returns; it is not a GPU in-flight pointer.
Creation/open uses NOFOLLOW_LINKS and exact sizing in an owned private session
directory. As with v1, per-call header/size validation can perform a metadata
query; native scheduling must account for publishing/copying rather than claiming
this is a zero-cost operation.

Eight authored file tests cover fixed size, repeated scratch reuse and detached
ownership, acknowledgement/content/camera bounds, resize causality, odd stamps,
CRC damage, session changes, v1 rejection, maximum dimensions/sequence, invalid
publication and independent concurrent producer/consumer integrity. Cross-profile
native integration, torn-readback completion, actual FX policy barriers and frame
pacing remain later gates. No new FPS or total process-memory claim is made.

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :rendering-bgfx:check
```
