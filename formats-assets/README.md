# Asset formats (layer 5)

Original Java 25 readers ported against C# revision `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`. `images` contains bounded BLP2 RGBA8 decoding; `models` contains native M2/SKIN structures, geometry-only parsing, static geometry/material admission and explicit combiner selection. Source authentication and exact build/file association belong to the layer 4 caller.

`AssetSource` supplies caller-owned bounded random access from arrays, channels and the Files seam. Reads are at most 64 KiB. Parser futures run on virtual threads and never occupy a lower-layer admission worker while waiting for I/O. Cancellation is explicit and checked within decoding/record loops. Returned pixels and collections are immutable owned values; no source is closed by a pure parser.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME=/private/tmp/vimi-gradle-home ./gradlew :formats-assets:test :formats-assets:check :formats-assets:assemble
```

The synthetic model/SKIN golden files contain no game bytes. They were generated with the unmodified pinned C# parser and `ModelFixtures.cs`; exact output metadata is adjacent JSON. Reproduce the fixtures with the pinned .NET 10 SDK:

```sh
/path/to/dotnet run --project formats-assets/src/interopTest/csharp/AssetsInterop.csproj -p:ReferenceRoot=/path/to/vibeminer -- formats-assets/src/test/resources/golden
```

BLP2 supports palette alpha depths0/1/4/8, BC1/2/3 and BGRA8 with the exact observed C# profiles, integer interpolation and straight alpha. Compact tail levels are described and explicitly unsupported for decoding; no pixels are fabricated. Only the selected level is checked against geometric/range bounds and read; descriptor structure is checked globally.

M2 admits unchunked264 and chunked272/274 only. SKIN preserves the raw modern zero/`0xCDCDCDCD` maximum-bones field; geometry-only reading validates declarations without reading/interpreting material records. Static opaque material and geometry-only policies are distinct. UTF-8 names, dependency ordinals, empty collision boxes, nonfinite values, all two-level indices and section provenance are checked.

This is an incremental implementation commit. The Files adapters, image cache, appearance-weight proofs and VMG1 artifact implementation are in progress and are not claimed complete here. Java output budgets account for the reference logical payload; boxed collections have higher physical overhead than C# compact arrays, and no equal throughput or memory-footprint claim is made.
