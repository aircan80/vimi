$input a_position, a_normal, a_texcoord0
$output v_wmoNormal, v_wmoUv, v_wmoDepth
#include <bgfx_shader.sh>
uniform mat4 u_wmoEye;
uniform mat4 u_wmoProjection;
uniform mat4 u_wmoModel;
uniform mat4 u_wmoNormal;
void main()
{
    vec4 relative = mul(u_wmoModel, vec4(a_position, 1.0));
    vec4 eye = mul(u_wmoEye, relative);
    gl_Position = mul(u_wmoProjection, eye);
    v_wmoNormal = mul(u_wmoNormal, vec4(a_normal, 0.0)).xyz;
    v_wmoUv = a_texcoord0;
    v_wmoDepth = -eye.z;
}
