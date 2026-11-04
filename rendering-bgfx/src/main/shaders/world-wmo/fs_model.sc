$input v_wmoNormal, v_wmoUv, v_wmoDepth
#include <bgfx_shader.sh>
SAMPLER2D(s_wmoDiffuse, 0);
void main()
{
    if (v_wmoDepth > 2200.0) {
        discard;
    }
    float squared = dot(v_wmoNormal, v_wmoNormal);
    float diffuse = 0.0;
    if (squared > 1e-12) {
        vec3 n = v_wmoNormal * inversesqrt(squared);
        diffuse = max(0.0, dot(n, vec3(-0.408248290463863, -0.408248290463863, 0.816496580927726)));
    }
    float intensity = (1.0 / 3.0) + (2.0 / 3.0) * diffuse;
    float fog = clamp((v_wmoDepth - 600.0) / 1600.0, 0.0, 0.85);
    vec3 sampled = texture2D(s_wmoDiffuse, v_wmoUv).rgb;
    vec3 rgb = floor(sampled * 255.0 * intensity * (1.0 - fog) + vec3(153.0, 184.0, 196.0) * fog);
    gl_FragColor = vec4(rgb / 255.0, 1.0);
}
