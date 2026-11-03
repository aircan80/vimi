package com.vibeminer.rendering.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Versioned bounded static positions/topology derivative. */
public final class PreviewMeshCodec {
    private static final int MAGIC = 0x56525031;

    private PreviewMeshCodec() {}

    public static byte[] encode(PreviewMesh mesh) {
        float[] points = mesh.positions();
        int[] faces = mesh.indices();
        ByteBuffer output =
                ByteBuffer.allocate(
                                16
                                        + 4 * points.length
                                        + 4 * faces.length
                                        + 12 * mesh.parts().size())
                        .order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC).putInt(points.length).putInt(faces.length).putInt(mesh.parts().size());
        for (float point : points) {
            output.putFloat(point);
        }
        for (int index : faces) {
            output.putInt(index);
        }
        for (var part : mesh.parts()) {
            output.putInt(part.id()).putInt(part.firstIndex()).putInt(part.indexCount());
        }
        return output.array();
    }

    public static PreviewMesh decode(byte[] bytes) {
        if (bytes.length < 16) {
            throw new IllegalArgumentException("Truncated preview geometry");
        }
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (input.getInt() != MAGIC) {
            throw new IllegalArgumentException("Unknown preview geometry format");
        }
        int positions = input.getInt(), indices = input.getInt(), parts = input.getInt();
        if (positions < 3
                || positions % 3 != 0
                || positions > PreviewMesh.MAX_VERTICES * 3
                || indices < 3
                || indices % 3 != 0
                || indices > PreviewMesh.MAX_INDICES
                || parts < 1
                || parts > PreviewMesh.MAX_PARTS
                || bytes.length != 16L + 4L * positions + 4L * indices + 12L * parts) {
            throw new IllegalArgumentException("Invalid preview geometry bounds");
        }
        float[] pointData = new float[positions];
        int[] faceData = new int[indices];
        var sections = new ArrayList<PreviewMesh.Part>();
        for (int i = 0; i < positions; i++) {
            pointData[i] = input.getFloat();
        }
        for (int i = 0; i < indices; i++) {
            faceData[i] = input.getInt();
        }
        for (int i = 0; i < parts; i++) {
            sections.add(new PreviewMesh.Part(input.getInt(), input.getInt(), input.getInt()));
        }
        return new PreviewMesh(pointData, faceData, sections);
    }
}
