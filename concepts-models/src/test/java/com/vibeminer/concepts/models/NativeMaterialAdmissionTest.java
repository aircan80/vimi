package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class NativeMaterialAdmissionTest {
    private NativeMaterialFixture environment() throws Exception {
        return new NativeMaterialFixture(274, 0x90, 14, 2, 0, 0);
    }

    private NativeMaterialFixture card() throws Exception {
        return new NativeMaterialFixture(274, 16, 16, 1, 17, 4);
    }

    @Test
    void environmentRequiresBothIdentityTransformsAndExactModernProfile() throws Exception {
        var good = environment().parse();
        assertEquals(DisplayMaterialProfiles.Kind.ENVIRONMENT, good.profile().kind());
        assertEquals(2, good.profile().stages());
        var transformed = environment();
        transformed.body.putShort(transformed.transforms + 2, (short) 0);
        assertNull(transformed.parse().profile());
        var legacy = environment();
        legacy.body.putInt(4, 272);
        assertNull(legacy.parse().profile());
        var flags = environment();
        flags.skin.put(140, (byte) 0x91);
        assertNull(flags.parse().profile());
        var texture = environment();
        texture.body.putInt(texture.textures + 16 + 4, 4);
        assertNull(texture.parse().profile());
    }

    @Test
    void opacityAndFactIdentityAreEvidenceRatherThanDefaults() throws Exception {
        var f = environment();
        int weightOffset = f.body.getInt(92); // native texture-weight track array
        int valuesOuter = f.body.getInt(weightOffset + 16);
        int raw = f.body.getInt(valuesOuter + 4);
        f.body.putShort(raw, (short) 16384);
        assertNull(f.parse().profile());
        var a = environment().parse();
        var b = environment().parse();
        assertNull(
                DisplayMaterialProfiles.admit(
                        a.model(), a.rest().batches().getFirst(), b.appearance()));
    }

    @Test
    void unlitLegacyProfileDoesNotLeakIntoModernModels() throws Exception {
        var legacy = new NativeMaterialFixture(272, 16, 0, 1, 1, 0);
        assertEquals(DisplayMaterialProfiles.Kind.UNLIT_OPAQUE, legacy.parse().profile().kind());
        legacy.body.putInt(4, 274);
        assertNull(legacy.parse().profile());
    }

    @Test
    void rigidLeafRequiresSingleFullWeightAndPaletteAgreement() throws Exception {
        var good = card().parse();
        assertEquals(1, good.cards().size());
        assertEquals(1, good.cards().getFirst().boneId());
        var mixed = card();
        mixed.body.putInt(mixed.vertices + 12, 0x7f80);
        mixed.body.putInt(mixed.vertices + 16, 0x0201);
        assertNull(mixed.parse().cards());
        var palette = card();
        palette.skin.putInt(76, 0);
        assertNull(palette.parse().cards());
    }

    @Test
    void ownTracksAndHierarchyAreCheckedWithoutRejectingAnimatedOrdinaryAncestors()
            throws Exception {
        var ancestor = card();
        ancestor.body.putInt(ancestor.bones + 16 + 4, 1);
        assertEquals(1, ancestor.parse().cards().size());
        var own = card();
        own.body.putInt(own.bones + 88 + 16 + 4, 1);
        assertNull(own.parse().cards());
        var nonleaf = card();
        nonleaf.body.putShort(nonleaf.bones + 176 + 8, (short) 1);
        assertNull(nonleaf.parse().cards());
        var cycle = card();
        cycle.body.putShort(cycle.bones + 8, (short) 2);
        cycle.body.putShort(cycle.bones + 176 + 8, (short) 0);
        assertNull(cycle.parse().cards());
    }

    @Test
    void triangleCannotStraddleRigidLeafGroups() throws Exception {
        var f = card();
        f.body.putInt(f.bones + 176 + 4, 8);
        f.body.putShort(f.bones + 176 + 8, (short) 0);
        f.body.putInt(f.vertices + 16, 2);
        f.skin.putInt(76, 2);
        assertNull(f.parse().cards());
    }

    @Test
    void aRigidCardCannotAlsoBelongToAnotherNativeSection() throws Exception {
        var f = card();
        byte[] section = java.util.Arrays.copyOfRange(f.skin.array(), 92, 140);
        byte[] batch = java.util.Arrays.copyOfRange(f.skin.array(), 140, 164);
        f.skin.position(140);
        f.skin.put(section).put(batch);
        f.skin.putInt(28, 2).putInt(40, 188);
        assertNull(f.parse().cards());
    }
}
