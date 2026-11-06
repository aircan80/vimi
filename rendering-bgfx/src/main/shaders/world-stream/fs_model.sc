$input v_color, v_uvFog, v_fogColor, v_depth
#include <bgfx_shader.sh>
SAMPLER2D(s_worldAtlas, 0);
uniform vec4 u_worldAtlas;
// x: fog start depth, y: depth where fog reaches z, z: maximum fog, w: cut depth.
uniform vec4 u_worldFogRange;
void main()
{
    // The World cut, per pixel as for objects; fog is full there, so it never shows.
    if (v_depth > u_worldFogRange.w) {
        discard;
    }
    vec3 base = v_color.rgb * 255.0;
    if (u_worldAtlas.w > 0.5) {
        vec4 texel = texture2D(s_worldAtlas, v_uvFog.xy);
        if (texel.a >= 0.5) {
            base = texel.rgb * 255.0;
        }
    }
    vec3 rgb = floor(base * (1.0 - v_uvFog.z) + v_fogColor * v_uvFog.z);
    gl_FragColor = vec4(rgb / 255.0, 1.0);
}
