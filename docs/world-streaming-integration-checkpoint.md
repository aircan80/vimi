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
  -PreferenceRoot=../vibeminer
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

## Integrated source, building and startup-close verification

At `07df3fc`, the reviewed internal qualified terrain factory and source-close
ordering fixes, parent cancellation lane, optional cold WMO child and bounded status
parser are integrated. Combined source/rendering-world/bgfx/GUI checks pass149tasks
(`/tmp/vimi-world-source-cold-parent-combined-check.log`). Actual integrated Vulkan
cold-child proof passes valid pixels/camera/resize/EOF/CLOSE and malformed, missing,
absent annex paths (`/tmp/vimi-world-cold-child-integrated-native.log`).

The accepted matching CLOSE now arms the independent child watchdog from the control
reader during startup. Wrong nonce does not arm it. Integrated bgfx checks and
actual streaming child proof pass40tasks: same child3content commits/15camera changes,
EOF and unread stdout shutdown in11.874s, owned exit73
(`/tmp/vimi-world-early-close-integrated-root-check.log`). This is not proof that a
particular GPU driver can unwind a blocked call.

Root also reran the actual qualified source proof against integrated classes, original
and prior stores read-only/localOnly, with fresh scratch. Map0 observed row, root777947,
256areas and unchanged geometry digest produce a qualified1,048,576-byte atlas with
tex0777950/twelve diffuse bindings. One output retires and atlas claims return to zero
(`/tmp/vimi-qualified-terrain-integrated-source-proof.log`). No game assets enter Git.
Public parent/source APIs and inline controller wiring still remain; none of these
checks enables persistent streaming or buildings in the released GUI.
