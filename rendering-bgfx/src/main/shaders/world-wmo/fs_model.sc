$input v_wmoNormal, v_wmoUv, v_wmoDepth, v_wmoRelative
#include <bgfx_shader.sh>
SAMPLER2D(s_wmoDiffuse, 0);
// x: alpha-test reference (0 never discards); y: 1 outputs sampled alpha for blending, 0 outputs 1;
// z: 1 turns the alpha test into an alpha-to-coverage ramp around the reference (multisampled).
uniform vec4 u_wmoMaterial;
// rgb: sky and fog colour at and below the horizon, 0..255; w: elevation (radians) of u_wmoSkyTop.
uniform vec4 u_wmoFog;
// rgb: sky colour at and above u_wmoFog.w, 0..255; w unused. Equal to u_wmoFog for flat fog.
uniform vec4 u_wmoSkyTop;
// x: fog start depth, y: depth where fog reaches z, z: maximum fog, w: cut depth.
uniform vec4 u_wmoFogRange;
void main()
{
    vec4 sampled = texture2D(s_wmoDiffuse, v_wmoUv);
    // Derivatives before any discard, so every pixel of the quad takes part.
    float ramp = max(fwidth(sampled.a), 0.0001);
    if (v_wmoDepth > u_wmoFogRange.w) {
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
    // Fog toward the sky at this fragment's own elevation above the camera.
    float elevation = atan2(v_wmoRelative.z, max(length(v_wmoRelative.xy), 1e-6));
    vec3 sky = mix(u_wmoFog.xyz, u_wmoSkyTop.xyz, clamp(elevation / u_wmoFog.w, 0.0, 1.0));
    vec3 rgb = floor(sampled.rgb * 255.0 * intensity * (1.0 - fog) + sky * fog);
    gl_FragColor = vec4(rgb / 255.0, alpha);
}
