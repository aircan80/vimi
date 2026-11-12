package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.models.BuildDisplayModels;
import com.vibeminer.concepts.models.DisplayModelTypes;
import com.vibeminer.rendering.bgfx.WorldStreamBodyFile;
import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.world.BodyAnimation;
import com.vibeminer.rendering.world.CapsuleCollision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The walking body as the World draws it: one creature display's World binding converted as the
 * World converts creatures ({@link WorldTileCreatures#convert}), its body sequences ({@link
 * BuildDisplayModels#worldSequences}) and its recorded size ({@link BuildDisplayModels#bodySize}),
 * packed into the renderer's body file. Walking draws a murloc (display 441), flying a gryphon
 * (display 1149).
 */
final class WorldBodyModel {
    /** The walking body's creature display: a murloc (model 31). */
    static final long MURLOC = 441;

    /** The flying body's creature display: a gryphon (model 792), the taxi gryphon. */
    static final long GRYPHON = 1149;

    /**
     * The packed file, the capsule from the recorded box and scale (null when the tables do not
     * say), and each sequence's loop length and move speed by AnimationData ID.
     */
    record Built(
            byte[] file,
            CapsuleCollision.Shape capsule,
            Map<Integer, Long> durations,
            Map<Integer, Float> moveSpeeds) {
        Built {
            Objects.requireNonNull(file);
            durations = Map.copyOf(durations);
            moveSpeeds = Map.copyOf(moveSpeeds);
        }
    }

    private WorldBodyModel() {}

    /**
     * Builds display {@code display}'s body; throws when the display cannot be a body (no World
     * binding, no Stand sequence, or a model the World would not draw).
     */
    static Built build(BuildDisplayModels displays, long display, Cancellation token)
            throws Exception {
        var preview = displays.worldPreview(display, token).join();
        token.check();
        if (preview == null || preview.appearance() == null) {
            throw new IllegalStateException("The body's display has no World model"
                    + (preview == null || preview.message() == null ? "" : ": " + preview.message()));
        }
        var answers = displays.worldSequences(display, BodyAnimation.SEQUENCES, token).join();
        token.check();
        var rest = preview.appearance();
        var sequences = new ArrayList<Integer>();
        var animations = new ArrayList<PreviewAnimation>();
        var durations = new LinkedHashMap<Integer, Long>();
        var moveSpeeds = new LinkedHashMap<Integer, Float>();
        var leftOut = new HashSet<Integer>();
        for (var answer : answers.entrySet()) {
            var idle = answer.getValue();
            var animation = idle.animation();
            if (idle.state() != DisplayModelTypes.IdleState.AVAILABLE || animation == null
                    || animation.viewDependent() || !animation.matches(rest.mesh())) continue;
            sequences.add(answer.getKey());
            animations.add(animation);
            durations.put(answer.getKey(), animation.durationMillis());
            moveSpeeds.put(answer.getKey(), animation.moveSpeed());
            for (int vertex : idle.leftOut()) leftOut.add(vertex);
        }
        if (!durations.containsKey(BodyAnimation.STAND))
            throw new IllegalStateException("The body's display has no Stand sequence for the World");
        var sources = new ArrayList<Integer>();
        var converted = WorldTileCreatures.convert(preview,
                leftOut.stream().mapToInt(Integer::intValue).sorted().toArray(), sources::add, token);
        if (converted.model() == null)
            throw new IllegalStateException("The World does not draw the body's model: " + converted.detail());
        var model = converted.model();
        if (sources.size() != model.vertexCount())
            throw new IllegalStateException("Body vertex map differs from its model");
        var images = new ArrayList<WorldStreamBodyFile.Image>();
        for (var image : model.images())
            images.add(new WorldStreamBodyFile.Image(image.width(), image.height(), image.rgba()));
        CapsuleCollision.Shape capsule = null;
        double scale = 1;
        var size = displays.bodySize(display, token).join();
        if (size != null) {
            scale = size.scale();
            capsule = BodyAnimation.capsule(size.geoBox(), size.scale());
        }
        var body = new WorldStreamBodyFile.Body(model.vertices(), model.indices(), model.ranges(),
                model.blends(), images, sources.stream().mapToInt(Integer::intValue).toArray(),
                (float) scale, sequences.stream().mapToInt(Integer::intValue).toArray(),
                List.copyOf(animations));
        return new Built(WorldStreamBodyFile.encode(body), capsule, durations, moveSpeeds);
    }
}
