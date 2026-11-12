# Pinned source inventory

C# compatibility is not a goal (owner decision, 2026-11-05). This file records how the Java port relates to the original C# implementation as background; differences are product choices, not defects.

Reference: `VibeMiner.Formats` at `4fd92d2a36add50c9b82e1c92bf72706e8d0df2b`. Every Images/Models source file is accounted for below. Java uses nested immutable messages instead of one public file per C# record; pure parsers accept `AssetSource` rather than mutable `Stream` position.

| C# source | Java implementation |
| --- | --- |
| Images/Blp2Contracts.cs | images/Blp2Types.java |
| Images/Blp2Decoder.cs, Blp2Blocks.cs | images/Blp2Decoder.java |
| Images/BlpFormatException.cs | images/BlpFormatException.java |
| Images/BlpImageResults.cs | images/BlpImageTypes.java |
| Images/BlpImages.cs | images/BlpImages.java, BlpImagePayload.java |
| Models/M2Contracts.cs | models/M2Types.java |
| Models/M2Input.cs | models/M2Input.java, AssetSource.java, AssetSources.java |
| Models/M2ModelReader.cs | models/M2ModelReader.java |
| Models/M2SkinGeometryCore.cs, M2SkinReader.cs | models/M2SkinReader.java |
| Models/M2GeometrySkinReader.cs | models/M2GeometrySkinReader.java |
| Models/M2StaticGeometry.cs | models/M2StaticGeometry.java |
| Models/M2StaticMaterials.cs | models/M2StaticMaterials.java |
| Models/M2Combiners.cs | models/M2Combiners.java |
| Models/M2Appearance.cs | models/M2Appearance.java |
| Models/ModelFormatException.cs | models/ModelFormatException.java |
| Models/Files/BuildModels.cs | models/BuildModels.java, BuildModelTypes.java |
| Models/Geometry/GeometryArtifactContracts.cs | models/GeometryTypes.java |
| Models/Geometry/GeometryArtifactWorkBudget.cs | models/GeometryArtifactWorkBudget.java |
| Models/Geometry/GeometryArtifactEncoding.cs | models/ModelGeometryArtifacts.java |
| Models/Geometry/ModelGeometryArtifacts.cs | models/ModelGeometryArtifacts.java |
| Models/Geometry/ModelGeometryArtifacts.Binding.cs | models/ModelGeometryArtifacts.java |
| Models/Geometry/ModelGeometryArtifacts.Verification.cs | models/ModelGeometryArtifacts.java |

The pinned reader's unsupported codecs/profiles/semantics remain explicit refusals. This module adds no guessed texture binding, resized BLP pixels, material/pose/visibility authority, unsupported-model fallback or synthesized protected bytes. Generated-store layout is unchanged; VMIM payloads and cache identities are compatible.

Java-specific seams: `Cancellation` is the Files/access token; generated-store calls have a registered linked cancellation token. `CompletableFuture` parser tasks use bounded fail-fast virtual-thread admission. A private adapter transaction monitor serializes decoded-stream seek/read operations without holding the stream's worker monitor. Typed file failures retain state, code and observation time. Java has no mutable parser stream position to require at zero or restore at EOF; the random-access source contract supplies exact bytes and actual length, and length is checked again at completion.

Validation covers authored fixtures, corruption/truncation/cancellation, source ownership and limits; it does not certify every retail asset or matching physical resource usage. Native Windows machine validation remains the repository's deferred platform task.
