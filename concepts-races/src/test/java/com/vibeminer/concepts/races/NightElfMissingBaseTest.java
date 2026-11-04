package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.MemoryGeneratedStore;
import com.vibeminer.rendering.models.PreviewMesh;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NightElfMissingBaseTest {
    @Test
    void actualMissingDiffuseResultNeverPresentsAnIncompleteBody() {
        var build = new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
        var files =
                new MemoryFiles()
                        .add(
                                build,
                                "enUS",
                                new FileTypes.BuildFile(
                                        1,
                                        null,
                                        FileTypes.FilePathSource.NONE,
                                        0L,
                                        "c".repeat(32),
                                        FileTypes.FileFormat.UNKNOWN,
                                        FileTypes.FileFormatSource.NONE,
                                        FileTypes.FileStorage.STORED,
                                        false),
                                new byte[0],
                                List.of())
                        .forBuild(build, "enUS", Cancellation.NONE)
                        .join()
                        .files();
        var tables =
                new MemoryTables()
                        .add(build, "enUS", List.of())
                        .forBuild(build, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var generated = new MemoryGeneratedStore();
        var service = new RacesService(tables, files, generated);
        try {
            var mesh =
                    new PreviewMesh(
                            new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                            new int[] {0, 1, 2},
                            List.of(new PreviewMesh.Part(0, 0, 3)));
            var base =
                    new RaceMediaTypes.Preview(
                            RaceTypes.State.PARTIAL,
                            new RaceMediaTypes.Variant(7, 0, 1, 1, 1),
                            mesh,
                            Set.of(0),
                            List.of(),
                            false,
                            null);
            var plan =
                    new RaceAppearancePlan.Plan(
                            List.of(new RaceAppearancePlan.Canvas(1, 4, 4)),
                            List.of(
                                    new RaceAppearancePlan.Layer(
                                            1, 100, 0, 0, 4, 4, 1, 0, "Authored base")),
                            List.of(),
                            RaceAppearanceProfiles.Profile.NIGHT_ELF_MALE);
            var prepared =
                    new RaceAppearance.Prepared(
                            base,
                            plan,
                            GeneratedKey.hash(
                                    "missing-night-elf-base".getBytes(StandardCharsets.UTF_8)),
                            List.of(),
                            null);
            var result =
                    new RaceAppearance(
                                    service,
                                    new RaceMedia(service, tables, files, generated),
                                    tables,
                                    files,
                                    generated)
                            .render(prepared, Cancellation.NONE);
            assertNull(result.appearance());
            assertEquals(RaceTypes.State.UNAVAILABLE, result.state());
            assertTrue(result.message().startsWith("The base skin texture is unavailable"));
            assertTrue(
                    result.issues().stream().anyMatch(issue -> issue.startsWith("Texture #100")));
            assertEquals(0, generated.puts());
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
            files.close();
        }
    }
}
