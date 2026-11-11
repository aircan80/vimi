package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2BindPoseFacts;
import com.vibeminer.formats.models.M2GeometrySkinReader;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2RestDrawSkinReader;
import com.vibeminer.formats.models.M2Types;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Native byte fixtures: replacement arrays are appended to an unchanged authored MD20 body. */
final class NativeMaterialFixture {
    final ByteBuffer body = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    final ByteBuffer skin = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    int bones, vertices, transforms, textures;

    NativeMaterialFixture(
            int version, int flags, int shader, int stages, int materialFlags, int blend)
            throws Exception {
        try (var in = getClass().getResourceAsStream("/golden/appearance-simple.m2")) {
            byte[] golden = in.readAllBytes();
            body.put(golden, 8, 592);
        }
        body.putInt(4, version);
        bones = body.position();
        declaration(44, 3, bones);
        for (int i = 0; i < 3; i++) {
            int start = body.position();
            body.position(start + 88);
            body.putInt(start, -1);
            body.putInt(start + 4, i == 1 ? 8 : 512);
            body.putShort(start + 8, (short) (i == 1 ? 0 : -1));
            for (int track : new int[] {16, 36, 56}) {
                body.putShort(start + track + 2, (short) -1);
            }
        }
        vertices = body.position();
        declaration(60, 4, vertices);
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (float[] c : corners) {
            body.putFloat(0).putFloat(c[0]).putFloat(c[1]);
            body.putInt(255).putInt(1);
            body.putFloat(1).putFloat(0).putFloat(0);
            body.putFloat((c[0] + 1) / 2).putFloat((c[1] + 1) / 2).putLong(0);
        }
        textures = body.position();
        declaration(80, 2, textures);
        for (int i = 0; i < 2; i++) {
            body.putInt(0).putInt(0).putInt(0).putInt(0);
        }
        int mat = body.position();
        declaration(112, 1, mat);
        body.putShort((short) materialFlags).putShort((short) blend);
        int palette = body.position();
        declaration(120, 3, palette);
        body.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        int combos = body.position();
        declaration(128, 2, combos);
        body.putShort((short) 0).putShort((short) 1);
        transforms = body.position();
        declaration(152, 2, transforms);
        body.putShort((short) -1).putShort((short) -1);
        int transform = body.position();
        declaration(96, 1, transform);
        body.position(transform + 60);
        for (int t : new int[] {0, 20, 40}) {
            body.putShort(transform + t + 2, (short) -1);
        }
        skin.putInt(0x4e494b53);
        skin.putInt(4).putInt(56).putInt(6).putInt(64).putInt(4).putInt(76);
        skin.putInt(1).putInt(92).putInt(1).putInt(140).putInt(3).putLong(0);
        for (int i = 0; i < 4; i++) {
            skin.putShort((short) i);
        }
        for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
            skin.putShort((short) i);
        }
        for (int i = 0; i < 4; i++) {
            skin.putInt(1);
        }
        skin.putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 4);
        skin.putShort((short) 0).putShort((short) 6).putShort((short) 3).putShort((short) 0);
        skin.putShort((short) 1).putShort((short) 0);
        skin.position(140);
        skin.put((byte) flags).put((byte) 0).putShort((short) shader);
        skin.putShort((short) 0).putShort((short) 0).putShort((short) -1).putShort((short) 0);
        skin.putShort((short) 0)
                .putShort((short) stages)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0)
                .putShort((short) 0);
    }

    void declaration(int header, int count, int offset) {
        body.putInt(header, count);
        body.putInt(header + 4, offset);
    }

    byte[] modelBytes() {
        int length = body.position();
        var out = ByteBuffer.allocate(length + 36).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x3132444d).putInt(length).put(body.array(), 0, length);
        out.putInt(0x44494653).putInt(4).putInt(9000);
        out.putInt(0x44495854).putInt(8).putInt(9001).putInt(9002);
        return out.array();
    }

    /**
     * Writes each vertex's bone indices as its skin palette slots, for tests that rebind vertices.
     * The palette is the identity (bone lookup 0, 1, 2 from slot 0), so the skin names the same
     * bones as the vertices, as client skins do.
     */
    void syncBoneProperties() {
        for (int vertex = 0; vertex < 4; vertex++) {
            skin.putInt(76 + vertex * 4, body.getInt(vertices + vertex * 48 + 16));
        }
    }

    Parsed parse() {
        byte[] bytes = modelBytes();
        var source = AssetSource.bytes(bytes);
        var model = M2ModelReader.read(source).join();
        var rest =
                M2RestDrawSkinReader.read(
                                AssetSource.bytes(Arrays.copyOf(skin.array(), skin.position())),
                                model,
                                null,
                                Cancellation.NONE)
                        .join();
        var geometry =
                M2GeometrySkinReader.read(
                                AssetSource.bytes(Arrays.copyOf(skin.array(), skin.position())),
                                model,
                                null,
                                Cancellation.NONE)
                        .join();
        return new Parsed(
                model,
                rest,
                geometry,
                M2Appearance.readModel(AssetSource.bytes(bytes), model, Cancellation.NONE).join(),
                M2BindPoseFacts.read(AssetSource.bytes(bytes), model, Cancellation.NONE).join());
    }

    record Parsed(
            M2Types.Model model,
            M2Types.RestDrawSkin rest,
            M2Types.GeometrySkin geometry,
            M2Appearance.ModelFacts appearance,
            M2BindPoseFacts.Facts bones) {
        DisplayMaterialProfiles.Profile profile() {
            return DisplayMaterialProfiles.admit(model, rest.batches().getFirst(), appearance);
        }

        java.util.List<com.vibeminer.rendering.models.MeshAppearance.RigidBillboard> cards() {
            return DisplayRigidCards.admit(
                    model, rest.surface(), geometry, 0, bones, Cancellation.NONE);
        }
    }
}
