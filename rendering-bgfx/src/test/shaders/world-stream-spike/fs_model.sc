$input v_color, v_uvFog
#include <bgfx_shader.sh>
SAMPLER2D(s_worldAtlas, 0);
uniform vec4 u_worldAtlas;
void main()
{
    if (u_worldAtlas.w < 0.5) {
        gl_FragColor = v_color;
    } else {
        vec4 texel = texture2D(s_worldAtlas, v_uvFog.xy);
        if (texel.a < 0.5) {
            gl_FragColor = v_color;
        } else {
            vec3 rgb = floor(texel.rgb * 255.0 * (1.0 - v_uvFog.z) + vec3(153.0, 184.0, 196.0) * v_uvFog.z);
            gl_FragColor = vec4(rgb / 255.0, 1.0);
        }
    }
}
