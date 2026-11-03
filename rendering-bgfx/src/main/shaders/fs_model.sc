$input v_position, v_texcoord0, v_lighting, v_environment, v_billboard

#include <bgfx_shader.sh>

SAMPLER2D(s_diffuse, 0);
SAMPLER2D(s_environment, 1);
uniform vec4 u_lightDirection[2];
// x=textures enabled, y=combiner, z=lighting, w=blend; explicit stable type codes.
uniform vec4 u_material;
// x=source vertex normals, y=diagnostic edge pass.
uniform vec4 u_drawFlags;

void main()
{
    vec4 sampled = vec4_splat(1.0);
    if (u_material.x > 0.5)
    {
        sampled = texture2D(s_diffuse, v_texcoord0);
    }
    if (u_material.w > 1.5 && u_material.w < 2.5 && sampled.a < 128.0 / 255.0)
    {
        discard;
    }
    if (u_drawFlags.y > 0.5)
    {
        gl_FragColor = vec4(vec3(171.0, 197.0, 177.0) / 255.0, 1.0);
    }
    else
    {
        vec3 normal = cross(dFdx(v_position), dFdy(v_position));
        vec3 light = u_lightDirection[0].xyz;
        if (v_billboard > 0.5)
        {
            normal = vec3(normal.y, normal.z, -normal.x);
            light = u_lightDirection[1].xyz;
        }
        float normalLength = length(normal);
        float flatLight = normalLength > 0.0
            ? 0.28 + 0.72 * min(1.0, abs(dot(normal, light)) / normalLength)
            : 0.28;
        float lighting = u_material.x > 0.5 && u_drawFlags.x > 0.5 ? v_lighting : flatLight;
        if (u_material.z > 1.5) lighting = 1.0;
        vec3 base = u_material.x > 0.5 ? sampled.rgb : vec3(190.0, 176.0, 153.0) / 255.0;
        if (u_material.y > 1.5)
        {
            base *= texture2D(s_environment, v_environment).rgb * 2.0;
        }
        // Keep the ENV product unclamped until lighting and final UNORM conversion.
        float alpha = u_material.w > 2.5 ? sampled.a : 1.0;
        gl_FragColor = vec4(base * lighting, alpha);
    }
}
