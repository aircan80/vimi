# Desktop prereleases

Every published desktop version is a **GitHub prerelease**, not a stable release.
The cadence starts after the World explorer and continues after each major feature.
Tags use `v0.1.0-alpha.1` (or a later alpha/beta/rc sequence). Native installers use
numeric versions: the first component is semantic major+1 (macOS requires a
positive first component), the second is semantic minor, and the third is patch×1000 + channel offset + sequence, where alpha=0, beta=300,
rc=600 and sequence is1–299. The exact semantic tag appears in the app title and
package manifest. For example v0.1.0-alpha.1 installs as1.1.1.

Artifacts include Java25, JavaFX, SQLite, the application/dependencies and license
notices. Windows has a per-user MSI installer and portable ZIP; macOS has DMG and
portable app ZIP; Linux has DEB/RPM and portable tar.gz. Architecture is included
in every filename. Linux x64/ARM64 and macOS Intel/AppleSilicon are built separately;
Windows starts with x64. Standard desktop GTK/X11 libraries remain Linux OS
requirements. These initial packages are built on Ubuntu24.04, macOS15 and Windows
Server2025 runners and are unsigned/unnotarized prereleases.

Windows testing is expected to use the downloaded installer, without installing a
JDK or checking out this repository. Installers keep runtime files separate from
per-user Vimi data. Windows uses LOCALAPPDATA/vimi, macOS uses ~/Library/Application Support/vimi,
and Linux uses $XDG_DATA_HOME/vimi (otherwise ~/.local/share/vimi).
Uninstalling an application package does not intentionally delete the library.
Back up valuable local data before testing a version that explicitly changes its
storage format; each release notes any such change.

## Roadmap delivery policy

The user authorizes grouping independently reviewed features that finish close
together into one GitHub prerelease. Work continues in parallel; do not hold an
urgent fix for unrelated unfinished work. Allocate the next unused alpha sequence
at delivery time after integration checks; never replace an existing tag.

Current priorities:

| Priority | Track |
| --- | --- |
| Immediate delivery | World navigation fix, Space ascent and map feedback; include ready Dwarf appearances, item-effects comparison and minimap after integrated checks |
| World exploration | Finish minimap and bounded greater view distance |
| World appearance | Terrain textures, then buildings/doodads and water |
| Further features | Animation, equipment and additional reviewed comparison/race profiles |
| Deferred tooling | Packaged GPU diagnostics; preserve reviewed checkpoints while world appearance takes priority |

Every included feature still requires independent review, meaningful validation,
clear release notes and successful packaged checks. Actual Metal/D3D hardware
qualification remains outstanding and cannot be claimed from cross-compilation
alone. Keep source/tag identities and release notes exact.

## Build and publication

The Desktop prereleases workflow runs on prerelease tag pushes. A manual dispatch
always builds/tests artifacts without publishing. It runs checks
on native platform runners, bundles the architecture-matched SQLite library, then
smoke-tests the actual packaged launcher, JavaFX startup, a temporary object-store
library and the bundled child JVM. It never opens the user's library or downloads
app data during smoke tests. Keeping the child java executable is required for
bounded voice decoding.

Only after every platform succeeds does publication validate all manifests,
commit/tag identity, asset sizes and hashes. It assembles SHA256SUMS, uploads the
complete asset set to a draft, then publishes it with prerelease=true and
make_latest=false. A published tag is immutable: fixes receive another prerelease
sequence rather than replacing public installer bytes. Publication is authorized
by the user's standing prerelease instruction; stable releases are not part of
this workflow.

Every platform packages JavaFX from the matching OpenJFX 25.0.1 SDK, verified
against its pinned SHA-256. The Maven `javafx-*-<platform>.jar`s embed the native
libraries, and JavaFX extracts those into the shared `~/.openjfx/cache` on every
launch, rewriting changed files without locking. Two instances starting at once on
an empty or stale cache, or another JavaFX 25.0.1 app, could then fail with
`UnsatisfiedLinkError` or a native crash. The SDK class JARs carry no natives, so
the package ships the SDK's `javafx.graphics` natives in `$APPDIR/native/javafx`
and points `java.library.path` there. The packaged app never writes a JavaFX cache.
Dependency legal notices and upstream source links are preserved; see
[runtime notices](third-party-runtime.md).

Local package-image verification (requires matching JDK25 and a prepared native
SQLite path):

```sh
python scripts/ci/javafx_sdk.py --platform linux-x64 --directory /path/to/verified-sdk
./gradlew :gui:installDist -PjavafxSdkRepository=/path/to/verified-sdk/artifacts \
  -PjavafxNativeLibrary=/path/to/verified-sdk/native
python scripts/release/package_app.py --tag v0.1.0-alpha.1 \
  --sqlite /absolute/path/to/sqlite-library --javafx-legal /path/to/verified-sdk/legal \
  --javafx-native /path/to/verified-sdk/native --output build/prerelease-test --image-only
```

Every target first runs scripts/ci/javafx_sdk.py with --platform to obtain the
matching verified OpenJFX class JARs, `javafx.graphics` natives and legal notices. It
passes the artifacts directory as `-PjavafxSdkRepository=...` to Gradle, and passes
--javafx-native/--javafx-legal to the packager. Those are build inputs; end users
receive the files in the package.

The packager refuses JavaFX JARs that embed natives, since they would extract into
`~/.openjfx/cache`. Its smoke launch sets `-Djavafx.verbose=true` and a sentinel
`-Djavafx.cachedir` through `JAVA_TOOL_OPTIONS`. It fails if the sentinel cache
appears, if any native loads from a JAR resource, or (for the UI smoke) if glass and
the other JavaFX natives do not load from inside the app image. On macOS it also
verifies the bundle seal and the signature of every bundled native library, after the
smoke run and before anything ships, `--image-only` included.

The `.deb` `Depends` is pinned through a jpackage `--resource-dir` control template to
the direct runtime needs of the bundled JDK and JavaFX natives (GTK 3, X11, GL, ALSA,
Pango/Cairo, fontconfig/freetype, xdg-utils), with `t64` alternatives. Left alone,
jpackage derives `Depends` from the whole shared-library closure using the build
runner's Ubuntu 24.04 package names, which do not install on Debian or Ubuntu 22.04.
The packager fails if the built deb's `Depends` or the rpm's `Requires` differ from
their allowlists. The pinned set was checked with `apt-get install -s` on Debian 12,
Debian 13, Ubuntu 22.04 and Ubuntu 24.04 for amd64 and arm64.

The packaged application image is launched on every platform. Installer creation is
checked, but installing/uninstalling the MSI/DMG/DEB/RPM is not automated yet.
Windows installation is the next manual validation on the user’s Windows machine.

## SDK-backed JavaFX checks

When supplying `-PjavafxSdkRepository`, also supply
`-PjavafxNativeLibrary=/absolute/path/to/verified/native`. The SDK class JARs and
native libraries are separate; GUI JavaExec checks need both. The desktop workflow
forwards both verified outputs on every platform. Ordinary Maven-backed developer
builds keep JavaFX's bundled native-library lookup; Gradle JavaExec tasks give each
task its own `javafx.cachedir`.

The alpha.21 publication run exposed the missing native path in the detached image
publication check and was blocked. Its tag is preserved. A local causal proof using
class-only JavaFX JARs fails without the native path and passes with it; the GUI
image publication task also passes (95 tasks). Actual ARM64 verification remains
a five-platform package gate for the next prerelease.
