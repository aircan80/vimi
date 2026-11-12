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
    private final String build;

    /** Outside displays read for the NPCs of one tile at once; null outside {@link #forNpcs}. */
    private final Map<Long, List<NpcDisplays.External>> read;

    WorldNpcDisplays(Supplier<BuildCreatures> creatures, Observed observed, String build) {
        this(creatures, observed, build, null);
    }

    private WorldNpcDisplays(
            Supplier<BuildCreatures> creatures,
            Observed observed,
            String build,
            Map<Long, List<NpcDisplays.External>> read) {
        this.creatures = Objects.requireNonNull(creatures);
        this.observed = Objects.requireNonNull(observed);
        this.build = build == null || build.isBlank() ? "unknown" : build;
        this.read = read;
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
        var absent = NpcPreviews.absentDisplays(handle, TableTypes.Priority.BACKGROUND, token);
        var outside = new java.util.ArrayList<Long>();
        for (long npcId : npcIds) {
            if (npcId < 0 || npcId > 0xffffffffL) continue;
            var client = handle.getData(npcId, TableTypes.Priority.BACKGROUND, token).join();
            token.check();
            if (NpcDisplays.resolve(client, clientProvenance(), null, absent).source()
                    != NpcDisplays.Source.CLIENT) {
                outside.add(npcId);
            }
        }
        if (outside.isEmpty()) return this;
        var found = observed.displays(outside, token);
        token.check();
        var tile = new java.util.HashMap<Long, List<NpcDisplays.External>>();
        for (long npcId : outside) tile.put(npcId, found.getOrDefault(npcId, List.of()));
        return new WorldNpcDisplays(creatures, observed, build, Map.copyOf(tile));
    }

    @Override
    public WorldTileCreatures.Chosen choose(long npcId, Cancellation token) throws Exception {
        var handle = creatures.get();
        if (handle == null)
            return new WorldTileCreatures.Chosen(CreatureModelPolicy.unknown("Creature data is not open"), null);
        if (npcId < 0 || npcId > 0xffffffffL)
            return new WorldTileCreatures.Chosen(
                    CreatureModelPolicy.unknown("NPC ID is outside the client creature range"), null);
        var client = handle.getData(npcId, TableTypes.Priority.BACKGROUND, token).join();
        token.check();
        var absent = NpcPreviews.absentDisplays(handle, TableTypes.Priority.BACKGROUND, token);
        var resolution = NpcDisplays.resolve(client, clientProvenance(), null, absent);
        if (resolution.source() == NpcDisplays.Source.CLIENT) {
            // The World's single-display rule for client records.
            var choice = CreatureModelPolicy.choose(client);
            return new WorldTileCreatures.Chosen(
                    withSource(choice, clientProvenance()),
                    choice.displayId() == null ? null : CreatureModelPolicy.scale(client, choice.displayId()));
        }
        var externals = read == null ? null : read.get(npcId);
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
        var chain = handle.displayChain(shown.displayId(), TableTypes.Priority.BACKGROUND, token).join();
        token.check();
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
