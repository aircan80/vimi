$input v_wmoNormal, v_wmoUv, v_wmoDepth
#include <bgfx_shader.sh>
SAMPLER2D(s_wmoDiffuse, 0);
// x: alpha-test reference (0 never discards); y: 1 outputs sampled alpha for blending, 0 outputs 1;
// z: 1 turns the alpha test into an alpha-to-coverage ramp around the reference (multisampled).
uniform vec4 u_wmoMaterial;
// rgb: fog colour in 0..255; w unused.
uniform vec4 u_wmoFog;
// x: fog start depth, y: depth where fog reaches z, z: maximum fog, w: unused.
uniform vec4 u_wmoFogRange;
void main()
{
    vec4 sampled = texture2D(s_wmoDiffuse, v_wmoUv);
    // Derivatives before any discard, so every pixel of the quad takes part.
    float ramp = max(fwidth(sampled.a), 0.0001);
    if (v_wmoDepth > 2200.0) {
        discard;
    }
    float alpha = mix(1.0, sampled.a, u_wmoMaterial.y);
    if (u_wmoMaterial.z > 0.5) {
        alpha = clamp((sampled.a - u_wmoMaterial.x) / ramp + 0.5, 0.0, 1.0);
        if (alpha <= 0.0) {
            discard;
        }
    } else if (sampled.a < u_wmoMaterial.x) {
        discard;
    }
    float squared = dot(v_wmoNormal, v_wmoNormal);
    float diffuse = 0.0;
    if (squared > 1e-12) {
        vec3 n = v_wmoNormal * inversesqrt(squared);
        diffuse = max(0.0, dot(n, vec3(-0.408248290463863, -0.408248290463863, 0.816496580927726)));
    }
    float intensity = (1.0 / 3.0) + (2.0 / 3.0) * diffuse;
    float fog = clamp((v_wmoDepth - u_wmoFogRange.x) / (u_wmoFogRange.y - u_wmoFogRange.x), 0.0, u_wmoFogRange.z);
    vec3 rgb = floor(sampled.rgb * 255.0 * intensity * (1.0 - fog) + u_wmoFog.xyz * fog);
    gl_FragColor = vec4(rgb / 255.0, alpha);
}
