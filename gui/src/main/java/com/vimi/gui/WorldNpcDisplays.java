package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.BuildCreatures;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.creatures.NpcDisplays;
import com.vibeminer.formats.tables.TableTypes;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The World's display for each NPC, through the resolver the NPCs page uses ({@link NpcDisplays}):
 * the client {@code Creature} record, then each outside source in order (downloaded EG Link
 * snapshots, then the bundled history). A display the opened build's {@code CreatureDisplayInfo}
 * proves missing is never used.
 *
 * <p>The rule differs by source. A client record keeps the World's single-display rule ({@link
 * CreatureModelPolicy}): several declared displays are left undrawn, because the client picks one
 * per spawn. An outside source records which displays the NPC was observed with; the World draws
 * the one the NPCs page shows, the highest probability and then the lowest ID, so the same data
 * always draws the same model. The scale is the client data's for that display. Every choice's
 * detail names its source, so a pick says where its display came from.
 */
final class WorldNpcDisplays implements WorldTileCreatures.Chooser {
    /**
     * Several NPCs' outside displays at once: for each NPC asked, its sources in the order they are
     * tried (empty when none applies). A source that cannot be read fails the call, so a passing
     * failure is never remembered as "no display".
     */
    @FunctionalInterface
    interface Observed {
        Observed NONE = (npcIds, token) -> Map.of();

        Map<Long, List<NpcDisplays.External>> displays(Collection<Long> npcIds, Cancellation token)
                throws Exception;
    }

    private final Supplier<BuildCreatures> creatures;
    private final Observed observed;
    private final Supplier<Object> revision;
    private final String build;

    /** Outside displays read for the NPCs of one tile at once; null outside {@link #forNpcs}. */
    private final Map<Long, List<NpcDisplays.External>> read;

    /** Why the tile's outside read failed; set on a tile's chooser, which then reads no more. */
    private final String unavailable;

    /** Client records read for one tile's NPCs at once; empty outside {@link #forNpcs}. */
    private final Map<Long, com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult> clients;

    /** Display chains, shared by this chooser and every tile chooser made from it. */
    private final Chains chains;

    /**
     * Display chains already read, for the session (#175): the chooser asks for the same displays
     * on every tile, twice per NPC (whether a display is absent, then the chain of the one shown).
     * Bounded, and dropped whenever the creature data handle or the chooser's {@link #revision()}
     * changes. A chain whose display or model is not readable yet is not kept, so it is asked again.
     */
    static final class Chains {
        static final int MAX = 4096;
        private Object handle, revision;
        private final Map<Long, com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain> known =
                new java.util.LinkedHashMap<>(64, 0.75f, true) {
                    @java.io.Serial private static final long serialVersionUID = 1L;

                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<Long, com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain> eldest) {
                        return size() > MAX;
                    }
                };
        private long reads;

        com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChain chain(BuildCreatures handle, Object revision,
                long displayId, Cancellation token) {
            synchronized (this) {
                if (this.handle != handle || !Objects.equals(this.revision, revision)) {
                    known.clear();
                    this.handle = handle;
                    this.revision = revision;
                }
                var chain = known.get(displayId);
                if (chain != null) return chain;
            }
            var chain = handle.displayChain(displayId, TableTypes.Priority.BACKGROUND, token).join();
            token.check();
            synchronized (this) {
                reads++;
                if (this.handle == handle && Objects.equals(this.revision, revision) && lasting(chain.state()))
                    known.put(displayId, chain);
            }
            return chain;
        }

        /** Chains read from the creature data so far (tests). */
        synchronized long reads() {
            return reads;
        }

        private static boolean lasting(com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState state) {
            return switch (state) {
                case DISPLAY_NOT_READABLE, MODEL_NOT_READABLE, DISPLAY_UNKNOWN, MODEL_UNKNOWN -> false;
                default -> true;
            };
        }
    }

    /** As {@link NpcPreviews#absentDisplays}, through the session's chains. */
    private java.util.function.LongPredicate absent(BuildCreatures handle, Object revision, Cancellation token) {
        return displayId -> {
            if (displayId < 1 || displayId > Integer.MAX_VALUE) return true;
            try {
                var state = chains.chain(handle, revision, displayId, token).state();
                return state == com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState.DISPLAY_ABSENT
                        || state == com.vibeminer.concepts.creatures.CreatureTypes.CreatureDisplayChainState
                                .INVALID_DISPLAY_REFERENCE;
            } catch (RuntimeException unavailable) {
                token.check();
                return false;
            }
        };
    }

    WorldNpcDisplays(Supplier<BuildCreatures> creatures, Observed observed, String build) {
        this(creatures, observed, () -> null, build);
    }

    /** {@code revision} says what can change the outside displays ({@link #revision()}). */
    WorldNpcDisplays(
            Supplier<BuildCreatures> creatures,
            Observed observed,
            Supplier<Object> revision,
            String build) {
        this(creatures, observed, revision, build, null, null, Map.of(), new Chains());
    }

    private WorldNpcDisplays(
            Supplier<BuildCreatures> creatures,
            Observed observed,
            Supplier<Object> revision,
            String build,
            Map<Long, List<NpcDisplays.External>> read,
            String unavailable,
            Map<Long, com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult> clients,
            Chains chains) {
        this.creatures = Objects.requireNonNull(creatures);
        this.observed = Objects.requireNonNull(observed);
        this.revision = Objects.requireNonNull(revision);
        this.build = build == null || build.isBlank() ? "unknown" : build;
        this.read = read;
        this.unavailable = unavailable;
        this.clients = Map.copyOf(clients);
        this.chains = Objects.requireNonNull(chains);
    }

    /** The session's display chains (tests). */
    Chains chains() {
        return chains;
    }

    /** The outside sources in use (snapshots and bundled history): choices are remade when it changes. */
    @Override
    public Object revision() {
        return revision.get();
    }

    private String clientProvenance() {
        return "client Creature record, build " + build;
    }

    /**
     * A chooser for one tile's NPCs whose outside displays are read in one call for those the
     * client record gives no display.
     */
    @Override
    public WorldTileCreatures.Chooser forNpcs(Collection<Long> npcIds, Cancellation token)
            throws Exception {
        var handle = creatures.get();
        if (handle == null || npcIds.isEmpty()) return this;
        Object current = revision();
        var absent = absent(handle, current, token);
        // The tile's client records are asked for together, then kept for its choices.
        var asked = new java.util.LinkedHashMap<Long,
                java.util.concurrent.CompletableFuture<com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult>>();
        for (long npcId : npcIds) {
            if (npcId < 0 || npcId > 0xffffffffL || asked.containsKey(npcId)) continue;
            asked.put(npcId, handle.getData(npcId, TableTypes.Priority.BACKGROUND, token));
        }
        var clients = new java.util.HashMap<Long, com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult>();
        var outside = new java.util.ArrayList<Long>();
        for (var each : asked.entrySet()) {
            var client = each.getValue().join();
            token.check();
            clients.put(each.getKey(), client);
            if (NpcDisplays.resolve(client, clientProvenance(), null, absent).source()
                    != NpcDisplays.Source.CLIENT) {
                outside.add(each.getKey());
            }
        }
        if (outside.isEmpty())
            return new WorldNpcDisplays(creatures, observed, revision, build, null, null, clients, chains);
        Map<Long, List<NpcDisplays.External>> found;
        try {
            found = observed.displays(outside, token);
        } catch (java.util.concurrent.CancellationException canceled) {
            throw canceled;
        } catch (Exception failed) {
            // Reported once for the tile; its NPCs without a client display wait for a later tile.
            token.check();
            WorldDiagnostics.degraded("creature lookups", "World outside display lookup failed", failed);
            return new WorldNpcDisplays(creatures, observed, revision, build, Map.of(),
                    failed.getClass().getSimpleName(), clients, chains);
        }
        token.check();
        var tile = new java.util.HashMap<Long, List<NpcDisplays.External>>();
        for (long npcId : outside) tile.put(npcId, found.getOrDefault(npcId, List.of()));
        return new WorldNpcDisplays(creatures, observed, revision, build, Map.copyOf(tile), null, clients, chains);
    }

    @Override
    public WorldTileCreatures.Chosen choose(long npcId, Cancellation token) throws Exception {
        var handle = creatures.get();
        if (handle == null)
            return new WorldTileCreatures.Chosen(CreatureModelPolicy.unknown("Creature data is not open"), null);
        if (npcId < 0 || npcId > 0xffffffffL)
            return new WorldTileCreatures.Chosen(
                    CreatureModelPolicy.unknown("NPC ID is outside the client creature range"), null);
        var client = clients.get(npcId);
        if (client == null) {
            client = handle.getData(npcId, TableTypes.Priority.BACKGROUND, token).join();
            token.check();
        }
        Object current = revision();
        var absent = absent(handle, current, token);
        var resolution = NpcDisplays.resolve(client, clientProvenance(), null, absent);
        if (resolution.source() == NpcDisplays.Source.CLIENT) {
            // The World's single-display rule for client records.
            var choice = CreatureModelPolicy.choose(client);
            return new WorldTileCreatures.Chosen(
                    withSource(choice, clientProvenance()),
                    choice.displayId() == null ? null : CreatureModelPolicy.scale(client, choice.displayId()));
        }
        var externals = read == null ? null : read.get(npcId);
        if (externals == null && unavailable != null) {
            throw new WorldTileCreatures.ChoiceLater("outside displays unavailable: " + unavailable);
        }
        if (externals == null) {
            externals = observed.displays(List.of(npcId), token).getOrDefault(npcId, List.of());
            token.check();
        }
        resolution = NpcDisplays.resolve(client, clientProvenance(), externals, absent);
        if (resolution.shown() == null) {
            var policy = CreatureModelPolicy.choose(client);
            if (resolution.malformed() > 0) {
                // A recorded NPC whose displays could not be read: said so, never "no display".
                return new WorldTileCreatures.Chosen(
                        new CreatureModelPolicy.Choice(
                                CreatureModelPolicy.State.UNKNOWN,
                                CreatureModelPolicy.Reason.OBSERVED_MALFORMED,
                                0,
                                null,
                                null,
                                null,
                                resolution.detail()),
                        null);
            }
            if (policy.state() == CreatureModelPolicy.State.QUALIFIED) {
                return new WorldTileCreatures.Chosen(
                        withSource(policy, clientProvenance()),
                        CreatureModelPolicy.scale(client, policy.displayId()));
            }
            return new WorldTileCreatures.Chosen(
                    new CreatureModelPolicy.Choice(
                            policy.state(),
                            policy.reason(),
                            policy.displays(),
                            policy.displayId(),
                            policy.modelId(),
                            null,
                            resolution.detail()),
                    null);
        }
        var shown = resolution.shown();
        var chain = chains.chain(handle, current, shown.displayId(), token);
        var choice =
                CreatureModelPolicy.observed(
                        (int) shown.displayId(),
                        chain,
                        resolution.candidates().size(),
                        resolution.detail() + " · source: " + resolution.provenance());
        return new WorldTileCreatures.Chosen(
                choice,
                choice.state() == CreatureModelPolicy.State.QUALIFIED ? CreatureModelPolicy.scale(chain) : null);
    }

    /** {@code choice} with its source named in its detail. */
    private static CreatureModelPolicy.Choice withSource(CreatureModelPolicy.Choice choice, String source) {
        return new CreatureModelPolicy.Choice(
                choice.state(),
                choice.reason(),
                choice.displays(),
                choice.displayId(),
                choice.modelId(),
                choice.modelFileDataId(),
                choice.detail() + " · source: " + source);
    }
}
