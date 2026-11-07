$input v_color, v_uvFog, v_fogColor, v_normal, v_mccv
#include <bgfx_shader.sh>
#include "splat.sh"
SAMPLER2D(s_worldAtlas, 0);
uniform vec4 u_worldAtlas;
// The World light (#373): [0] direction toward the light, w 1 when lighting is on; [1] diffuse rgb;
// [2] ambient rgb. A surface is its albedo times ambient + diffuse * max(0, n . direction).
uniform vec4 u_worldLight[3];
// No discard, so terrain keeps early depth testing. Chunks wholly past the cut are culled on the
// CPU; the few triangles of a straddling chunk drawn past it are fully fogged into the sky.
void main()
{
    float fade = splatFade(v_uvFog.xy);
    vec3 base = v_color.rgb * 255.0;
    if (u_worldAtlas.w > 0.5) {
        vec4 texel = texture2D(s_worldAtlas, v_uvFog.xy);
        vec4 splat = vec4_splat(0.0);
        if (u_worldSplat.x > 0.5) {
            splat = splatAlbedo(v_uvFog.xy);
        }
        if (texel.a >= 0.5) {
            // The atlas bakes MCCV in; the splat takes it from the vertex (#373).
            vec3 tinted = splat.rgb * mix(vec3_splat(1.0), 2.0 * v_mccv.rgb, v_mccv.a);
            base = mix(min(tinted, vec3_splat(255.0)), texel.rgb * 255.0, max(fade, 1.0 - splat.a));
        }
    }
    float squared = dot(v_normal, v_normal);
    float lambert = max(0.0, dot(v_normal * inversesqrt(max(squared, 1e-6)), u_worldLight[0].xyz));
    // Lit only when lighting is on and the vertex has a normal (water has none).
    float lit = u_worldLight[0].w * step(0.25, squared);
    base *= mix(vec3_splat(1.0), u_worldLight[2].rgb + u_worldLight[1].rgb * lambert, lit);
    vec3 rgb = floor(base * (1.0 - v_uvFog.z) + v_fogColor * v_uvFog.z);
    gl_FragColor = vec4(rgb / 255.0, 1.0);
}
