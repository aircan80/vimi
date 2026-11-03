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
  Source: https://github.com/openjdk/jfx . The original JAR notices remain intact;
  the Linux ARM64 SDK legal files are copied beside the application notices.
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

The package contains no downloaded game databases, CDN payloads, player data,
credentials or local development configuration. Game data is acquired through the
application's configured Blizzard/GitHub sources after launch.
