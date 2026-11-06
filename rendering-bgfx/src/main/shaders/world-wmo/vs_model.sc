$input a_position, a_normal, a_texcoord0, i_data0, i_data1, i_data2
$output v_wmoNormal, v_wmoUv, v_wmoDepth, v_wmoRelative
#include <bgfx_shader.sh>
uniform mat4 u_wmoEye;
uniform mat4 u_wmoProjection;
void main()
{
    // Per instance (#382): the placement's scaled rotation rows with the translation, relative
    // to the camera, in w.
    vec4 p = vec4(a_position, 1.0);
    vec4 relative = vec4(dot(i_data0, p), dot(i_data1, p), dot(i_data2, p), 1.0);
    vec4 eye = mul(u_wmoEye, relative);
    gl_Position = mul(u_wmoProjection, eye);
    // Uniform scale and an orthonormal rotation: the fragment shader normalises.
    v_wmoNormal = vec3(dot(i_data0.xyz, a_normal), dot(i_data1.xyz, a_normal), dot(i_data2.xyz, a_normal));
    v_wmoUv = a_texcoord0;
    v_wmoDepth = -eye.z;
    // Position relative to the model origin, which the hot pass places at the camera.
    v_wmoRelative = relative.xyz;
}
