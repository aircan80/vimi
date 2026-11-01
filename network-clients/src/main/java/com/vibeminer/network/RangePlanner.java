package com.vibeminer.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure C#-compatible archive planning; index membership, never size, chooses ranged vs loose. */
public final class RangePlanner {
    public static final String SIZE_CONFLICT = "SIZE_CONFLICT",
            OVERLAP = "RANGE_OVERLAP",
            LOCATION_INVALID = "LOCATION_INVALID";

    private RangePlanner() {}

    public record Location(CdnKey archive, long offset, long size) {
        public Location {
            Objects.requireNonNull(archive);
        }
    }

    public record ObjectToPlan(CdnKey key, Long size, List<Location> locations) {
        public ObjectToPlan {
            Objects.requireNonNull(key);
            locations = List.copyOf(locations);
        }
    }

    public record Member(CdnKey key, long offsetInRange, long size) {}

    public record Range(CdnKey archive, long offset, long length, List<Member> members) {
        public Range {
            members = List.copyOf(members);
        }

        public long memberBytes() {
            return members.stream().mapToLong(Member::size).sum();
        }

        public long gapBytes() {
            return length - memberBytes();
        }
    }

    public record Loose(CdnKey key, Long size) {}

    public record Rejection(CdnKey key, String code, String message) {}

    public record Plan(List<Range> ranges, List<Loose> loose, List<Rejection> rejected) {
        public Plan {
            ranges = List.copyOf(ranges);
            loose = List.copyOf(loose);
            rejected = List.copyOf(rejected);
        }
    }

    public record Options(long targetBytes, long capBytes, long gapBytes) {
        public Options {
            if (targetBytes < 1 || capBytes < targetBytes || gapBytes < 0 || gapBytes > capBytes) {
                throw new IllegalArgumentException("Invalid range bounds");
            }
        }

        public Options() {
            this(8L << 20, 16L << 20, 1L << 20);
        }
    }

    public record Part(CdnKey key, byte[] bytes) {
        public Part {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record Chosen(CdnKey key, Location location) {}

    public static Plan plan(List<ObjectToPlan> objects) {
        return plan(objects, List.of(), new Options());
    }

    public static Plan plan(
            List<ObjectToPlan> objects, List<CdnKey> archiveOrder, Options options) {
        Objects.requireNonNull(options);
        Map<CdnKey, Integer> rank = new HashMap<>();
        for (int i = 0; i < archiveOrder.size(); i++) {
            rank.putIfAbsent(archiveOrder.get(i), i);
        }
        Comparator<CdnKey> archiveComparator =
                Comparator.comparingInt((CdnKey k) -> rank.getOrDefault(k, Integer.MAX_VALUE))
                        .thenComparing(Comparator.naturalOrder());
        Map<CdnKey, Set<Long>> sizes = new LinkedHashMap<>();
        Map<CdnKey, Set<Location>> locations = new HashMap<>();
        Map<CdnKey, Rejection> rejected = new LinkedHashMap<>();
        for (ObjectToPlan object : objects) {
            sizes.computeIfAbsent(object.key(), ignored -> new LinkedHashSet<>());
            locations.computeIfAbsent(object.key(), ignored -> new LinkedHashSet<>());
            if (object.size() != null) {
                if (object.size() < 1) {
                    reject(rejected, object.key(), LOCATION_INVALID);
                } else {
                    sizes.get(object.key()).add(object.size());
                }
            }
            for (Location location : object.locations()) {
                if (location.offset() < 0
                        || location.size() < 1
                        || location.offset() > Long.MAX_VALUE - location.size()) {
                    reject(rejected, object.key(), LOCATION_INVALID);
                    continue;
                }
                locations.get(object.key()).add(location);
                sizes.get(object.key()).add(location.size());
            }
        }
        List<Loose> loose = new ArrayList<>();
        Map<CdnKey, List<Chosen>> chosen = new java.util.TreeMap<>(archiveComparator);
        for (CdnKey key : sizes.keySet()) {
            if (rejected.containsKey(key)) {
                continue;
            }
            if (sizes.get(key).size() > 1) {
                reject(rejected, key, SIZE_CONFLICT);
                continue;
            }
            Long size = sizes.get(key).isEmpty() ? null : sizes.get(key).iterator().next();
            if (locations.get(key).isEmpty()) {
                loose.add(new Loose(key, size));
                continue;
            }
            Location pick =
                    locations.get(key).stream()
                            .min(
                                    Comparator.comparing(Location::archive, archiveComparator)
                                            .thenComparingLong(Location::offset))
                            .orElseThrow();
            chosen.computeIfAbsent(pick.archive(), ignored -> new ArrayList<>())
                    .add(new Chosen(key, pick));
        }
        List<Range> ranges = new ArrayList<>();
        for (var group : chosen.entrySet()) {
            List<Chosen> members = group.getValue();
            members.sort(
                    Comparator.comparingLong((Chosen c) -> c.location().offset())
                            .thenComparing(Chosen::key));
            boolean[] bad = new boolean[members.size()];
            int reach = -1;
            long reachEnd = Long.MIN_VALUE;
            for (int i = 0; i < members.size(); i++) {
                Location location = members.get(i).location();
                if (location.offset() < reachEnd) {
                    bad[i] = true;
                    bad[reach] = true;
                }
                if (location.offset() + location.size() > reachEnd) {
                    reachEnd = location.offset() + location.size();
                    reach = i;
                }
            }
            List<Chosen> kept = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                if (bad[i]) {
                    reject(rejected, members.get(i).key(), OVERLAP);
                } else {
                    kept.add(members.get(i));
                }
            }
            for (int start = 0; start < kept.size(); ) {
                long begin = kept.get(start).location().offset(),
                        end = begin + kept.get(start).location().size();
                int next = start + 1;
                while (next < kept.size()) {
                    Location l = kept.get(next).location();
                    if (l.offset() - end > options.gapBytes()
                            || end - begin >= options.targetBytes()
                            || l.offset() + l.size() - begin > options.capBytes()) {
                        break;
                    }
                    end = l.offset() + l.size();
                    next++;
                }
                List<Member> planned = new ArrayList<>();
                for (int i = start; i < next; i++) {
                    Chosen c = kept.get(i);
                    planned.add(
                            new Member(
                                    c.key(), c.location().offset() - begin, c.location().size()));
                }
                ranges.add(new Range(group.getKey(), begin, end - begin, planned));
                start = next;
            }
        }
        return new Plan(ranges, loose, new ArrayList<>(rejected.values()));
    }

    private static void reject(Map<CdnKey, Rejection> rejected, CdnKey key, String code) {
        rejected.putIfAbsent(
                key, new Rejection(key, code, "The object cannot be planned: " + code));
    }

    public static List<Part> split(Range range, byte[] reply) {
        if (reply.length != range.length()) {
            throw new IllegalArgumentException("Reply differs from planned range length");
        }
        List<Part> parts = new ArrayList<>();
        for (Member member : range.members()) {
            if (member.offsetInRange() < 0
                    || member.size() < 1
                    || member.offsetInRange() > reply.length - member.size()) {
                throw new IllegalArgumentException("Member outside range");
            }
            parts.add(
                    new Part(
                            member.key(),
                            Arrays.copyOfRange(
                                    reply,
                                    (int) member.offsetInRange(),
                                    (int) (member.offsetInRange() + member.size()))));
        }
        return List.copyOf(parts);
    }
}
