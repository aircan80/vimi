$input a_position, a_color0
$output v_color, v_uvFog, v_fogColor
#include <bgfx_shader.sh>
uniform mat4 u_worldEye;
uniform mat4 u_worldProjection;
uniform vec4 u_worldRebase;
uniform vec4 u_worldAtlas;
// rgb: sky and fog colour at and below the horizon, 0..255; w: elevation (radians) of u_worldSkyTop.
uniform vec4 u_worldFog;
// rgb: sky colour at and above u_worldFog.w, 0..255; w unused.
uniform vec4 u_worldSkyTop;
// x: fog start depth, y: depth where fog reaches z, z: maximum fog, w: cut depth.
uniform vec4 u_worldFogRange;
void main()
{
    // The rebase origin is the camera: positions relative to it are also the view direction.
    vec3 relative = a_position - u_worldRebase.xyz;
    vec4 eye = mul(u_worldEye, vec4(relative, 1.0));
    gl_Position = mul(u_worldProjection, eye);
    float depth = -eye.z;
    // Fog toward the sky at this vertex's own elevation.
    float elevation = atan2(relative.z, max(length(relative.xy), 1e-6));
    vec3 sky = mix(u_worldFog.xyz, u_worldSkyTop.xyz, clamp(elevation / u_worldFog.w, 0.0, 1.0));
    float fog = clamp((depth - u_worldFogRange.x) / (u_worldFogRange.y - u_worldFogRange.x), 0.0, u_worldFogRange.z);
    v_color = a_color0;
    vec2 uv = vec2(u_worldAtlas.y - a_position.y, u_worldAtlas.x - a_position.x) * u_worldAtlas.z;
    v_uvFog = vec3(uv, fog);
    v_fogColor = sky;
}
