package com.vibeminer.concepts.npcs;

import com.vibeminer.access.Cancellation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable union of provider facts; null means unknown, empty text means known empty. */
public final class NpcCatalog {
    private NpcCatalog() {}

    public enum Provider {
        BLIZZARD,
        QUESTIE,
        POLESTAR,
        EG_LINK
    }

    public record Source(
            Provider provider, String version, String product, String build, String locale) {}

    public record Levels(int min, int max) {
        public Levels {
            if (min < 0 || max < min || max > 1000) {
                throw new IllegalArgumentException("Invalid NPC levels");
            }
        }

        @Override
        public String toString() {
            return min == max ? Integer.toString(min) : min + "–" + max;
        }
    }

    public record Observation(long id, String name, String subname, Levels levels, Source source) {
        public Observation {
            if (id <= 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Invalid NPC ID");
            }
            java.util.Objects.requireNonNull(source);
        }
    }

    public record Npc(
            long id, String name, String subname, Levels levels, List<Observation> evidence) {
        public Npc {
            evidence = List.copyOf(evidence);
        }
    }

    public record Snapshot(List<Npc> npcs, List<String> diagnostics) {
        public Snapshot {
            npcs = List.copyOf(npcs);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    @FunctionalInterface
    public interface Loader {
        List<Observation> load(Cancellation token) throws Exception;
    }

    public record Input(Provider provider, Loader loader) {}

    public static Snapshot load(List<Input> inputs, Cancellation token) {
        token.check();
        var observations = new ArrayList<Observation>();
        var diagnostics = new ArrayList<String>();
        for (var input : inputs) {
            token.check();
            try {
                var rows = input.loader().load(token);
                observations.addAll(rows);
                diagnostics.add(input.provider() + ": " + rows.size() + " records");
            } catch (java.util.concurrent.CancellationException canceled) {
                throw canceled;
            } catch (Exception failure) {
                token.check();
                diagnostics.add(input.provider() + ": unavailable; other sources retained");
            }
        }
        return merge(observations, diagnostics, token);
    }

    public static Snapshot merge(
            List<Observation> observations, List<String> diagnostics, Cancellation token) {
        token.check();
        Map<Long, List<Observation>> byId = new TreeMap<>();
        for (var value : observations) {
            token.check();
            byId.computeIfAbsent(value.id(), ignored -> new ArrayList<>()).add(value);
        }
        var result = new ArrayList<Npc>();
        for (var entry : byId.entrySet()) {
            token.check();
            var evidence = entry.getValue();
            evidence.sort(Comparator.comparing(o -> o.source().provider()));
            String name = null, title = null;
            Levels levels = null;
            for (var value : evidence) {
                if (name == null) {
                    name = value.name();
                }
                if (title == null) {
                    title = value.subname();
                }
                if (levels == null) {
                    levels = value.levels();
                }
            }
            result.add(new Npc(entry.getKey(), name, title, levels, evidence));
        }
        return new Snapshot(result, diagnostics);
    }
}
