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
per-user Vimi data. Windows uses LOCALAPPDATA/Vimi, macOS uses its Application
Support directory, and Linux follows XDG_DATA_HOME (otherwise ~/.local/share/vimi).
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

The Linux ARM64 JavaFX SDK is verified against its published SHA-256 because the
matching JavaFX25.0.1 Maven artifacts are absent. Other platforms use the pinned
Gradle dependencies. Dependency legal notices and upstream source links are
preserved; see [runtime notices](third-party-runtime.md).

Local package-image verification (requires matching JDK25 and a prepared native
SQLite path):

```sh
./gradlew :gui:installDist
python scripts/release/package_app.py --tag v0.1.0-alpha.1 \
  --sqlite /absolute/path/to/sqlite-library --javafx-legal /path/to/verified-sdk/legal \
  --output build/prerelease-test --image-only
```

Every target first runs scripts/ci/javafx_sdk.py with --platform to obtain matching
verified OpenJFX legal notices. Linux ARM64 also passes its artifacts directory as
`-PjavafxSdkRepository=...` to Gradle, and passes --javafx-native/--javafx-legal to the
packager. Those are build inputs; end users receive the files in the package.

The packaged application image is launched on every platform. Installer creation is
checked, but installing/uninstalling the MSI/DMG/DEB/RPM is not automated yet.
Windows installation is the next manual validation on the user’s Windows machine.

## SDK-backed JavaFX checks

When supplying `-PjavafxSdkRepository` for the Linux ARM64 SDK adapter, also
supply `-PjavafxNativeLibrary=/absolute/path/to/verified/native`. Its class JARs
and native libraries are separate; GUI JavaExec checks need both. The desktop
workflow forwards both verified outputs before packaging. Ordinary Maven-backed
platform builds keep their bundled native-library lookup.

The alpha.21 publication run exposed the missing native path in the detached image
publication check and was blocked. Its tag is preserved. A local causal proof using
class-only JavaFX JARs fails without the native path and passes with it; the GUI
image publication task also passes (95 tasks). Actual ARM64 verification remains
a five-platform package gate for the next prerelease.
