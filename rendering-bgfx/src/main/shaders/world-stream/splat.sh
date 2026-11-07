// Terrain splatting (issue #174 step 2b-2): near ground blends the tile's own layer diffuses by its
// alpha maps, as the atlas composer does, and fades to the baked atlas texel where the atlas is no
// longer magnified. No discard, so terrain keeps early depth testing.
//
// Stage 1: the shared layer array (256 x 256 BC1, or 128 x 128 RGBA8), repeat, anisotropic.
// Stage 2: the tile's mask map (512 or 1024 square, RGBA8): R, G, B weigh layers 1 to 3, A is the
//          MCCV brightness (128 = 1.0), unused since #373. Clamp, trilinear at splatMaskLod.
// Stage 3: the tile's 16 x 16 chunk table of the four layers' array indices, 255 for none. Point.
SAMPLER2DARRAY(s_worldSplatLayers, 1);
SAMPLER2D(s_worldSplatMask, 2);
SAMPLER2D(s_worldSplatTable, 3);
// x: 1 when this tile's splat inputs are drawn, else 0; y: mask side in texels; zw unused.
uniform vec4 u_worldSplat;

// How much of the atlas texel to keep, from its texels per pixel: 0 where the 512 atlas is
// magnified at least two times, 1 from one texel per pixel on. Call it at the top of main(),
// before any branch: derivatives are only defined in uniform control flow.
float splatFade(vec2 atlasUV)
{
    // The longer screen-axis step of the atlas position: the same footprint at every yaw, where
    // fwidth (|dx| + |dy| per axis) overestimates by up to sqrt(2) at 45 degrees.
    vec2 p = atlasUV * 512.0;
    float texels = max(length(dFdx(p)), length(dFdy(p)));
    return smoothstep(0.5, 1.0, texels);
}

// The mask level for this pixel, from the mask texels per pixel, at most the level where one texel
// covers a chunk. Call it at the top of main() like splatFade: it takes derivatives.
float splatMaskLod(vec2 atlasUV)
{
    vec2 texel = atlasUV * u_worldSplat.y;
    float footprint = max(max(length(dFdx(texel)), length(dFdy(texel))), 1e-6);
    return clamp(log2(footprint), 0.0, log2(max(u_worldSplat.y / 16.0, 1.0)));
}

// rgb: the splatted albedo, 0..255, before MCCV; a: 1 where the chunk has its layers, 0 where it
// keeps the atlas (its table says 255 for layer 0). Every sample is taken unconditionally, so call
// it only under uniform branches.
vec4 splatAlbedo(vec2 atlasUV, float lod)
{
    vec2 cell = clamp(floor(atlasUV * 16.0), 0.0, 15.0);
    vec4 ids = floor(texture2DLod(s_worldSplatTable, (cell + 0.5) / 16.0, 0.0) * 255.0 + 0.5);
    float keep = ids.x < 254.5 ? 1.0 : 0.0;
    // 255 (no layer) is past the array's last layer: sample the base layer instead. Its mask
    // weight is 0, so the blend is unchanged, and no backend reads out of range.
    ids.x = ids.x < 254.5 ? ids.x : 0.0;
    ids.yzw = mix(ids.yzw, ids.xxx, step(vec3_splat(254.5), ids.yzw));
    // Mask texels of this chunk only: a neighbour chunk's channels weigh other layers. Trilinear
    // reads the two levels around lod, so the clamp keeps half a texel of the coarser one inside
    // the chunk; a mip-0-only clamp let a coarser level (HIGH masks in the fade band) bleed.
    float per = u_worldSplat.y / 16.0;
    float edge = min(0.5 * exp2(ceil(lod)), 0.5 * per);
    vec2 local = clamp((atlasUV * 16.0 - cell) * per, edge, per - edge);
    vec4 mask = texture2DLod(s_worldSplatMask, (cell * per + local) / u_worldSplat.y, lod);
    // Eight repeats per chunk, continuous across chunk borders so mips and anisotropy stay smooth.
    vec2 uv = atlasUV * 128.0;
    vec3 l0 = texture2DArray(s_worldSplatLayers, vec3(uv, ids.x)).rgb;
    vec3 l1 = texture2DArray(s_worldSplatLayers, vec3(uv, ids.y)).rgb;
    vec3 l2 = texture2DArray(s_worldSplatLayers, vec3(uv, ids.z)).rgb;
    vec3 l3 = texture2DArray(s_worldSplatLayers, vec3(uv, ids.w)).rgb;
    // The composer's weights: the base layer takes what the overlays leave, and overlays summing
    // past one are normalized, never saturated (#397).
    float sum = mask.r + mask.g + mask.b;
    float w0 = max(0.0, 1.0 - sum);
    vec3 albedo = (l0 * w0 + l1 * mask.r + l2 * mask.g + l3 * mask.b) / max(1.0, w0 + sum);
    // MCCV comes from the vertex since #373; mask A is no longer read.
    albedo = albedo * 255.0;
    return vec4(albedo, keep);
}
