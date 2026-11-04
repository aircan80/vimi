# Persistent World integration checkpoint

Draft PR153 at fd6d087 combines reviewed source/cache ownership, material drain,
World packet/frame/control codecs, native owner, bounded actor and cold WMO annex.
It is not connected to the World Explorer GUI and does not change released behavior.

Root independently reviewed actor2d83f6f and reran actual Linux Vulkan acceptance:
same child3commits,15camera revisions, stationary-camera content changes,800x500
resize, mapped pixels, cancellation, malformed-commit replay rejection, CLOSE/EOF.
A genuine unread stdout pipe stopped at573commands/1147completedwrites and its
watchdog terminated that owned child after11.884seconds. The structural cold WMO
annex f6ce9c2 has independent full-code/test review and10actually executed tests.

Combined integration validation at fd6d087 passed149tasks:

```
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew \
  :rendering-bgfx:check :rendering-world:check :concepts-world:check :gui:check \
  :rendering-bgfx:nativeGpuWorldStreamSessionProof \
  -PsqliteLibrary=/lib64/libsqlite3.so.0 \
  -PreferenceRoot=/home/aircan/Development/vibeminer
```

Local evidence is `/tmp/vimi-world-actor-annex-integration-check.log`: samechild
558878,EOF560737,blockedpipe560779,11.866seconds,actualexit73. Tests use authored
geometry/textures and no game assets or network. These results do not establish
nine-tile movement FPS, production source admission or Metal/D3D native behavior.

Remaining integration gates: reviewed partial Map selected-ID witness and actual
source factory qualification; one source-owned geometry/material/packet transaction;
parent transport with confirmed-process-exit lifetime; inline content/camera/policy
presentation barriers; real movement without renderer restart or texture flash.
Cold WMO native pass, qualified global-instance source/transform and character
customization proceed as separate reviewed milestones. Never publish this draft
as a completed streaming feature before those presentation gates pass.
