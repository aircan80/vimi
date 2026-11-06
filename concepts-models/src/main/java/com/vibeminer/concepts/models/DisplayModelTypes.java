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

    public record Reference(
            long displayId,
            long modelId,
            long modelFileDataId,
            List<Long> textureVariations,
            Long extendedDisplayInfoId,
            Long creatureGeosetDataId) {
        public Reference {
            textureVariations = List.copyOf(textureVariations);
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

    public record Idle(
            IdleState state, Reference reference, PreviewAnimation animation, String message) {
        public Idle {
            if (state == null
                    || (state == IdleState.AVAILABLE) != (animation != null)
                    || message == null) {
                throw new IllegalArgumentException("Inconsistent idle animation result");
            }
        }

        public static Idle unavailable(String message) {
            return new Idle(IdleState.UNAVAILABLE, null, null, message);
        }
    }
}
