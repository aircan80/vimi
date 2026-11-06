$input v_color, v_uvFog, v_fogColor
#include <bgfx_shader.sh>
SAMPLER2D(s_worldAtlas, 0);
uniform vec4 u_worldAtlas;
// No discard, so terrain keeps early depth testing. Chunks wholly past the cut are culled on the
// CPU; the few triangles of a straddling chunk drawn past it are fully fogged into the sky.
void main()
{
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
