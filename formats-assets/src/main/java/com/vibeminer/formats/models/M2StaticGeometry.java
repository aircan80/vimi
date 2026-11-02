package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2Types.Bounds;
import com.vibeminer.formats.models.M2Types.GeometrySkin;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Section;
import com.vibeminer.formats.models.M2Types.Skin;
import com.vibeminer.formats.models.M2Types.Vec3;
import com.vibeminer.formats.models.M2Types.Vertex;
import java.util.ArrayList;
import java.util.List;

/** Native bind/rest triangles only; no pose, visibility, material or appearance authority. */
public record M2StaticGeometry(
        List<Vertex> vertices, List<Integer> indices, List<Section> sections, Bounds bounds) {
    public static final String COORDINATE_POLICY = "native-m2-position-normal-uv-winding-v1";

    public M2StaticGeometry {
        vertices = List.copyOf(vertices);
        indices = List.copyOf(indices);
        sections = List.copyOf(sections);
    }

    public static M2StaticGeometry create(Model model, Skin skin, Cancellation token) {
        return core(
                model,
                skin.model(),
                skin.vertexLookup(),
                skin.triangleLookup(),
                skin.sections(),
                token);
    }

    public static M2StaticGeometry create(Model model, GeometrySkin skin, Cancellation token) {
        return core(
                model,
                skin.model(),
                skin.vertexLookup(),
                skin.triangleLookup(),
                skin.sections(),
                token);
    }

    private static M2StaticGeometry core(
            Model model,
            Model bound,
            List<Integer> lookup,
            List<Integer> triangles,
            List<Section> sections,
            Cancellation token) {
        token.check();
        if (model != bound) {
            throw M2Input.error("INVALID", "The SKIN was validated against another model.");
        }
        if (triangles.isEmpty() || sections.stream().noneMatch(s -> s.triangleCount() != 0)) {
            throw M2Input.error(
                    "UNSUPPORTED", "The selected profile has no static surface triangles.");
        }
        List<Integer> indices = new ArrayList<>(triangles.size());
        for (int i = 0; i < triangles.size(); i++) {
            if ((i & 4095) == 0) {
                token.check();
            }
            indices.add(lookup.get(triangles.get(i)));
        }
        float minX = Float.POSITIVE_INFINITY,
                minY = minX,
                minZ = minX,
                maxX = Float.NEGATIVE_INFINITY,
                maxY = maxX,
                maxZ = maxX;
        for (Section section : sections) {
            for (long n = section.triangleStart();
                    n < section.triangleStart() + section.triangleCount();
                    n++) {
                if ((n & 4095) == 0) {
                    token.check();
                }
                Vec3 p = model.vertices().get(indices.get((int) n)).position();
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                minZ = Math.min(minZ, p.z());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
                maxZ = Math.max(maxZ, p.z());
            }
        }
        double dx = (double) maxX - minX,
                dy = (double) maxY - minY,
                dz = (double) maxZ - minZ,
                r = Math.sqrt(dx * dx + dy * dy + dz * dz) / 2;
        if (r > Float.MAX_VALUE) {
            throw M2Input.error("LIMIT", "Section bounds exceed finite geometry representation.");
        }
        return new M2StaticGeometry(
                model.vertices(),
                indices,
                sections,
                new Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ), (float) r));
    }
}
