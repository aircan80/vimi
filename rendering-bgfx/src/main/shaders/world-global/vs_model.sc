$input a_position, a_texcoord0
$output v_globalUv
#include <bgfx_shader.sh>
uniform mat4 u_globalEye;
uniform mat4 u_globalProjection;
void main()
{
    gl_Position = mul(u_globalProjection, mul(u_globalEye, vec4(a_position, 1.0)));
    v_globalUv = a_texcoord0;
}
