package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.PreviewMesh;

/**
 * One SKIN profile's native rest surface. The static preview, the textured sample and the Stand
 * idle all build it here, so an idle animation prepared for it matches the surface on screen.
 */
record RaceRestSurface(PreviewMesh mesh, float[] normals, float[] uv) {
    static RaceRestSurface of(M2Types.Model model, M2Types.RestSkin geometry, Cancellation token) {
        int count = geometry.vertexLookup().size();
        float[] points = new float[count * 3],
                normals = new float[count * 3],
                uv = new float[count * 2];
        for (int i = 0; i < count; i++) {
            if ((i & 2047) == 0) {
                token.check();
            }
            var vertex = model.vertices().get(geometry.vertexLookup().get(i));
            points[i * 3] = vertex.position().x();
            points[i * 3 + 1] = vertex.position().y();
            points[i * 3 + 2] = vertex.position().z();
            normals[i * 3] = vertex.normal().x();
            normals[i * 3 + 1] = vertex.normal().y();
            normals[i * 3 + 2] = vertex.normal().z();
            uv[i * 2] = vertex.uv0().x();
            uv[i * 2 + 1] = vertex.uv0().y();
        }
        var mesh =
                new PreviewMesh(
                        points,
                        geometry.triangleLookup().stream().mapToInt(Integer::intValue).toArray(),
                        geometry.sections().stream()
                                .map(
                                        part ->
                                                new PreviewMesh.Part(
                                                        part.id(),
                                                        (int) part.triangleStart(),
                                                        part.triangleCount()))
                                .toList());
        return new RaceRestSurface(mesh, normals, uv);
    }
}
