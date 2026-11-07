$input a_position, a_normal, a_texcoord0, a_color0, i_data0, i_data1, i_data2
$output v_wmoNormal, v_wmoUv, v_wmoDepth, v_wmoRelative, v_wmoColour
#include <bgfx_shader.sh>
uniform mat4 u_wmoEye;
uniform mat4 u_wmoProjection;
void main()
{
    // Per instance (#382): the placement's scale times its rotation rows, with the translation
    // relative to the camera (the frame origin) in w.
    vec4 p = vec4(a_position, 1.0);
    vec4 relative = vec4(dot(i_data0, p), dot(i_data1, p), dot(i_data2, p), 1.0);
    vec4 eye = mul(u_wmoEye, relative);
    gl_Position = mul(u_wmoProjection, eye);
    // Uniform scale times an orthonormal rotation: dividing by the scale (a row's length) leaves
    // the rotated normal at the source normal's own length, as the old normal matrix did, so the
    // fragment shader's zero-length cutoff is unchanged.
    vec3 n = vec3(dot(i_data0.xyz, a_normal), dot(i_data1.xyz, a_normal), dot(i_data2.xyz, a_normal));
    v_wmoNormal = n * inversesqrt(max(dot(i_data0.xyz, i_data0.xyz), 1e-24));
    v_wmoUv = a_texcoord0;
    v_wmoDepth = -eye.z;
    // Position relative to the model origin, which the hot pass places at the camera.
    v_wmoRelative = relative.xyz;
    // MOCV vertex colour (0 when the group has none), added to the light in the fragment shader.
    v_wmoColour = a_color0.rgb;
}
