// Terrain splatting (issue #174 step 2b-2): near ground blends the tile's own layer diffuses by its
// alpha maps, as the atlas composer does, and fades to the baked atlas texel where the atlas is no
// longer magnified. No discard, so terrain keeps early depth testing.
//
// Stage 1: the shared layer array (256 x 256 BC1, or 128 x 128 RGBA8), repeat, anisotropic.
// Stage 2: the tile's mask map (512 or 1024 square, RGBA8): R, G, B weigh layers 1 to 3, A is the
//          MCCV brightness (128 = 1.0). Clamp, trilinear.
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
    vec2 texels = fwidth(atlasUV * 512.0);
    return smoothstep(0.5, 1.0, max(texels.x, texels.y));
}

// rgb: the splatted albedo, 0..255, MCCV applied; a: 1 where the chunk has its layers, 0 where it
// keeps the atlas (its table says 255 for layer 0). Every sample is taken unconditionally, so call
// it only under uniform branches.
vec4 splatAlbedo(vec2 atlasUV)
{
    vec2 cell = clamp(floor(atlasUV * 16.0), 0.0, 15.0);
    vec4 ids = floor(texture2DLod(s_worldSplatTable, (cell + 0.5) / 16.0, 0.0) * 255.0 + 0.5);
    // Mask texels of this chunk only: a neighbour chunk's channels weigh other layers.
    float per = u_worldSplat.y / 16.0;
    vec2 local = clamp((atlasUV * 16.0 - cell) * per, 0.5, per - 0.5);
    vec4 mask = texture2D(s_worldSplatMask, (cell * per + local) / u_worldSplat.y);
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
    albedo = min(albedo * mask.a * (255.0 * 255.0 / 128.0), vec3_splat(255.0));
    return vec4(albedo, ids.x < 254.5 ? 1.0 : 0.0);
}
