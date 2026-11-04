$input a_position, i_data0, i_data1, i_data2
$output v_color, v_uvFog
#include <bgfx_shader.sh>
uniform mat4 u_worldEye;
uniform mat4 u_worldProjection;
uniform vec4 u_worldRebase;
uniform vec4 u_worldAtlas;
void main()
{
    vec4 a = mul(u_worldEye, vec4(i_data0.xyz - u_worldRebase.xyz, 1.0));
    vec4 b = mul(u_worldEye, vec4(i_data1.xyz - u_worldRebase.xyz, 1.0));
    vec4 c = mul(u_worldEye, vec4(i_data2.xyz - u_worldRebase.xyz, 1.0));
    float farDepth = max(-a.z, max(-b.z, -c.z));
    float nearDepth = min(-a.z, min(-b.z, -c.z));
    if (farDepth < 0.5 || nearDepth > 2200.0) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        v_color = vec4_splat(0.0);
        v_uvFog = vec3_splat(0.0);
    } else {
        vec4 selected = a_position.x < 0.5 ? a : (a_position.x < 1.5 ? b : c);
        vec3 nativePosition = a_position.x < 0.5 ? i_data0.xyz : (a_position.x < 1.5 ? i_data1.xyz : i_data2.xyz);
        gl_Position = mul(u_worldProjection, selected);
        float fog = clamp((farDepth - 600.0) / 1600.0, 0.0, 0.85);
        vec3 rgb = floor(vec3(i_data0.w, i_data1.w, i_data2.w) * (1.0 - fog) + vec3(153.0, 184.0, 196.0) * fog);
        v_color = vec4(rgb / 255.0, 1.0);
        vec2 uv = vec2(u_worldAtlas.y - nativePosition.y, u_worldAtlas.x - nativePosition.x) * u_worldAtlas.z;
        v_uvFog = vec3(uv, fog);
    }
}
