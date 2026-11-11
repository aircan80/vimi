package com.vibeminer.rendering.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded evaluator for one looping bone sequence over a native-Z-up preview mesh.
 *
 * <p>Each bone's local transform is {@code T(pivot + translation) * R(rotation) * S(scale) *
 * T(-pivot)}, composed under its parent. Vertices use up to four linear-blend influences. A
 * spherical billboard bone keeps its card members at their rest offsets, scaled by its own scale
 * keys, around its evaluated pivot (moved by its own translation keys), so the existing
 * camera-facing card renderer stays responsible for orientation. Its rotation is the camera's.
 *
 * <p>A camera-facing bone ({@link Facing}) is instead evaluated for a {@link View}: its world
 * rotation is replaced by the camera's (fully, or turned only about the model's Z or Y axis), its
 * own scale applies, and its pivot follows its parent chain and its own translation. Its children
 * compose under it, so they turn with it, and vertices blend it with other bones by weight. An
 * animation with such a bone is {@link #viewDependent()} and must be posed with a view. No IK,
 * blending between sequences, attachments, particles or material tracks are evaluated.
 */
public final class PreviewAnimation {
    /** A software evaluation budget; see {@code M2IdleAnimation.MAX_BONES}. */
    public static final int MAX_BONES = 1024;

    public static final int MAX_KEYS = 262_144;
    public static final long MAX_DURATION_MILLIS = 600_000;

    /**
     * Global clocks loop independently of the played sequence and are sampled modulo their own
     * length, so they are bounded only by the {@code int} key-time range. Character faces, for
     * example, loop over clocks longer than {@link #MAX_DURATION_MILLIS}.
     */
    public static final long MAX_GLOBAL_MILLIS = Integer.MAX_VALUE;

    private static final int FRAMING_SAMPLES = 96;

    public enum Interpolation {
        STEP,
        LINEAR
    }

    /** Ordered keys. Translation and scale use three components; rotation xyzw quaternions. */
    public static final class Track {
        private final Interpolation interpolation;
        private final int globalSequence;
        private final int components;
        private final int[] times;
        private final float[] values;

        public Track(
                Interpolation interpolation,
                int globalSequence,
                int components,
                int[] times,
                float[] values) {
            if (interpolation == null
                    || components != 3 && components != 4
                    || globalSequence < -1
                    || times.length == 0
                    || times.length > MAX_KEYS
                    || values.length != times.length * components) {
                throw new IllegalArgumentException("Invalid animation track");
            }
            this.interpolation = interpolation;
            this.globalSequence = globalSequence;
            this.components = components;
            this.times = times.clone();
            this.values = values.clone();
            for (int i = 0; i < this.times.length; i++) {
                if (this.times[i] < 0 || i > 0 && this.times[i] < this.times[i - 1]) {
                    throw new IllegalArgumentException("Animation keys must be ordered");
                }
            }
            for (int key = 0; key < this.times.length; key++) {
                double length = 0;
                for (int c = 0; c < components; c++) {
                    float value = this.values[key * components + c];
                    if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
                        throw new IllegalArgumentException("Invalid animation key value");
                    }
                    length += (double) value * value;
                }
                if (components == 4) {
                    if (length < 1e-12) {
                        throw new IllegalArgumentException("Degenerate rotation key");
                    }
                    double scale = 1 / Math.sqrt(length);
                    for (int c = 0; c < 4; c++) {
                        this.values[key * 4 + c] = (float) (this.values[key * 4 + c] * scale);
                    }
                }
            }
        }

        public Interpolation interpolation() {
            return interpolation;
        }

        public int globalSequence() {
            return globalSequence;
        }

        public int keyCount() {
            return times.length;
        }

        void sample(long time, double[] out) {
            int last = times.length - 1;
            int at;
            double f = 0;
            if (time <= times[0]) {
                at = 0;
            } else if (time >= times[last]) {
                at = last;
            } else {
                int low = 0, high = last;
                while (high - low > 1) {
                    int mid = (low + high) >>> 1;
                    if (times[mid] <= time) {
                        low = mid;
                    } else {
                        high = mid;
                    }
                }
                at = low;
                if (interpolation == Interpolation.LINEAR) {
                    f = (double) (time - times[low]) / (times[high] - times[low]);
                }
            }
            if (f == 0) {
                for (int c = 0; c < components; c++) {
                    out[c] = values[at * components + c];
                }
                return;
            }
            int a = at * components, b = (at + 1) * components;
            if (components == 3) {
                for (int c = 0; c < 3; c++) {
                    out[c] = values[a + c] + (values[b + c] - (double) values[a + c]) * f;
                }
                return;
            }
            double dot = 0;
            for (int c = 0; c < 4; c++) {
                dot += (double) values[a + c] * values[b + c];
            }
            double sign = dot < 0 ? -1 : 1;
            dot = Math.abs(dot);
            double wa, wb;
            if (dot > 0.9995) {
                wa = 1 - f;
                wb = f;
            } else {
                double theta = Math.acos(dot), sin = Math.sin(theta);
                wa = Math.sin((1 - f) * theta) / sin;
                wb = Math.sin(f * theta) / sin;
            }
            double length = 0;
            for (int c = 0; c < 4; c++) {
                out[c] = values[a + c] * wa + values[b + c] * sign * wb;
                length += out[c] * out[c];
            }
            length = Math.sqrt(length);
            for (int c = 0; c < 4; c++) {
                out[c] /= length;
            }
        }
    }

    /** How a camera-facing bone turns toward the camera. */
    public enum Facing {
        /** An ordinary bone. */
        NONE,
        /** Fully: its X axis points at the camera, Y to screen right, Z to screen up. */
        SPHERICAL,
        /** Only about the model's Z axis, which stays fixed (bone flag 0x40). */
        CYLINDRICAL_Z,
        /** Only about the model's Y axis, which stays fixed (bone flag 0x20). */
        CYLINDRICAL_Y
    }

    /**
     * A camera in native model space (Z up): the unit direction from the model toward the camera,
     * and the camera's unit up direction, perpendicular to it.
     */
    public record View(
            double towardX, double towardY, double towardZ, double upX, double upY, double upZ) {
        public View {
            double toward = towardX * towardX + towardY * towardY + towardZ * towardZ,
                    up = upX * upX + upY * upY + upZ * upZ,
                    dot = towardX * upX + towardY * upY + towardZ * upZ;
            if (!Double.isFinite(toward + up + dot)
                    || Math.abs(toward - 1) > 1e-6
                    || Math.abs(up - 1) > 1e-6
                    || Math.abs(dot) > 1e-6) {
                throw new IllegalArgumentException("Invalid camera view");
            }
        }

        /**
         * The orbit camera of the preview renderers ({@code StaticMeshRenderer}, {@code
         * PreviewCamera}) at {@code yaw} and {@code pitch}.
         */
        public static View orbit(double yaw, double pitch) {
            double c = Math.cos(yaw), s = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
            return new View(c * cp, -s * cp, sp, -c * sp, s * sp, cp);
        }
    }

    /**
     * One bone. A null track is an identity component. {@code billboard} marks a spherical
     * billboard bone: its translation moves its pivot and its scale sizes its card around that
     * pivot; it has no rotation track, since the camera sets its orientation.
     */
    public record Bone(
            int parent,
            float pivotX,
            float pivotY,
            float pivotZ,
            Track translation,
            Track rotation,
            Track scale,
            boolean billboard,
            Facing facing) {
        /** An ordinary or rigid-card bone, not camera-facing. */
        public Bone(
                int parent,
                float pivotX,
                float pivotY,
                float pivotZ,
                Track translation,
                Track rotation,
                Track scale,
                boolean billboard) {
            this(
                    parent,
                    pivotX,
                    pivotY,
                    pivotZ,
                    translation,
                    rotation,
                    scale,
                    billboard,
                    Facing.NONE);
        }

        public Bone {
            if (parent < -1
                    || !Float.isFinite(pivotX)
                    || !Float.isFinite(pivotY)
                    || !Float.isFinite(pivotZ)
                    || Math.max(Math.abs(pivotX), Math.max(Math.abs(pivotY), Math.abs(pivotZ)))
                            > 1_000_000
                    || translation != null && translation.components != 3
                    || rotation != null && rotation.components != 4
                    || scale != null && scale.components != 3
                    || billboard && rotation != null
                    || facing == null
                    || facing != Facing.NONE && (billboard || rotation != null)) {
                throw new IllegalArgumentException("Invalid animation bone");
            }
        }
    }

    public record Pose(PreviewMesh mesh, float[] normals, List<MeshAppearance.Vec3> pivots) {}

    private final String label;
    private final long durationMillis;
    private final long[] globalMillis;
    private final List<Bone> bones;

    /** Parent-first bones that move a vertex or parent one; only these are evaluated. */
    private final int[] order;

    /** Bones that neither move a vertex nor parent one: they keep their rest place. */
    private final int[] idle;

    private final float[] rest;
    private final float[] restNormals;
    private final int[] influenceBones;
    private final int[] influenceWeights;
    private final int[] rigidBillboard;

    /** Per rest vertex: whether a camera-facing bone, or a descendant of one, moves it. */
    private final boolean[] facingVertex;

    private final boolean viewDependent;
    private final float[] frameMin = new float[3], frameMax = new float[3];

    /**
     * @param influenceBones four bone indices per rest vertex
     * @param influenceWeights four weights per rest vertex, each 0..255 and summing to 255
     */
    public PreviewAnimation(
            String label,
            long durationMillis,
            long[] globalSequenceMillis,
            List<Bone> bones,
            PreviewMesh restMesh,
            float[] restNormals,
            int[] influenceBones,
            int[] influenceWeights) {
        this.label = Objects.requireNonNull(label);
        if (label.isBlank() || label.length() > 64) {
            throw new IllegalArgumentException("Invalid animation label");
        }
        if (durationMillis <= 0 || durationMillis > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Animation duration is outside playback bounds");
        }
        if (globalSequenceMillis.length > 1024 || bones.isEmpty() || bones.size() > MAX_BONES) {
            throw new IllegalArgumentException("Animation exceeds bone bounds");
        }
        this.durationMillis = durationMillis;
        globalMillis = globalSequenceMillis.clone();
        for (long value : globalMillis) {
            if (value < 0 || value > MAX_GLOBAL_MILLIS) {
                throw new IllegalArgumentException("Invalid global sequence duration");
            }
        }
        this.bones = List.copyOf(bones);
        long keys = 0;
        for (var bone : this.bones) {
            if (bone.parent() >= this.bones.size()) {
                throw new IllegalArgumentException("Bone parent outside skeleton");
            }
            for (var track : new Track[] {bone.translation(), bone.rotation(), bone.scale()}) {
                if (track == null) {
                    continue;
                }
                if (track.globalSequence >= globalMillis.length) {
                    throw new IllegalArgumentException("Track names an undeclared global clock");
                }
                keys += track.keyCount();
            }
        }
        if (keys > MAX_KEYS) {
            throw new IllegalArgumentException("Animation exceeds key bounds");
        }
        for (var bone : this.bones) {
            if (bone.parent() >= 0 && this.bones.get(bone.parent()).billboard()) {
                throw new IllegalArgumentException("Billboard bones must be leaves");
            }
        }
        int[] parentFirst = order(this.bones);
        Objects.requireNonNull(restMesh);
        int vertices = restMesh.vertexCount();
        if (restNormals.length != 0 && restNormals.length != vertices * 3
                || influenceBones.length != vertices * 4
                || influenceWeights.length != vertices * 4) {
            throw new IllegalArgumentException("Influences differ from the rest mesh");
        }
        rest = restMesh.positions.clone();
        this.restNormals = restNormals.clone();
        this.influenceBones = influenceBones.clone();
        this.influenceWeights = influenceWeights.clone();
        rigidBillboard = new int[vertices];
        for (int v = 0; v < vertices; v++) {
            int sum = 0, billboard = -1, billboardWeight = 0;
            for (int k = 0; k < 4; k++) {
                int bone = this.influenceBones[v * 4 + k],
                        weight = this.influenceWeights[v * 4 + k];
                if (weight < 0 || weight > 255 || bone < 0 || bone >= this.bones.size()) {
                    throw new IllegalArgumentException("Invalid vertex influence");
                }
                sum += weight;
                if (weight > 0 && this.bones.get(bone).billboard()) {
                    billboard = bone;
                    billboardWeight = weight;
                }
            }
            if (sum != 255) {
                throw new IllegalArgumentException("Vertex influences must sum to 255");
            }
            if (billboard >= 0 && billboardWeight != 255) {
                throw new IllegalArgumentException("Billboard members must be rigidly bound");
            }
            rigidBillboard[v] = billboard;
        }
        // Rigs carry attachment and effect bones that move no rendered vertex; skip them.
        boolean[] needed = new boolean[this.bones.size()];
        for (int i = 0; i < this.influenceBones.length; i++) {
            if (this.influenceWeights[i] > 0) {
                for (int at = this.influenceBones[i]; at >= 0 && !needed[at]; ) {
                    needed[at] = true;
                    at = this.bones.get(at).parent();
                }
            }
        }
        int[] all = parentFirst;
        order = Arrays.stream(all).filter(b -> needed[b]).toArray();
        idle = Arrays.stream(all).filter(b -> !needed[b]).toArray();
        boolean[] facingTree = new boolean[this.bones.size()];
        boolean dependent = false;
        for (int b : order) {
            var bone = this.bones.get(b);
            facingTree[b] =
                    bone.facing() != Facing.NONE || bone.parent() >= 0 && facingTree[bone.parent()];
            dependent |= bone.facing() != Facing.NONE;
        }
        viewDependent = dependent;
        facingVertex = new boolean[vertices];
        for (int v = 0; v < vertices; v++) {
            for (int k = 0; k < 4; k++) {
                if (this.influenceWeights[v * 4 + k] > 0
                        && facingTree[this.influenceBones[v * 4 + k]]) {
                    facingVertex[v] = true;
                }
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            frameMin[axis] = restMesh.minimum(axis);
            frameMax[axis] = restMesh.maximum(axis);
        }
        float[] scratch = new float[rest.length];
        double[] matrices = new double[this.bones.size() * 12];
        double[] scales = new double[this.bones.size() * 3];
        // A view-dependent pose is framed over views around the model as well.
        List<View> views = new ArrayList<>();
        views.add(null);
        if (viewDependent) {
            views.clear();
            for (int yaw = 0; yaw < 8; yaw++) {
                for (double pitch : new double[] {-1.1, 0, 1.1}) {
                    views.add(View.orbit(yaw * Math.PI / 4, pitch));
                }
            }
        }
        int samples = viewDependent ? FRAMING_SAMPLES / 8 : FRAMING_SAMPLES;
        for (var view : views) {
            for (int sample = 0; sample < samples; sample++) {
                long time = durationMillis * sample / samples;
                matrices(time, view, matrices, scales);
                skin(matrices, scales, scratch, null);
                for (int i = 0; i < scratch.length; i++) {
                    frameMin[i % 3] = Math.min(frameMin[i % 3], scratch[i]);
                    frameMax[i % 3] = Math.max(frameMax[i % 3], scratch[i]);
                }
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            if (!Float.isFinite(frameMin[axis])
                    || !Float.isFinite(frameMax[axis])
                    || Math.abs(frameMin[axis]) > 1_000_000
                    || Math.abs(frameMax[axis]) > 1_000_000) {
                throw new IllegalArgumentException("Evaluated pose leaves preview bounds");
            }
        }
    }

    private static int[] order(List<Bone> bones) {
        int[] state = new int[bones.size()], order = new int[bones.size()];
        int count = 0;
        for (int start = 0; start < bones.size(); start++) {
            if (state[start] == 2) {
                continue;
            }
            var chain = new ArrayList<Integer>();
            int at = start;
            while (at >= 0 && state[at] == 0) {
                state[at] = 1;
                chain.add(at);
                at = bones.get(at).parent();
            }
            if (at >= 0 && state[at] == 1) {
                throw new IllegalArgumentException("Bone hierarchy contains a cycle");
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                state[chain.get(i)] = 2;
                order[count++] = chain.get(i);
            }
        }
        return order;
    }

    public String label() {
        return label;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public int boneCount() {
        return bones.size();
    }

    public int vertexCount() {
        return rest.length / 3;
    }

    /** True only for the exact rest surface these influences were prepared for. */
    public boolean matches(PreviewMesh mesh) {
        return mesh != null && Arrays.equals(mesh.positions, rest);
    }

    public boolean matches(MeshAppearance appearance) {
        return appearance != null
                && matches(appearance.mesh())
                && Arrays.equals(appearance.normals, restNormals);
    }

    /**
     * Evaluates the looping sequence at playback time {@code millis}. A bone that neither moves a
     * vertex nor parents one is not evaluated; its pivot is reported at its rest place.
     */
    public Pose pose(PreviewMesh restMesh, long millis) {
        return pose(restMesh, millis, null);
    }

    /** Whether a camera-facing bone moves rendered vertices: posing then needs a {@link View}. */
    public boolean viewDependent() {
        return viewDependent;
    }

    /**
     * As {@link #pose(PreviewMesh, long)} for the camera {@code view}, which a {@link
     * #viewDependent()} animation requires and any other ignores.
     */
    public Pose pose(PreviewMesh restMesh, long millis, View view) {
        if (!matches(restMesh)) {
            throw new IllegalArgumentException("Animation belongs to another rest surface");
        }
        if (viewDependent && view == null) {
            throw new IllegalArgumentException("A camera-facing pose needs a camera view");
        }
        double[] matrices = new double[bones.size() * 12];
        double[] scales = new double[bones.size() * 3];
        matrices(Math.max(0, millis), view, matrices, scales);
        float[] positions = new float[rest.length];
        float[] normals = restNormals.length == 0 ? new float[0] : new float[restNormals.length];
        skin(matrices, scales, positions, normals.length == 0 ? null : normals);
        var pivots = new ArrayList<MeshAppearance.Vec3>(bones.size());
        for (int b = 0; b < bones.size(); b++) {
            var bone = bones.get(b);
            double[] p = transform(matrices, b, bone.pivotX(), bone.pivotY(), bone.pivotZ());
            pivots.add(new MeshAppearance.Vec3((float) p[0], (float) p[1], (float) p[2]));
        }
        return new Pose(restMesh.posed(positions, frameMin, frameMax), normals, pivots);
    }

    /**
     * Evaluated appearance with unchanged textures, UVs and materials. Rigid billboard cards keep
     * their members and move to the evaluated pivot of the bone that owns them.
     */
    public MeshAppearance pose(MeshAppearance restAppearance, long millis) {
        return pose(restAppearance, millis, null);
    }

    /**
     * As {@link #pose(MeshAppearance, long)} for the camera {@code view}. Vertices that a
     * camera-facing bone moves are posed already facing the camera, so a rest card over them is
     * dropped rather than turned a second time by the card renderer.
     */
    public MeshAppearance pose(MeshAppearance restAppearance, long millis, View view) {
        if (!matches(restAppearance)) {
            throw new IllegalArgumentException("Animation belongs to another rest appearance");
        }
        var pose = pose(restAppearance.mesh(), millis, view);
        var cards = new ArrayList<MeshAppearance.RigidBillboard>();
        for (var card : restAppearance.billboards()) {
            int[] members = card.vertexIndices();
            if (Arrays.stream(members).anyMatch(member -> facingVertex[member])) {
                continue;
            }
            int bone = rigidBillboard[members[0]];
            for (int member : members) {
                if (rigidBillboard[member] != bone) {
                    throw new IllegalArgumentException("Billboard card spans bones");
                }
            }
            cards.add(
                    bone < 0
                            ? card
                            : new MeshAppearance.RigidBillboard(
                                    card.boneId(), pose.pivots().get(bone), members, card.kind()));
        }
        return new MeshAppearance(
                pose.mesh(),
                restAppearance.uv,
                pose.normals(),
                restAppearance.surfaces(),
                restAppearance.textures(),
                cards);
    }

    /**
     * Bone matrices at {@code millis}; for a billboard bone, {@code scales} receives its own scale,
     * which sizes its card instead of entering its matrix.
     */
    private void matrices(long millis, View view, double[] out, double[] scales) {
        long sequence = millis % durationMillis;
        double[] t = new double[4], r = new double[4], s = new double[4], local = new double[12];
        double[] none = {0, 0, 0, 1}, unit = {1, 1, 1, 1};
        for (int b : idle) {
            Arrays.fill(out, b * 12, b * 12 + 12, 0);
            out[b * 12] = out[b * 12 + 5] = out[b * 12 + 10] = 1;
        }
        for (int b : order) {
            var bone = bones.get(b);
            if (bone.facing() != Facing.NONE) {
                sample(bone.translation(), sequence, millis, t, 0);
                sample(bone.scale(), sequence, millis, s, 2);
                facing(bone, view, b, t, s, out);
                continue;
            }
            if (bone.billboard()) {
                sample(bone.translation(), sequence, millis, t, 0);
                sample(bone.scale(), sequence, millis, s, 2);
                scales[b * 3] = s[0];
                scales[b * 3 + 1] = s[1];
                scales[b * 3 + 2] = s[2];
                compose(bone, t, none, unit, local);
            } else {
                sample(bone.translation(), sequence, millis, t, 0);
                sample(bone.rotation(), sequence, millis, r, 1);
                sample(bone.scale(), sequence, millis, s, 2);
                compose(bone, t, r, s, local);
            }
            if (bone.parent() < 0) {
                System.arraycopy(local, 0, out, b * 12, 12);
            } else {
                multiply(out, bone.parent() * 12, local, out, b * 12);
            }
        }
    }

    /**
     * A camera-facing bone's matrix: its pivot (moved by its own translation) through its parent's
     * matrix, then the camera basis scaled by its own scale about that pivot. The parent's rotation
     * and scale do not turn or size it; its children compose under it.
     */
    private void facing(Bone bone, View view, int b, double[] t, double[] s, double[] out) {
        double px = bone.pivotX() + t[0], py = bone.pivotY() + t[1], pz = bone.pivotZ() + t[2];
        double[] pivot =
                bone.parent() < 0
                        ? new double[] {px, py, pz}
                        : transform(out, bone.parent(), px, py, pz);
        double[] x, y, z;
        double[] toward = {view.towardX(), view.towardY(), view.towardZ()},
                up = {view.upX(), view.upY(), view.upZ()};
        switch (bone.facing()) {
            case SPHERICAL -> {
                x = toward;
                z = up;
                y = cross(z, x);
            }
            case CYLINDRICAL_Z -> {
                z = new double[] {0, 0, 1};
                x =
                        horizontal(
                                toward[0],
                                toward[1],
                                -up[0] * Math.signum(toward[2]),
                                -up[1] * Math.signum(toward[2]));
                x = new double[] {x[0], x[1], 0};
                y = cross(z, x);
            }
            case CYLINDRICAL_Y -> {
                y = new double[] {0, 1, 0};
                double[] h =
                        horizontal(
                                toward[0],
                                toward[2],
                                -up[0] * Math.signum(toward[1]),
                                -up[2] * Math.signum(toward[1]));
                x = new double[] {h[0], 0, h[1]};
                z = cross(x, y);
            }
            default -> throw new IllegalStateException("Not a camera-facing bone");
        }
        int o = b * 12;
        double[][] axes = {x, y, z};
        for (int row = 0; row < 3; row++) {
            double offset = pivot[row];
            for (int col = 0; col < 3; col++) {
                double value = axes[col][row] * s[col];
                out[o + row * 4 + col] = value;
                offset -=
                        value
                                * (col == 0
                                        ? bone.pivotX()
                                        : col == 1 ? bone.pivotY() : bone.pivotZ());
            }
            out[o + row * 4 + 3] = offset;
        }
    }

    /** The unit vector of (a, b), or of the fallback (c, d) when (a, b) is (nearly) zero. */
    private static double[] horizontal(double a, double b, double c, double d) {
        double length = Math.hypot(a, b);
        if (length < 1e-9) {
            a = c;
            b = d;
            length = Math.hypot(a, b);
        }
        if (length < 1e-9) {
            return new double[] {1, 0};
        }
        return new double[] {a / length, b / length};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]
        };
    }

    private void sample(Track track, long sequence, long millis, double[] out, int kind) {
        if (track == null) {
            out[0] = kind == 2 ? 1 : 0;
            out[1] = kind == 2 ? 1 : 0;
            out[2] = kind == 2 ? 1 : 0;
            out[3] = 1;
            return;
        }
        long time = sequence;
        if (track.globalSequence >= 0) {
            long period = globalMillis[track.globalSequence];
            time = period == 0 ? 0 : millis % period;
        }
        track.sample(time, out);
    }

    private static void compose(Bone bone, double[] t, double[] q, double[] s, double[] m) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[] r = {
            1 - 2 * (y * y + z * z),
            2 * (x * y - z * w),
            2 * (x * z + y * w),
            2 * (x * y + z * w),
            1 - 2 * (x * x + z * z),
            2 * (y * z - x * w),
            2 * (x * z - y * w),
            2 * (y * z + x * w),
            1 - 2 * (x * x + y * y)
        };
        double[] p = {bone.pivotX(), bone.pivotY(), bone.pivotZ()};
        for (int row = 0; row < 3; row++) {
            double offset = p[row] + t[row];
            for (int col = 0; col < 3; col++) {
                double value = r[row * 3 + col] * s[col];
                m[row * 4 + col] = value;
                offset -= value * p[col];
            }
            m[row * 4 + 3] = offset;
        }
    }

    private static void multiply(double[] a, int ao, double[] b, double[] out, int oo) {
        double[] result = new double[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                double value =
                        a[ao + row * 4] * b[col]
                                + a[ao + row * 4 + 1] * b[4 + col]
                                + a[ao + row * 4 + 2] * b[8 + col];
                if (col == 3) {
                    value += a[ao + row * 4 + 3];
                }
                result[row * 4 + col] = value;
            }
        }
        System.arraycopy(result, 0, out, oo, 12);
    }

    private static double[] transform(double[] m, int bone, double x, double y, double z) {
        int o = bone * 12;
        return new double[] {
            m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3],
            m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7],
            m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11]
        };
    }

    private void skin(double[] m, double[] scales, float[] positions, float[] normals) {
        for (int v = 0; v < rest.length / 3; v++) {
            double x = rest[v * 3], y = rest[v * 3 + 1], z = rest[v * 3 + 2];
            int billboard = rigidBillboard[v];
            if (billboard >= 0) {
                var bone = bones.get(billboard);
                double[] pivot =
                        transform(m, billboard, bone.pivotX(), bone.pivotY(), bone.pivotZ());
                positions[v * 3] = (float) (pivot[0] + scales[billboard * 3] * (x - bone.pivotX()));
                positions[v * 3 + 1] =
                        (float) (pivot[1] + scales[billboard * 3 + 1] * (y - bone.pivotY()));
                positions[v * 3 + 2] =
                        (float) (pivot[2] + scales[billboard * 3 + 2] * (z - bone.pivotZ()));
                if (normals != null) {
                    System.arraycopy(restNormals, v * 3, normals, v * 3, 3);
                }
                continue;
            }
            double px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
            double rx = normals == null ? 0 : restNormals[v * 3],
                    ry = normals == null ? 0 : restNormals[v * 3 + 1],
                    rz = normals == null ? 0 : restNormals[v * 3 + 2];
            for (int k = 0; k < 4; k++) {
                int weight = influenceWeights[v * 4 + k];
                if (weight == 0) {
                    continue;
                }
                double w = weight / 255.0;
                int o = influenceBones[v * 4 + k] * 12;
                px += w * (m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3]);
                py += w * (m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7]);
                pz += w * (m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11]);
                if (normals != null) {
                    nx += w * (m[o] * rx + m[o + 1] * ry + m[o + 2] * rz);
                    ny += w * (m[o + 4] * rx + m[o + 5] * ry + m[o + 6] * rz);
                    nz += w * (m[o + 8] * rx + m[o + 9] * ry + m[o + 10] * rz);
                }
            }
            positions[v * 3] = (float) px;
            positions[v * 3 + 1] = (float) py;
            positions[v * 3 + 2] = (float) pz;
            if (normals != null) {
                double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 1e-12) {
                    double restLength = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    nx *= restLength / length;
                    ny *= restLength / length;
                    nz *= restLength / length;
                } else {
                    nx = rx;
                    ny = ry;
                    nz = rz;
                }
                normals[v * 3] = (float) nx;
                normals[v * 3 + 1] = (float) ny;
                normals[v * 3 + 2] = (float) nz;
            }
        }
    }
}
