package com.vibeminer.concepts.quests;

import com.vibeminer.access.Cancellation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable union of provider facts; null means unknown, empty text means known empty. */
public final class QuestCatalog {
    private QuestCatalog() {}

    public enum Provider {
        BLIZZARD,
        QUESTIE,
        POLESTAR,
        EG_LINK
    }

    public record Source(
            Provider provider, String version, String product, String build, String locale) {}

    public record Observation(
            long id, String title, Integer requiredLevel, Integer questLevel, Source source) {
        public Observation {
            if (id <= 0 || id > 0xffffffffL) {
                throw new IllegalArgumentException("Invalid quest ID");
            }
            java.util.Objects.requireNonNull(source);
        }
    }

    public record Quest(
            long id,
            String title,
            Integer requiredLevel,
            Integer questLevel,
            List<Observation> evidence) {
        public Quest {
            evidence = List.copyOf(evidence);
        }
    }

    public record Snapshot(List<Quest> quests, List<String> diagnostics) {
        public Snapshot {
            quests = List.copyOf(quests);
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
        var result = new ArrayList<Quest>();
        for (var entry : byId.entrySet()) {
            token.check();
            var evidence = entry.getValue();
            evidence.sort(Comparator.comparing(o -> o.source().provider()));
            String title = null;
            Integer requiredLevel = null, questLevel = null;
            for (var value : evidence) {
                if (title == null) {
                    title = value.title();
                }
                if (requiredLevel == null) {
                    requiredLevel = value.requiredLevel();
                }
                if (questLevel == null) {
                    questLevel = value.questLevel();
                }
            }
            result.add(new Quest(entry.getKey(), title, requiredLevel, questLevel, evidence));
        }
        return new Snapshot(result, diagnostics);
    }
}
