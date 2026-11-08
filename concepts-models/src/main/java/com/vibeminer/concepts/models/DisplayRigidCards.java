package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2BindPoseFacts;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source proof for rigid leaf cards in an explicitly unanimated bind/rest preview. */
final class DisplayRigidCards {
    private DisplayRigidCards() {}

    static List<MeshAppearance.RigidBillboard> admit(
            M2Types.Model model,
            M2Types.RestSkin rest,
            M2Types.GeometrySkin geometry,
            int ordinal,
            M2BindPoseFacts.Facts facts,
            Cancellation token) {
        if (facts == null
                || facts.model() != model
                || geometry == null
                || geometry.model() != model
                || rest.model() != model
                || model.bones().size() > 4096
                || model.skeletonFileDataId() != 0
                || model.boneFileDataIds().stream().anyMatch(id -> id != 0)
                || !geometry.vertexLookup().equals(rest.vertexLookup())
                || !geometry.triangleLookup().equals(rest.triangleLookup())
                || !geometry.sections().equals(rest.sections())
                || geometry.boneProperties().size() != rest.vertexLookup().size()
                || facts.absentOwnTracks().size() != model.bones().size()) {
            return null;
        }
        var section = rest.sections().get(ordinal);
        if (section.boneInfluences() != 1
                || section.triangleCount() < 6
                || section.triangleCount() > 128 * 6
                || section.triangleCount() % 6 != 0
                || section.vertexCount() > 1024) {
            return null;
        }
        int[] children = new int[model.bones().size()];
        for (var bone : model.bones()) {
            if (bone.parent() >= 0) {
                children[bone.parent()]++;
            }
        }
        int[] member = new int[rest.vertexLookup().size()];
        Arrays.fill(member, -1);
        Map<Integer, TreeSet<Integer>> groups = new TreeMap<>();
        Map<Integer, Integer> triangles = new TreeMap<>();
        int first = Math.toIntExact(section.triangleStart());
        for (int at = first; at < first + section.triangleCount(); at += 3) {
            token.check();
            int[] corners = {
                rest.triangleLookup().get(at),
                rest.triangleLookup().get(at + 1),
                rest.triangleLookup().get(at + 2)
            };
            if (corners[0] == corners[1] || corners[0] == corners[2] || corners[1] == corners[2]) {
                return null;
            }
            int triangleBone = -1;
            for (int vertex : corners) {
                if (vertex < section.vertexStart()
                        || vertex >= section.vertexStart() + section.vertexCount()) {
                    return null;
                }
                int bone = member[vertex];
                if (bone < 0) {
                    bone = resolve(model, rest, geometry, section, vertex);
                    if (bone < 0 || !leaf(model, bone, children, facts, token)) {
                        return null;
                    }
                    member[vertex] = bone;
                    groups.computeIfAbsent(bone, unused -> new TreeSet<>()).add(vertex);
                    if (groups.size() > 128 || groups.get(bone).size() > 4) {
                        return null;
                    }
                }
                if (triangleBone >= 0 && triangleBone != bone) {
                    return null;
                }
                triangleBone = bone;
            }
            if (triangles.merge(triangleBone, 1, Integer::sum) > 2) {
                return null;
            }
        }
        long scanned = 0;
        for (int index = 0; index < rest.sections().size(); index++) {
            if (index == ordinal) {
                continue;
            }
            var other = rest.sections().get(index);
            scanned += other.triangleCount();
            if (scanned > PreviewMesh.MAX_INDICES) {
                return null;
            }
            int start = Math.toIntExact(other.triangleStart());
            for (int at = start; at < start + other.triangleCount(); at++) {
                if ((at & 4095) == 0) {
                    token.check();
                }
                if (member[rest.triangleLookup().get(at)] >= 0) {
                    return null;
                }
            }
        }
        var cards = new ArrayList<MeshAppearance.RigidBillboard>();
        for (var group : groups.entrySet()) {
            if (group.getValue().size() != 4 || triangles.get(group.getKey()) != 2) {
                return null;
            }
            var pivot = model.bones().get(group.getKey()).pivot();
            if (!bounded(pivot.x()) || !bounded(pivot.y()) || !bounded(pivot.z())) {
                return null;
            }
            cards.add(
                    new MeshAppearance.RigidBillboard(
                            group.getKey(),
                            new MeshAppearance.Vec3(pivot.x(), pivot.y(), pivot.z()),
                            group.getValue().stream().mapToInt(Integer::intValue).toArray(),
                            kind(model.bones().get(group.getKey()).flags())));
        }
        return List.copyOf(cards);
    }

    private static int resolve(
            M2Types.Model model,
            M2Types.RestSkin rest,
            M2Types.GeometrySkin geometry,
            M2Types.Section section,
            int vertex) {
        var source = model.vertices().get(rest.vertexLookup().get(vertex));
        int slot = -1;
        for (int index = 0; index < 4; index++) {
            int weight = (int) (source.boneWeights() >>> (index * 8) & 255);
            if (weight != 0) {
                if (weight != 255 || slot != -1) {
                    return -1;
                }
                slot = index;
            }
        }
        if (slot == -1) {
            return -1;
        }
        int bone = (int) (source.boneIndices() >>> (slot * 8) & 255);
        int local = (int) (geometry.boneProperties().get(vertex) >>> (slot * 8) & 255);
        long palette = (long) section.boneStart() + local;
        if (bone >= model.bones().size()
                || local >= section.boneCount()
                || palette >= model.boneLookup().size()
                || model.boneLookup().get((int) palette) != bone) {
            return -1;
        }
        return bone;
    }

    private static boolean leaf(
            M2Types.Model model,
            int bone,
            int[] children,
            M2BindPoseFacts.Facts facts,
            Cancellation token) {
        if (children[bone] != 0
                || kind(model.bones().get(bone).flags()) == null
                || !facts.absentOwnTracks().get(bone)) {
            return false;
        }
        boolean[] seen = new boolean[model.bones().size()];
        seen[bone] = true;
        int parent = model.bones().get(bone).parent();
        while (parent >= 0) {
            token.check();
            if (parent >= seen.length || seen[parent]) {
                return false;
            }
            seen[parent] = true;
            long flags = model.bones().get(parent).flags();
            if (flags != 0 && flags != 0x200) {
                return false;
            }
            parent = model.bones().get(parent).parent();
        }
        return true;
    }

    /**
     * The card kind of a billboard leaf whose flags are exactly one billboard flag: 0x8 spherical,
     * 0x40 cylindrical about Z, 0x20 cylindrical about Y; otherwise null.
     */
    static MeshAppearance.CardKind kind(long flags) {
        return flags == 0x8
                ? MeshAppearance.CardKind.SPHERICAL
                : flags == 0x40
                        ? MeshAppearance.CardKind.CYLINDRICAL_Z
                        : flags == 0x20 ? MeshAppearance.CardKind.CYLINDRICAL_Y : null;
    }

    private static boolean bounded(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 1_000_000;
    }
}
