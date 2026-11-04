$input v_color, v_terrainXYFog
#include <bgfx_shader.sh>
SAMPLER2D(s_terrainAtlas, 0);
uniform vec4 u_terrainAtlas;
void main()
{
    if (u_terrainAtlas.w < 0.5) {
        gl_FragColor = v_color;
    } else {
        vec2 uv = vec2(u_terrainAtlas.y - v_terrainXYFog.y, u_terrainAtlas.x - v_terrainXYFog.x) * u_terrainAtlas.z;
        vec4 sampleColor = texture2D(s_terrainAtlas, uv);
        if (sampleColor.a < 0.5) {
            gl_FragColor = v_color;
        } else {
            float fog = v_terrainXYFog.z;
            vec3 rgb = floor(sampleColor.rgb * 255.0 * (1.0 - fog) + vec3(153.0, 184.0, 196.0) * fog);
            gl_FragColor = vec4(rgb / 255.0, 1.0);
        }
    }
}
