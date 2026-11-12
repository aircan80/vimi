package com.vibeminer.concepts.models;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewAnimation;
import java.util.List;
import java.util.Set;

/**
 * Actual display-linked static geometry/diffuse facts; no runtime pose or character appearance
 * claim.
 */
public final class DisplayModelTypes {
    private DisplayModelTypes() {}

    /**
     * The preview component cannot take the request now: its queue is full ({@code closed} false)
     * or it is closed. Nothing about the display was learned.
     */
    public static final class ServiceUnavailable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final boolean closed;

        public ServiceUnavailable(String message, boolean closed, Throwable cause) {
            super(message, cause);
            this.closed = closed;
        }

        public boolean closed() {
            return closed;
        }
    }

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    /**
     * A display's model references. {@code creatureGeosetDataId} is 0 when the column is absent and
     * null when it could not be read. {@code displayGeosets} holds the display's own geoset choices
     * (CreatureDisplayInfoGeosetData rows) as geoset group to value, where the group is the row's
     * GeosetIndex + 1 and the chosen section is {@code group * 100 + value} (wowdev
     * DB/CreatureDisplayInfoGeosetData: meshId = (geosetIndex + 1) * 100 + geosetValue). It is
     * empty when the display records none, null when that is not known, and a group that two rows
     * set to different values maps to {@link #CONFLICTING_GEOSET}.
     */
    public record Reference(
            long displayId,
            long modelId,
            long modelFileDataId,
            List<Long> textureVariations,
            Long extendedDisplayInfoId,
            Long creatureGeosetDataId,
            java.util.Map<Integer, Integer> displayGeosets) {
        /** A geoset group that two of the display's rows set to different values. */
        public static final int CONFLICTING_GEOSET = -1;

        public Reference {
            textureVariations = List.copyOf(textureVariations);
            displayGeosets = displayGeosets == null ? null : java.util.Map.copyOf(displayGeosets);
        }

        /**
         * Without the per-display geoset check: {@code displayGeosets} is null, which callers that
         * choose geosets must treat as unresolved.
         */
        public Reference(
                long displayId,
                long modelId,
                long modelFileDataId,
                List<Long> textureVariations,
                Long extendedDisplayInfoId,
                Long creatureGeosetDataId) {
            this(
                    displayId,
                    modelId,
                    modelFileDataId,
                    textureVariations,
                    extendedDisplayInfoId,
                    creatureGeosetDataId,
                    null);
        }
    }

    public record Preview(
            State state,
            Reference reference,
            MeshAppearance appearance,
            Set<Integer> visibleParts,
            List<String> issues,
            boolean restored,
            String message) {
        public Preview {
            visibleParts = Set.copyOf(visibleParts);
            issues = List.copyOf(issues);
        }
    }

    /** Qualification of one display's idle sequence; static previews never depend on it. */
    public enum IdleState {
        /** A Stand sequence passed the reviewed local-bone profile. */
        AVAILABLE,
        /** The source is readable but uses a profile idle evaluation does not implement. */
        UNSUPPORTED,
        /** The source or relationship could not be read; retry can reassess it. */
        UNAVAILABLE
    }

    /**
     * @param leftOut rest-surface vertices that the World leaves out (weighted to a billboard bone
     *     or a descendant of one, #384); empty for the preview profile
     */
    public record Idle(
            IdleState state,
            Reference reference,
            PreviewAnimation animation,
            String message,
            int[] leftOut) {
        public Idle {
            if (state == null
                    || (state == IdleState.AVAILABLE) != (animation != null)
                    || message == null
                    || leftOut == null
                    || leftOut.length != 0 && animation == null) {
                throw new IllegalArgumentException("Inconsistent idle animation result");
            }
            leftOut = leftOut.clone();
        }

        public Idle(
                IdleState state, Reference reference, PreviewAnimation animation, String message) {
            this(state, reference, animation, message, new int[0]);
        }

        @Override
        public int[] leftOut() {
            return leftOut.clone();
        }

        public static Idle unavailable(String message) {
            return new Idle(IdleState.UNAVAILABLE, null, null, message);
        }
    }

    /**
     * A display's recorded body size: the model's box (CreatureModelData GeoBox: minimum x, y, z
     * then maximum x, y, z, in model yards), its CreatureModelData ModelScale and the display's
     * CreatureDisplayInfo CreatureModelScale. The body's scale is their product ({@link #scale}).
     */
    public record BodySize(double[] geoBox, double modelScale, double creatureModelScale) {
        public BodySize {
            if (geoBox == null || geoBox.length != 6) {
                throw new IllegalArgumentException("A model box has six values");
            }
            geoBox = geoBox.clone();
            for (double v : geoBox) {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("Invalid model box");
                }
            }
            if (!(modelScale > 0 && modelScale < 1000)
                    || !(creatureModelScale > 0 && creatureModelScale < 1000)) {
                throw new IllegalArgumentException("Invalid model scale");
            }
        }

        @Override
        public double[] geoBox() {
            return geoBox.clone();
        }

        /** CreatureDisplayInfo.CreatureModelScale times CreatureModelData.ModelScale. */
        public double scale() {
            return creatureModelScale * modelScale;
        }
    }
}
