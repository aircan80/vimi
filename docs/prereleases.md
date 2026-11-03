# Desktop prereleases

Every published desktop version is a **GitHub prerelease**, not a stable release.
The cadence starts after the World explorer and continues after each major feature.
Tags use `v0.1.0-alpha.1` (or a later alpha/beta/rc sequence). Native installers use
numeric versions: patch×1000 + channel offset + sequence, where alpha=0, beta=300,
rc=600 and sequence is1–299. The exact semantic tag appears in the app title and
package manifest. For example alpha.1 installs as0.1.1.

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

## Build and publication

The Desktop prereleases workflow runs on prerelease tag pushes. A manual dispatch
can build/test artifacts without publishing, using `manual dispatch always validates without publishing`. It runs checks
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
  --sqlite /absolute/path/to/sqlite-library --output build/prerelease-test --image-only
```

Linux ARM64 first runs scripts/ci/javafx_sdk.py, passes its artifacts directory as
`-PjavafxSdkRepository=...` to Gradle, and passes --javafx-native/--javafx-legal to the
packager. Those are build inputs; end users receive the files in the package.

The packaged application image is launched on every platform. Installer creation is
checked, but installing/uninstalling the MSI/DMG/DEB/RPM is not automated yet.
Windows installation is the next manual validation on the user’s Windows machine.
