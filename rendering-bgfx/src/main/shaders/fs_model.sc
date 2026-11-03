$input v_position, v_texcoord0, v_lighting

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);
uniform vec4 u_lightDirection;
uniform vec4 u_material;

void main()
{
    vec4 sampled = vec4_splat(1.0);
    if (u_material.x > 0.5 || u_material.y > 0.5)
    {
        sampled = texture2D(s_diffuse, v_texcoord0);
    }
    if (u_material.y > 0.5 && sampled.a < 128.0 / 255.0)
    {
        discard;
    }
    vec3 normal = cross(dFdx(v_position), dFdy(v_position));
    float normalLength = length(normal);
    float flatLight = normalLength > 0.0
        ? 0.28 + 0.72 * min(1.0, abs(dot(normal, u_lightDirection.xyz)) / normalLength)
        : 0.28;
    float lighting = u_material.x > 0.5 && u_material.z > 0.5 ? v_lighting : flatLight;
    vec3 base = u_material.x > 0.5 ? sampled.rgb : vec3(190.0, 176.0, 153.0) / 255.0;
    gl_FragColor = u_material.w > 0.5
        ? vec4(vec3(171.0, 197.0, 177.0) / 255.0, 1.0)
        : vec4(base * lighting, 1.0);
}
