package com.vibeminer.questie.cli;

import com.vibeminer.generated.Cancellation;
import com.vibeminer.questie.QuestieRepository;
import com.vibeminer.questie.QuestieTypes.EntityKind;
import com.vibeminer.questie.QuestieTypes.Flavor;
import com.vibeminer.questie.QuestieTypes.Progress;
import com.vibeminer.questie.internal.Json;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Explicit command-line harness for the standalone backend; performs no GUI or WoW-build work. */
public final class QuestieCli {
    private QuestieCli() {}

    public static void main(String[] args) {
        if (args.length < 3 || !args[0].equals("--data")) {
            usage();
            return;
        }
        Path directory = Path.of(args[1]);
        String command = args[2];
        String[] values = java.util.Arrays.copyOfRange(args, 3, args.length);
        var token = new Cancellation();
        try (var repository = QuestieRepository.open(directory, token).join()) {
            var shutdown =
                    new Thread(
                            () -> {
                                token.cancel();
                                repository.closeAsync().join();
                            },
                            "questie-cli-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                var progress = new Reporter();
                Object result =
                        switch (command) {
                            case "check" -> {
                                var check = repository.checkForUpdates(token).join();
                                yield Map.of(
                                        "latest",
                                        check.latest() == null ? "" : check.latest().tag(),
                                        "previous",
                                        check.previousTag() == null ? "" : check.previousTag(),
                                        "changed",
                                        check.changed(),
                                        "offline",
                                        check.offline(),
                                        "message",
                                        check.message() == null ? "" : check.message());
                            }
                            case "releases" ->
                                    repository.releases(token).join().stream()
                                            .map(
                                                    release ->
                                                            Map.of(
                                                                    "tag",
                                                                    release.tag(),
                                                                    "publishedAt",
                                                                    release.publishedAt()
                                                                            .toString(),
                                                                    "artifacts",
                                                                    release.assets().stream()
                                                                            .map(
                                                                                    asset ->
                                                                                            asset
                                                                                                    .name())
                                                                            .toList()))
                                            .toList();
                            case "sources" -> repository.sourceSnapshots(token).join();
                            case "versions" -> repository.versions(token).join();
                            case "download" -> {
                                Flavor flavor =
                                        values.length > 1
                                                ? Flavor.parse(values[1])
                                                : Flavor.FOREVER;
                                if (values.length == 0 || values[0].equals("latest")) {
                                    yield repository.downloadLatest(flavor, progress, token).join();
                                }
                                yield repository
                                        .download(
                                                repository.release(values[0], token).join(),
                                                flavor,
                                                progress,
                                                token)
                                        .join();
                            }
                            case "prepare" -> {
                                need(values, 1);
                                yield repository.prepare(values[0], progress, token).join();
                            }
                            case "sync" ->
                                    repository
                                            .downloadAndPrepareLatest(
                                                    values.length == 0
                                                            ? Flavor.FOREVER
                                                            : Flavor.parse(values[0]),
                                                    progress,
                                                    token)
                                            .join();
                            case "entity" -> {
                                need(values, 3);
                                try (var handle = repository.openVersion(values[0], token).join()) {
                                    yield handle.lookup(
                                                    EntityKind.parse(values[1]),
                                                    Long.parseLong(values[2]),
                                                    values.length > 3 ? values[3] : "enUS",
                                                    token)
                                            .join();
                                }
                            }
                            case "search" -> {
                                need(values, 3);
                                try (var handle = repository.openVersion(values[0], token).join()) {
                                    yield handle.search(
                                                    EntityKind.parse(values[1]),
                                                    values[2],
                                                    values.length > 3 ? values[3] : "enUS",
                                                    values.length > 4
                                                            ? Integer.parseInt(values[4])
                                                            : 0,
                                                    values.length > 5
                                                            ? Integer.parseInt(values[5])
                                                            : 100,
                                                    token)
                                            .join();
                                }
                            }
                            case "item-sources" -> {
                                need(values, 2);
                                try (var handle = repository.openVersion(values[0], token).join()) {
                                    yield handle.itemSources(
                                                    Long.parseLong(values[1]),
                                                    values.length > 2 ? values[2] : "enUS",
                                                    token)
                                            .join();
                                }
                            }
                            case "compare" -> {
                                need(values, 4);
                                yield repository
                                        .compareEntity(
                                                values[0],
                                                values[1],
                                                EntityKind.parse(values[2]),
                                                Long.parseLong(values[3]),
                                                values.length > 4 ? values[4] : "enUS",
                                                token)
                                        .join();
                            }
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown command: " + command);
                        };
                System.out.println(new String(Json.bytes(json(result)), StandardCharsets.UTF_8));
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        }
    }

    private static void need(String[] values, int count) {
        if (values.length < count) {
            throw new IllegalArgumentException(
                    "Not enough command arguments; run without arguments for usage");
        }
    }

    private static void usage() {
        System.out.println(
                """
            Usage: vimi-questie --data DIRECTORY COMMAND [arguments]
              check                            Check official latest release (retains local observations)
              releases | sources | versions    List cached releases, original packages or prepared data
              download [latest|TAG] [FLAVOR]    Keep official original package; default Forever
              prepare SOURCE_ID                Prepare a retained package into its own SQLite snapshot
              sync [FLAVOR]                    Check latest, download if needed, and prepare
              entity VERSION KIND ID [LOCALE]  Read one local entity
              search VERSION KIND TEXT [LOCALE] [OFFSET] [LIMIT]
              item-sources VERSION ITEM_ID [LOCALE]
              compare LEFT_VERSION RIGHT_VERSION KIND ID [LOCALE]
            KIND: item, npc, quest, object. No WoW installation or opened build is required.
            """);
    }

    private static Object json(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof Instant || value instanceof Path) {
            return value.toString();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(QuestieCli::json).orElse(null);
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, entry) -> result.put(key.toString(), json(entry)));
            return result;
        }
        if (value instanceof Iterable<?> values) {
            var result = new ArrayList<>();
            for (var entry : values) {
                result.add(json(entry));
            }
            return result;
        }
        if (value.getClass().isRecord()) {
            var result = new LinkedHashMap<String, Object>();
            for (var component : value.getClass().getRecordComponents()) {
                try {
                    result.put(component.getName(), json(component.getAccessor().invoke(value)));
                } catch (IllegalAccessException | InvocationTargetException error) {
                    throw new IllegalArgumentException(error);
                }
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported CLI output type: " + value.getClass());
    }

    private static final class Reporter implements Consumer<Progress> {
        private long last;
        private Object stage;

        @Override
        public void accept(Progress value) {
            long now = System.nanoTime();
            if (stage != value.stage()
                    || now - last > 250_000_000L
                    || value.total() != null && value.done() == value.total()) {
                stage = value.stage();
                last = now;
                System.err.println(
                        value.stage()
                                + " "
                                + value.done()
                                + (value.total() == null ? "" : "/" + value.total())
                                + " "
                                + value.unit()
                                + " · "
                                + value.detail());
            }
        }
    }
}
