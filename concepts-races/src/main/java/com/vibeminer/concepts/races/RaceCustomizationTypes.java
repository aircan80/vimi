package com.vibeminer.concepts.races;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Bounded descriptive customization choices; requests are revalidated by the owning service. */
public final class RaceCustomizationTypes {
    private RaceCustomizationTypes() {}

    public record Pick(long optionId, long choiceId) {
        public Pick {
            id(optionId, false);
            id(choiceId, false);
        }
    }

    public record Request(long raceId, long classId, long modelId, List<Pick> picks) {
        public Request {
            context(raceId, classId, modelId);
            picks = snapshot(picks, 16);
            var seen = new HashSet<Long>();
            for (var pick : picks) {
                if (!seen.add(pick.optionId())) {
                    throw new IllegalArgumentException("Duplicate customization option");
                }
            }
        }
    }

    public record Choice(long id, String label) {
        public Choice {
            RaceCustomizationTypes.id(id, false);
            label = text(label);
        }
    }

    public record Option(long id, String label, List<Choice> choices, long defaultChoiceId) {
        public Option {
            RaceCustomizationTypes.id(id, false);
            label = text(label);
            choices = snapshot(choices, 64);
            var seen = new HashSet<Long>();
            for (var choice : choices) {
                if (!seen.add(choice.id())) {
                    throw new IllegalArgumentException("Duplicate customization choice");
                }
            }
            if (!seen.contains(defaultChoiceId)) {
                throw new IllegalArgumentException("Default choice is not an option member");
            }
        }
    }

    public record Catalog(
            RaceTypes.State state,
            long raceId,
            long classId,
            long modelId,
            List<Option> options,
            List<String> issues,
            String message) {
        public Catalog {
            Objects.requireNonNull(state);
            context(raceId, classId, modelId);
            options = snapshot(options, 16);
            int choices = 0;
            var seen = new HashSet<Long>();
            for (var option : options) {
                choices += option.choices().size();
                if (!seen.add(option.id()) || choices > 256) {
                    throw new IllegalArgumentException("Customization catalog exceeds bounds");
                }
            }
            issues = snapshot(issues, 32).stream().map(RaceCustomizationTypes::text).toList();
            if (message != null) {
                message = text(message);
            }
        }
    }

    private static void context(long race, long characterClass, long model) {
        id(race, false);
        id(characterClass, true);
        id(model, false);
    }

    private static void id(long value, boolean zero) {
        if (value < (zero ? 0 : 1) || value > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid customization context/ID");
        }
    }

    private static <T> List<T> snapshot(List<T> values, int maximum) {
        Objects.requireNonNull(values);
        if (values.size() > maximum) {
            throw new IllegalArgumentException("Customization list exceeds bounds");
        }
        var copy = new ArrayList<T>();
        var iterator = values.iterator();
        while (iterator.hasNext()) {
            if (copy.size() == maximum) {
                throw new IllegalArgumentException("Customization list exceeds bounds");
            }
            copy.add(Objects.requireNonNull(iterator.next()));
        }
        return List.copyOf(copy);
    }

    private static String text(String value) {
        Objects.requireNonNull(value);
        if (value.length() > 256) {
            throw new IllegalArgumentException("Customization text exceeds bounds");
        }
        return value;
    }
}
