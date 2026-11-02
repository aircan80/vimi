package com.vibeminer.formats.models;

import java.util.List;

public final class M2Combiners {
    private M2Combiners() {}

    public enum Combiner {
        OPAQUE,
        MOD,
        OPAQUE_MOD2X_NA_ALPHA,
        OPAQUE_ADD_ALPHA_ALPHA,
        OPAQUE_ALPHA;

        public int code() {
            return ordinal() + 1;
        }
    }

    public enum Coordinate {
        UV0,
        UV1,
        ENV;

        public int code() {
            return ordinal();
        }
    }

    public record ShaderNames(String pixelShader, String vertexShader) {}

    public record Profile(
            Combiner combiner,
            String pixelShader,
            String vertexShader,
            List<Coordinate> stages,
            boolean textureAlpha) {
        public Profile {
            stages = List.copyOf(stages);
        }
    }

    public record Admission(Profile profile, String label) {}

    private static final ShaderNames[] EFFECTS = {
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_AddAlpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_AddAlpha_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha_Add", "Diffuse_T1_Env_T1"),
        new ShaderNames("Combiners_Mod_AddAlpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_AddAlpha", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Mod_AddAlpha", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Mod_AddAlpha_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_Alpha_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha_3s", "Diffuse_T1_Env_T1"),
        new ShaderNames("Combiners_Opaque_AddAlpha_Wgt", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Mod_Add_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_ModNA_Alpha", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Mod_AddAlpha_Wgt", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Mod_AddAlpha_Wgt", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Opaque_AddAlpha_Wgt", "Diffuse_T1_T2"),
        new ShaderNames("Combiners_Opaque_Mod_Add_Wgt", "Diffuse_T1_Env"),
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha_UnshAlpha", "Diffuse_T1_Env_T1"),
        new ShaderNames("Combiners_Mod_Dual_Crossfade", "Diffuse_T1"),
        new ShaderNames("Combiners_Mod_Depth", "Diffuse_EdgeFade_T1"),
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha_Alpha", "Diffuse_T1_Env_T2"),
        new ShaderNames("Combiners_Mod_Mod", "Diffuse_EdgeFade_T1_T2"),
        new ShaderNames("Combiners_Mod_Masked_Dual_Crossfade", "Diffuse_T1_T2"),
        new ShaderNames("Combiners_Opaque_Alpha", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Opaque_Mod2xNA_Alpha_UnshAlpha", "Diffuse_T1_Env_T2"),
        new ShaderNames("Combiners_Mod_Depth", "Diffuse_EdgeFade_Env"),
        new ShaderNames("Guild", "Diffuse_T1_T2_T1"),
        new ShaderNames("Guild_NoBorder", "Diffuse_T1_T2"),
        new ShaderNames("Guild_Opaque", "Diffuse_T1_T2_T1"),
        new ShaderNames("Illum", "Diffuse_T1_T1"),
        new ShaderNames("Combiners_Mod_Mod_Mod_Const", "Diffuse_T1_T2_T3"),
        new ShaderNames("Combiners_Mod_Mod_Mod_Const", "Color_T1_T2_T3"),
        new ShaderNames("Combiners_Opaque", "Diffuse_T1"),
        new ShaderNames("Combiners_Mod_Mod2x", "Diffuse_EdgeFade_T1_T2"),
        new ShaderNames("Combiners_Mod", "Diffuse_EdgeFade_T1"),
        new ShaderNames("Combiners_Mod_Mod_Depth", "Diffuse_EdgeFade_T1_T2")
    };
    private static final List<Profile> SUPPORTED =
            List.of(
                    new Profile(
                            Combiner.OPAQUE,
                            "Combiners_Opaque",
                            "Diffuse_T1",
                            List.of(Coordinate.UV0),
                            false),
                    new Profile(
                            Combiner.MOD,
                            "Combiners_Mod",
                            "Diffuse_T1",
                            List.of(Coordinate.UV0),
                            true),
                    new Profile(
                            Combiner.OPAQUE_MOD2X_NA_ALPHA,
                            "Combiners_Opaque_Mod2xNA_Alpha",
                            "Diffuse_T1_Env",
                            List.of(Coordinate.UV0, Coordinate.ENV),
                            false),
                    new Profile(
                            Combiner.OPAQUE_ADD_ALPHA_ALPHA,
                            "Combiners_Opaque_AddAlpha_Alpha",
                            "Diffuse_T1_Env",
                            List.of(Coordinate.UV0, Coordinate.ENV),
                            false),
                    new Profile(
                            Combiner.OPAQUE_ALPHA,
                            "Combiners_Opaque_Alpha",
                            "Diffuse_T1_T1",
                            List.of(Coordinate.UV0, Coordinate.UV0),
                            false));

    public static Profile profile(Combiner code) {
        return SUPPORTED.stream().filter(p -> p.combiner() == code).findFirst().orElse(null);
    }

    public static ShaderNames name(int count, int shader) {
        if (shader < 0 || shader > 65535) {
            throw new IllegalArgumentException("uint16 shader required");
        }
        if ((shader & 0x8000) != 0) {
            int i = shader & 0x7fff;
            return i < EFFECTS.length ? EFFECTS[i] : null;
        }
        if (count == 1) {
            return new ShaderNames(
                    (shader & 0x70) != 0 ? "Combiners_Mod" : "Combiners_Opaque",
                    (shader & 0x80) != 0
                            ? "Diffuse_Env"
                            : (shader & 0x4000) != 0 ? "Diffuse_T2" : "Diffuse_T1");
        }
        int low = shader & 7;
        String ps =
                (shader & 0x70) != 0
                        ? switch (low) {
                            case 3 -> "Combiners_Mod_Add";
                            case 4 -> "Combiners_Mod_Mod2x";
                            case 6 -> "Combiners_Mod_Mod2xNA";
                            case 7 -> "Combiners_Mod_AddNA";
                            default -> "Combiners_Mod_Mod";
                        }
                        : switch (low) {
                            case 0 -> "Combiners_Opaque_Opaque";
                            case 3, 7 -> "Combiners_Opaque_AddAlpha";
                            case 4 -> "Combiners_Opaque_Mod2x";
                            case 6 -> "Combiners_Opaque_Mod2xNA";
                            default -> "Combiners_Opaque_Mod";
                        };
        String vs =
                (shader & 0x80) != 0
                        ? (shader & 8) != 0 ? "Diffuse_Env_Env" : "Diffuse_Env_T1"
                        : (shader & 8) != 0
                                ? "Diffuse_T1_Env"
                                : (shader & 0x4000) != 0 ? "Diffuse_T1_T2" : "Diffuse_T1_T1";
        return new ShaderNames(ps, vs);
    }

    public static Admission admit(int count, int shader) {
        ShaderNames n = name(count, shader);
        String id = String.format(java.util.Locale.ROOT, "0x%04X", shader);
        if (n == null) {
            return new Admission(null, "shader " + id + " (no named combiner)");
        }
        int known = (shader & 0x8000) != 0 ? 0xffff : count == 1 ? 0x40f0 : 0x40ff;
        if ((shader & ~known) != 0) {
            return new Admission(null, "shader " + id + " (bits outside the named selection)");
        }
        String label =
                n.pixelShader()
                        + " with "
                        + n.vertexShader()
                        + " (shader "
                        + id
                        + ", "
                        + count
                        + (count == 1 ? " texture)" : " textures)");
        Profile p =
                SUPPORTED.stream()
                        .filter(
                                v ->
                                        v.stages().size() == count
                                                && v.pixelShader().equals(n.pixelShader())
                                                && v.vertexShader().equals(n.vertexShader()))
                        .findFirst()
                        .orElse(null);
        return new Admission(p, label);
    }
}
