# Security review

Review date: 2026-11-04. Baseline: main `dc7c5a4` (including the merged World
streaming implementation). This is a source-based review, not a penetration test,
security certification or exhaustive dependency vulnerability assessment. No live
endpoints, private libraries, downloaded game data or credentials were used. No
application behavior was changed. The companion [data-flow inventory](data-flow-inventory.md)
describes destinations, user triggers and storage in detail.

The important boundaries are the desktop user's account, HTTPS source hosts and their
publishers, downloaded game/community formats, writable local caches and native
libraries/drivers. No critical or high-severity exploitable issue was confirmed by
this review. The findings below are hardening and privacy risks with explicit
conditions; absence of a confirmed exploit is not evidence that all inputs are safe.

## Findings and priorities

Severity describes realistic impact and prerequisites, not a CVSS score. Medium
means a meaningful release/trust-boundary gap requiring a compromised supply chain
or malicious input; low means a conditional local/privacy or operational risk.

### S1 — Medium: release authentication relies on the hosting account

Evidence: [package_app.py](../scripts/release/package_app.py) `main`,
[publish_prerelease.py](../scripts/release/publish_prerelease.py) `validate`/`main`,
[desktop-prereleases.yml](../.github/workflows/desktop-prereleases.yml) and
[prerelease policy](prereleases.md).

Packages are explicitly unsigned/unnotarized. Publication verifies complete
platform manifests, commit/tag identities, asset lengths and SHA-256 values,
creates SHA256SUMS and publishes through a draft release. These detect accidental
corruption and mismatched build outputs. They do not independently authenticate a
publisher when both binaries and checksum files can be replaced through the same
GitHub account or release workflow. An attacker with release authority could
substitute executable content; users also lack normal platform publisher identity.
This is a release trust gap, not a demonstrated bypass of GitHub TLS or CI.

Remediation: add Windows signing and macOS signing/notarization, publish verifiable
build attestations and independently authenticated checksum signatures, and
protect signing/release environments and tags. Document verification steps and
retain the current immutable-release policy. Re-review trusted publisher and
workflow permissions when signing is added.

### S2 — Medium: dependency versions are locked, but artifact trust is incomplete

Evidence: [gui/build.gradle](../gui/build.gradle) `repositories` and
`dependencyLocking` (also other module build files), checked-in module lockfiles,
[wrapper properties](../gradle/wrapper/gradle-wrapper.properties),
[native_sqlite.py](../scripts/ci/native_sqlite.py) `locate`, and
[desktop-prereleases.yml](../.github/workflows/desktop-prereleases.yml) package job.

Gradle dependency versions are locked, Maven Central is the normal repository,
GitHub Actions use commit hashes, and the wrapper distribution has a SHA-256 pin.
JavaFX SDKs, Windows SQLite and shader tools/includes have explicit checksum checks.
No Gradle dependency verification metadata was found in the tracked baseline.
Version locking does not reject altered bytes published under the same coordinate.
Linux/macOS SQLite comes from the runner's OS/Homebrew installation, and setup-java
selects major version 25, so those build inputs can change between builds. Package
manifests record the resulting SQLite version/hash, but that is observation rather
than a pre-execution trust pin. Malicious repository/cache/native build inputs could
execute in the build or shipped application if the associated distribution channel
is compromised. This review did not establish any currently vulnerable dependency.

Remediation: enable strict Gradle checksum/signature verification from independently
reviewed artifacts; explicitly version and verify JDK/native packaging inputs or
record authenticated provenance for them. Retain the architecture/version checks
and per-platform packaged smoke tests. Add scheduled dependency/advisory review;
lockfiles alone are not an advisory scanner.

### S3 — Low: persistent history and derived content require deliberate disposal

Evidence: [Questie README](../questie/README.md), [Polestar README](../polestar/README.md),
[EG-link README](../eg-link/README.md), [prerelease policy](prereleases.md),
[BuildDownloads.java](../gui/src/main/java/com/vimi/gui/BuildDownloads.java), and the
[data-flow inventory](data-flow-inventory.md).

The library retains original acquired objects, community snapshots and generated
indexes/prepared data for offline use and reproducibility. Application uninstall
intentionally preserves the library. Per-transfer and parser size limits do not
constitute a total disk quota. A shared computer, copied backup or abandoned
library can expose selections, source provenance and acquired content; repeated
legitimate acquisitions can consume substantial disk. This is intentional storage
behavior with privacy/availability consequences, not an identified unauthorized
upload. OS permissions and encryption are the current privacy boundary.

Remediation: extend existing storage-usage reporting with explicit retention and
disposal controls, distinguish deleting
a build/catalog entry from deleting retained bytes, add an optional total disk
budget and document full-library disposal and backups. Do not silently delete
retained originals. Consider owner-only directory creation and test permissions
on each OS, especially custom data directories, rather than assuming a parent
folder is private.

## Executable and native boundaries

- Runtime subprocess sites are [VoiceDecoder.java](../formats-audio/src/main/java/com/vibeminer/formats/audio/VoiceDecoder.java)
  `decode`, [AudioStreamDecoder.java](../formats-audio/src/main/java/com/vibeminer/formats/audio/AudioStreamDecoder.java)
  `open`, [GpuPreviewService.java](../gui/src/main/java/com/vimi/gui/GpuPreviewService.java)
  `nativeCommand`/`startChild`/`startStreamingChild`, and the packaged diagnostic
  [PackageSmoke.java](../gui/src/main/java/com/vimi/gui/PackageSmoke.java).
  They use `ProcessBuilder` argument lists and the executable under `java.home`;
  these paths do not concatenate a shell command from a game filename. Child JVMs
  inherit account privileges and environment. A child process is not an OS sandbox
  and can access that account's files/network if compromised.
- Vorbis decoding validates the Ogg framing first, caps encoded input at 2 MiB,
  uses `-Xmx64m` and a 48 MiB metaspace limit, bounds PCM output and imposes an
  eight-second deadline covering pipe I/O. The child is forcibly destroyed on
  completion/cancellation/error. These are useful denial-of-service protections,
  not proof of codec memory safety or sandbox confinement. See `VoiceDecoder`,
  `OggVoiceInput`, `VorbisWorker` and `PcmClip` in `formats-audio/src/main/java`.
- World explorer streaming audio accepts byte-identified Ogg Vorbis (same framing
  preflight) or MPEG-1 audio (JavaMP3 1.0.1, unmaintained since 2017) up to 16 MiB,
  uses `-Xmx128m`/48 MiB metaspace, bounds output to 15 minutes, fails after 8 s
  without output and destroys the child on close. See `AudioStreamDecoder`,
  `AudioStreamWorker` and `PcmStream`, and [the checkpoint](world-area-audio-checkpoint.md).
- GPU preview uses LWJGL/bgfx/GLFW and OS graphics drivers in a child JVM with
  `--enable-native-access=ALL-UNNAMED`. [WorldStreamPreparedFiles.java](../rendering-bgfx/src/main/java/com/vibeminer/rendering/bgfx/WorldStreamPreparedFiles.java)
  creates random per-session directories; bootstrap uses CREATE_NEW and
  NOFOLLOW_LINKS. [WorldStreamSession.java](../rendering-bgfx/src/main/java/com/vibeminer/rendering/bgfx/WorldStreamSession.java)
  and [WorldStreamTileFile.java](../rendering-bgfx/src/main/java/com/vibeminer/rendering/bgfx/WorldStreamTileFile.java)
  reject nonregular/symlink files, validate framing, identities/checksums and
  fixed limits. World session/control nonces and hashes bind local IPC to a
  session; they are not encryption or authentication against the same user who
  can inspect or alter process memory/files. Parent PID checks and shutdown/drain
  logic limit orphan lifetime. Crash/forced termination can still leave artifacts.
- SQLite uses Java FFM native downcalls rather than a pure Java database engine.
  [SqliteLibrary.java](../object-store/src/main/java/com/vibeminer/store/SqliteLibrary.java)
  accepts `vibeminer.sqlite.library`, otherwise uses the platform loader's SQLite
  name; packaging supplies a bundled `$APPDIR/native` path. Symbol presence is
  checked, not binary signature. Developer overrides, classpaths, native search
  paths and writable installations are trusted executable inputs. Do not load
  an arbitrary downloaded native library or treat an untrusted SQLite library as
  data. Native SQLite, decompression and GPU/driver vulnerabilities remain outside
  Java bounds checks.

## Untrusted-data parsing and resource controls

Game data and community publications are data, not trusted code. Content hashes
prove consistency with the selected metadata, not that the publisher is benign.
Community manifests and their checksums usually share the same publisher/channel;
a compromised publisher can produce a consistently hashed malicious input.

| Surface | Observed controls | Residual risk / follow-up |
| --- | --- | --- |
| CDN/version/definition/listfile responses | Official constrained URL builders; TLS validation; explicit redirect policy; exact ranges and response/body limits; deadlines, cancellation and bounded admission. See `network-clients/README.md`, `SourceUrls`, `CdnRoute`, `NetworkClients`. | Re-audit each new source separately; source-host restrictions do not prove every payload trustworthy. Parser and cumulative-memory bounds still matter. |
| BLTE, ENCODING, ROOT, archive indexes | Validated counts and bounded reads/decode; BLTE decoded ceiling documented at 512 MiB in `builds/RESOLVER.md`. | A valid maximum can still be expensive, especially with multiple operations. No fuzzing or worst-case aggregate memory test was performed here. |
| Images/models/world geometry and cached derivatives | BLP/model/DB2 structural validation, versioned identities, image/geometry limits; World packet maximum 2,284,544 bytes in `WorldStreamEnvelope`; tile counts, finite numeric values and fingerprints checked before consumption. | Native upload/driver paths, cancellation races and all historical formats were not exhaustively audited. Preserve malformed-input tests and add coverage-guided fuzzing at parser boundaries. |
| Questie ZIP and baked data | `BakedArchive` hashes archive, caps 10,000 entries/128 MiB each/512 MiB aggregate declared expansion, rejects duplicate/unsafe names, bounds actual member reads and checks producer/contract/flavor. Reads selected members directly; does not extract or execute Lua. Source repository validates acquisition and notices. | Large admitted archives still need memory/CPU. No general claim that arbitrary ZIPs are safe. Review nested baked/base64/compression parsers and aggregate preparation budgets with hostile fixtures. |
| Polestar / EG-link JSON(L) | Per-file 16 MiB and snapshot 64 MiB ceilings in `PolestarRepository` and `EgLinkRepository`; commit/source identity and hashes retained; prepared data separate from originals. | Static publisher metadata can be wrong. Preserve provenance in UI/export; do not infer runtime game truth from publication integrity alone. |
| Local SQLite / packs / record files | Schema/format versions, lengths, checksums and recovery paths; parameter binding in native SQLite adapters. | A user-supplied library/database is a parser input, not a safe executable bundle. Review SQLite defensive/trusted-schema settings and fuzz damaged stores; SQL-injection immunity was not established for every call site. |

Archive traversal was checked in the relevant acquisition/build helpers:
Questie does not extract the upstream ZIP; JavaFX SDK extraction selects known
members and checks extraction bounds; Windows SQLite extracts only the named DLL
following checksum verification. See `BakedArchive`,
[scripts/ci/javafx_sdk.py](../scripts/ci/javafx_sdk.py),
[scripts/ci/native_sqlite.py](../scripts/ci/native_sqlite.py) and their tests.
The release packager creates archives from its own staging image; it is not an
end-user arbitrary-archive installer. No traversal exploit was confirmed. This is
not a review of all platform installer internals.

## Privacy, secrets and transport

All reviewed production source URL builders use HTTPS. Normal certificate and
hostname validation is retained; test-only loopback transports are separate from
production. `NetworkClients` disables automatic redirects/retries and applies
source-specific policies. Questie has a separate JDK HTTP client and validates
each redirect against its GitHub host set; do not assume every network subsystem
inherits the same limits. `QuestieSourceRepository.request` bounds redirect hops,
header time and body idle time. No Blizzard account login or application API token
was required by the reviewed public-data flows. Signed GitHub release-asset query
strings can be transient bearer capabilities; network-client failure URLs are
redacted. Further error/UI/log-path testing should verify redaction in the
separate Questie implementation and nested exception causes too.

The source search did not find an external analytics SDK, automatic crash-report
uploader or application log-upload pipeline in the reviewed production modules.
The network-activity module reports local transfer counters; its name does not
indicate third-party analytics. This is a qualified static observation, not proof
of no network activity, no SDK-internal collection, or no OS crash reporting.
Source access is not always a manual download action: `DataSourcesService.policy`
defaults sources to enabled, with daily TACT key checks; `checkDue` can fetch keys
automatically after startup refresh. MANUAL source-update scheduling is not a global
offline mode: enabled sources can still be used by preparation. Review source
settings before assuming launch or browsing is network-silent.

Content fetches disclose IP address, time, requested source/build/file and headers
to source operators/CDNs. Local settings/provenance/cache records are not encrypted
by an application key. BLTE/Salsa20 content decryption is game-format handling,
not library encryption at rest. Use OS account controls and disk/backup encryption
for local confidentiality. JVM/native fatal-error reports and OS diagnostics may
contain paths/process details or memory; no application-controlled guaranteed
redaction/deletion policy for those artifacts was established.

## C# helpers and development-only inputs

At the time of this review, C# compatibility harnesses lived under module
`src/interopTest/csharp` and read a developer-provided C# reference checkout and
NuGet packages as trusted build inputs. #274 removed them (C# compatibility is not
a goal, owner decision 2026-11-05), so the build no longer runs .NET, restores NuGet
packages or reads a reference checkout.

Packaging/CI also runs Python, Gradle, `jpackage`, `gh`, OS package managers and
native shader tools. These are build/release network and executable surfaces,
separate from desktop content fetches. The shader compiler script verifies pinned
tool/include hashes before execution; release publication has write permissions
only in its publish job, and checkout disables credential persistence. This review
did not audit hosted account settings, branch/environment protection, secret values
or runner images.

## Scope and next review

Main `dc7c5a4` is the reviewed source baseline, not a claim that an earlier published
alpha contains every surface above. Candidate and proposal statements do not
establish shipped behavior. Later next-feature worktrees are excluded unless
explicitly identified by the companion inventory; global WMO/water/other candidate
features need their own review before release.

Recommended next work: implement S1/S2 release provenance controls; test local
permissions, crash residue and deletion semantics; audit diagnostic redaction
end to end; fuzz binary/archive/record decoders and test aggregate resource
pressure; inventory exact packaged dependency/native versions against advisories.
No builds/tests were executed for this documentation-only review. Validation was
source/path inspection and Markdown-link checking; existing tests mentioned above
are evidence of intended controls, not newly reproduced security test results.
