# Bundled runtime notices and source locations

Vimi/VibeMiner source and its GPLv3 license are at https://github.com/aircan80/vimi.
The package includes the root LICENSE. Existing branding exports are unchanged
GPL-3.0-or-later artwork from VibeMiner; hashes/provenance are in
`gui/src/main/resources/branding/README.md` in the source repository.

- OpenJDK25: GPLv2 with the Classpath Exception and component-specific notices.
  Runtime legal files are retained under the bundled Java runtime's legal folder.
  Temurin builds/source: https://github.com/adoptium/temurin25-binaries and
  https://github.com/openjdk/jdk25u .
- OpenJFX25.0.1: GPLv2 with the Classpath Exception and third-party notices.
  Source: https://github.com/openjdk/jfx . The matching, checksum-verified SDK legal files for base, graphics and controls
  are copied beside the application notices on every platform, including all
  component notices. The packaged JavaFX class JARs and the `javafx.graphics` native
  libraries in `native/javafx` come from that same SDK; SDK_SOURCE.txt records the
  exact SDK URL and SHA-256 used. On Windows, `native/javafx` also holds the
  Microsoft Universal C Runtime and Visual C++ runtime DLLs that OpenJFX redistributes
  (`api-ms-win-*.dll`, `ucrtbase.dll`, `vcruntime140*.dll`, `msvcp140*.dll`), the same
  files the Maven `javafx-graphics` Windows JAR embeds.
- SQLite: public-domain SQLite library. Version/hash is recorded per package.
  Source and license: https://github.com/sqlite/sqlite and https://sqlite.org/copyright.html .
- OkHttp4.12.0, Okio3.6.0, Kotlin1.9.10: Apache-2.0 notices retained in their JARs.
  Sources: https://github.com/square/okhttp , https://github.com/square/okio ,
  https://github.com/JetBrains/kotlin .
- Jackson Core2.20.1: Apache-2.0 notices retained in the JAR.
  Source: https://github.com/FasterXML/jackson-core .
- zstd-jni1.5.7-6: its BSD notices and bundled zstd notices remain in the JAR.
  Source: https://github.com/luben/zstd-jni .
- J-Ogg Vorbis1.0.6: original license/notice are in the formats-audio JAR.
  Source: https://github.com/stephengold/j-ogg-all . Required attribution:
  This software is based on or using the J-Ogg library available from
  http://www.j-ogg.de and copyrighted by Tor-Einar Jarnbjo.
- JavaMP3 1.0.1 (`fr.delthas:javamp3`): MIT, Copyright (c) 2017 delthas. The license
  text is in the formats-audio JAR (`META-INF/LICENSE-JavaMP3`).
  Source: https://github.com/delthas/JavaMP3 .

- LWJGL3.4.3: BSD-3-Clause; bgfx API156: BSD-2-Clause.
  Explicit notices are bundled in `rendering-bgfx` resources under
  `com/vibeminer/rendering/bgfx/legal/`; Maven runtime JARs do not contain them.
  Sources: https://github.com/LWJGL/lwjgl3/tree/3.4.3 ,
  https://github.com/LWJGL-CI/bgfx/tree/780f7bf00a29573473b90dcfab9f92a7d8687702 .
  Runtime artifact classifiers, pinned URLs and SHA-256 values are recorded in
  `rendering-bgfx/lwjgl-runtime.json`.
  The build-only bgfx shader compiler is not installed or downloaded by the app.

The package contains no downloaded game databases, CDN payloads, player data,
credentials or local development configuration. Game data is acquired through the
application's configured Blizzard/GitHub sources after launch.
