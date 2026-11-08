$input a_position, a_normal, a_texcoord0, a_texcoord1
$output v_position, v_texcoord0, v_lighting, v_environment, v_billboard

#include <bgfx_shader.sh>

uniform mat4 u_modelToEye;
uniform mat4 u_projection;
uniform vec4 u_lightDirection[2];

vec2 sphereCoordinate(vec3 position, vec3 normal)
{
    float p2 = dot(position, position);
    float n2 = dot(normal, normal);
    if (p2 <= 1e-12 || n2 <= 1e-12)
    {
        return vec2_splat(0.5);
    }
    vec3 u = position / sqrt(p2);
    vec3 n = normal / sqrt(n2);
    vec3 f = u - 2.0 * n * dot(n, u);
    float q = dot(f.xy, f.xy) + (f.z + 1.0) * (f.z + 1.0);
    if (q <= 1e-12)
    {
        return vec2_splat(0.5);
    }
    return vec2_splat(0.5) + f.xy / (2.0 * sqrt(q));
}

void main()
{
    vec4 eye = mul(u_modelToEye, vec4(a_position, 1.0));
    vec3 eyeNormal = mul(u_modelToEye, vec4(a_normal, 0.0)).xyz;
    if (a_texcoord1.w > 1.5)
    {
        // Cylindrical card (2: turns about model Z, 3: about model Y). The fixed axis keeps its
        // eye-space direction and the card turns about it, so that its local X lies along the
        // line of sight as in the spherical card below (eye -Z): with the eye level with the
        // card it is exactly that card.
        vec3 delta = a_position - a_texcoord1.xyz;
        eye = mul(u_modelToEye, vec4(a_texcoord1.xyz, 1.0));
        vec3 fixedAxis = a_texcoord1.w > 2.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
        vec3 axis = normalize(mul(u_modelToEye, vec4(fixedAxis, 0.0)).xyz);
        vec3 toward = -eye.xyz - axis * dot(-eye.xyz, axis);
        if (dot(toward, toward) < 1e-12)
        {
            toward = vec3(0.0, 0.0, 1.0) - axis * dot(vec3(0.0, 0.0, 1.0), axis);
        }
        if (dot(toward, toward) < 1e-12)
        {
            toward = vec3(1.0, 0.0, 0.0) - axis * dot(vec3(1.0, 0.0, 0.0), axis);
        }
        // The spherical card maps local X to eye -Z, away from the eye.
        vec3 away = -normalize(toward);
        vec3 right = a_texcoord1.w > 2.5 ? axis : cross(away, axis);
        vec3 up = a_texcoord1.w > 2.5 ? cross(axis, away) : axis;
        eye.xyz += delta.x * away + delta.y * right + delta.z * up;
        eyeNormal = a_normal.x * away + a_normal.y * right + a_normal.z * up;
    }
    else if (a_texcoord1.w > 0.5)
    {
        vec3 delta = a_position - a_texcoord1.xyz;
        eye = mul(u_modelToEye, vec4(a_texcoord1.xyz, 1.0));
        eye.xyz += vec3(delta.y, delta.z, -delta.x);
        eyeNormal = vec3(a_normal.y, a_normal.z, -a_normal.x);
    }
    gl_Position = mul(u_projection, eye);
    v_position = a_position;
    v_texcoord0 = a_texcoord0;
    v_billboard = a_texcoord1.w;
    // Deliberately per vertex, then perspective-interpolated by the rasterizer.
    v_environment = sphereCoordinate(eye.xyz, eyeNormal);
    float normalLength = length(a_normal);
    v_lighting = normalLength > 0.0
        ? 0.28 + 0.72 * min(1.0, abs(dot(a_normal, u_lightDirection[0].xyz)) / normalLength)
        : 0.28;
}
