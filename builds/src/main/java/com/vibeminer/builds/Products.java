package com.vibeminer.builds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reviewed labels plus bounded natural-order discovery of future WoW product IDs. */
public final class Products {
    public record Reviewed(String id, String label) {}

    public record Listed(String id, String label, boolean reviewed) {}

    public static final int MAX_UNREVIEWED = 64;
    public static final List<Reviewed> REVIEWED =
            List.of(
                    new Reviewed("wow", "Retail"),
                    new Reviewed("wowt", "PTR"),
                    new Reviewed("wowxptr", "PTR 2"),
                    new Reviewed("wow_beta", "Beta"),
                    new Reviewed("wow_classic", "Classic Progression"),
                    new Reviewed("wow_classic_ptr", "Classic Progression PTR"),
                    new Reviewed("wow_classic_era", "Classic Era"),
                    new Reviewed("wow_classic_era_ptr", "Classic Era PTR"),
                    new Reviewed("wow_classic_era_beta", "Classic Era Beta"),
                    new Reviewed("wow_anniversary", "Anniversary"),
                    new Reviewed("wow_classic_beta", "Forever Beta"),
                    new Reviewed("wow_cn_beta", "Forever Beta (China)"),
                    new Reviewed("wowf", "Forever"),
                    new Reviewed("wow_classic_titan", "Titan Reforged (China)"));
    public static final List<String> IDS = REVIEWED.stream().map(Reviewed::id).toList();
    public static final Comparator<String> NATURAL_ORDER = Products::naturalCompare;

    private Products() {}

    public static boolean isKnown(String id) {
        return id != null && IDS.contains(id);
    }

    public static boolean isWow(String id) {
        return id != null && id.matches("wow[a-z0-9_-]{0,61}");
    }

    public static String label(String id) {
        return REVIEWED.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown product"))
                .label();
    }

    public static String name(String id) {
        return isKnown(id) ? label(id) : id;
    }

    public static boolean looksLikeWow(String id) {
        return id.startsWith("wow");
    }

    public static List<Listed> listed(Iterable<String> unreviewed) {
        Objects.requireNonNull(unreviewed);
        List<Listed> result = new ArrayList<>();
        REVIEWED.forEach(p -> result.add(new Listed(p.id(), p.label(), true)));
        java.util.Set<String> distinct = new java.util.TreeSet<>(NATURAL_ORDER);
        for (String id : unreviewed) {
            if (isWow(id) && !isKnown(id)) {
                distinct.add(id);
            }
        }
        distinct.stream()
                .limit(MAX_UNREVIEWED)
                .forEach(id -> result.add(new Listed(id, id, false)));
        return List.copyOf(result);
    }

    private static int naturalCompare(String x, String y) {
        if (x == y) {
            return 0;
        }
        if (x == null) {
            return -1;
        }
        if (y == null) {
            return 1;
        }
        int i = 0, j = 0;
        while (i < x.length() && j < y.length()) {
            if (digit(x.charAt(i)) && digit(y.charAt(j))) {
                int a = i, b = j;
                while (i < x.length() && digit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && digit(y.charAt(j))) {
                    j++;
                }
                String left = x.substring(a, i).replaceFirst("^0+", ""),
                        right = y.substring(b, j).replaceFirst("^0+", "");
                int order = Integer.compare(left.length(), right.length());
                if (order != 0) {
                    return order;
                }
                order = left.compareTo(right);
                if (order != 0) {
                    return order;
                }
                order = Integer.compare(i - a, j - b);
                if (order != 0) {
                    return order;
                }
                continue;
            }
            if (x.charAt(i) != y.charAt(j)) {
                return Character.compare(x.charAt(i), y.charAt(j));
            }
            i++;
            j++;
        }
        return Integer.compare(x.length() - i, y.length() - j);
    }

    private static boolean digit(char value) {
        return value >= '0' && value <= '9';
    }
}
