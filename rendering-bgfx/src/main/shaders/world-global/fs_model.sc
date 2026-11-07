$input v_globalUv
#include <bgfx_shader.sh>
SAMPLER2D(s_globalDiffuse, 0);
// x: alpha-test reference (0 never discards); y: 1 outputs the sampled alpha for blending, 0
// outputs 1 (#297, the outdoor objects' material mapping).
uniform vec4 u_globalMaterial;
void main()
{
    vec4 sampled = texture2D(s_globalDiffuse, v_globalUv);
    if (sampled.a < u_globalMaterial.x) {
        discard;
    }
    gl_FragColor = vec4(sampled.rgb, mix(1.0, sampled.a, u_globalMaterial.y));
}
