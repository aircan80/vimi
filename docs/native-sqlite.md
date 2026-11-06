# Native SQLite distribution and validation

The layer 1 JAR uses the Java 25 foreign-function API and contains no SQLite binary,
JavaFX dependency or application launcher. A consumer supplies an architecture-matched
SQLite shared library and enables native access with `--enable-native-access=ALL-UNNAMED`.
The Java classes require a full Java 25 JDK to build; consumers need a Java 25 runtime.

## Current distribution strategy

| Platform | Development/CI source | Default library name |
| --- | --- | --- |
| Linux | Distribution SQLite package; hosted Ubuntu installs `libsqlite3-0` | `libsqlite3.so.0` |
| macOS | Platform library or package-managed SQLite; hosted checks use Homebrew SQLite explicitly | `libsqlite3.dylib` |
| Windows | Architecture-matched official SQLite DLL; hosted x64 checks download a checksum-pinned archive | `sqlite3.dll` |

A bare default name uses the operating system's native-library search rules. For a
reproducible installation, pass the absolute library path instead:

```sh
java --enable-native-access=ALL-UNNAMED -Dvibeminer.sqlite.library=/absolute/path/to/library ...
./gradlew :object-store:integrationTest -PsqliteLibrary=/absolute/path/to/library
```

On Windows use `gradlew.bat` and a DLL matching the JDK architecture, rather than
assuming the operating system architecture identifies the running JVM. The pinned
Windows helper in `scripts/ci/native_sqlite.py` supports hosted x64 runners only;
it is a CI dependency installer, not the product's installer or an ARM64 bundle.
No binaries or package-manager directories are copied into the source tree or JAR.
Future application packaging must select its native assets separately and retain
provenance, checksums and applicable notices. Release publishing is tracked in #22.

Use a thread-safe SQLite build with WAL, progress callbacks, and file-backed temporary
storage enabled. Maintenance needs temporary disk space proportional to its metadata.
A custom build forcing `SQLITE_TEMP_STORE=3` is outside the supported configuration:
SQLite's compile-time setting overrides the FILE pragma. See the upstream
[compile options](https://www.sqlite.org/compile.html) and
[temp_store documentation](https://www.sqlite.org/pragma.html#pragma_temp_store).
The loader validates required C symbols; that check does not certify all build options.

## Startup failures and checks

Missing/unloadable libraries, wrong architectures and missing required C symbols
produce `OBJECT_STORE_NATIVE_LIBRARY` from both asynchronous open APIs, before any
store directory or writer lock is created. The message names the JDK-architecture
requirement, runtime property and Gradle override. The cause retains the native
loader diagnostic for local debugging. Avoid publishing raw local exception dumps
without reviewing paths and machine/account identifiers.

Loading is process-wide. After correcting a library or its configured path, restart
the JVM. Repeated failed opens retain the same actionable error rather than poisoning
SQLite class initialization. A successfully loaded library stays loaded for the process.

```sh
./gradlew :object-store:integrationTest --tests '*SqliteLibraryTest' -PsqliteLibrary=/absolute/path/to/library
```

Fresh JVM probes cover a missing path, a non-library file, explicit override startup
and default-name startup. A controlled symbol lookup checks rejection of a missing
C symbol. Windows ships no `sqlite3.dll`, so there the default-name case puts the
directory of the `-PsqliteLibrary` DLL first on the child's `PATH` and starts
without the override property. A companion case starts a child with no `PATH`
change and expects the actionable failure. Both skip when `-PsqliteLibrary` is
missing or names a file other than `sqlite3.dll`, or when the default DLL search
path already provides `sqlite3.dll`. The override case also requires the Gradle
property. These probes do not replace clean-machine/native testing under #24.
Run the broader disk, crash and interoperability suites before accepting a new native
library build. Read-only media evidence is tracked separately in #25.

## Third-party provenance

SQLite's deliverable library is dedicated to the public domain according to its
[upstream copyright statement](https://www.sqlite.org/copyright.html). This statement
concerns upstream SQLite; a distributor's additions and surrounding package files
must be reviewed separately if they are redistributed. The current JAR does not
redistribute a native binary. The project's GPL license remains in `LICENSE`.
