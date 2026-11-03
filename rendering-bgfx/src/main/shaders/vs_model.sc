$input a_position, a_normal, a_texcoord0
$output v_position, v_texcoord0, v_lighting

#include <bgfx_shader.sh>

uniform mat4 u_modelToClip;
uniform vec4 u_lightDirection;

void main()
{
    gl_Position = mul(u_modelToClip, vec4(a_position, 1.0));
    v_position = a_position;
    v_texcoord0 = a_texcoord0;
    float normalLength = length(a_normal);
    v_lighting = normalLength > 0.0
        ? 0.28 + 0.72 * min(1.0, abs(dot(a_normal, u_lightDirection.xyz)) / normalLength)
        : 0.28;
}
