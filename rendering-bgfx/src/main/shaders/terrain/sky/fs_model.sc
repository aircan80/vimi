#include <bgfx_shader.sh>
uniform vec4 u_terrainMode;
void main()
{
    float row = floor(gl_FragCoord.y);
    if (u_terrainMode.z > 0.5) row = u_terrainMode.y - 1.0 - row;
    float t = row / u_terrainMode.y;
    gl_FragColor = vec4(floor(vec3(91.0, 141.0, 177.0) + vec3(80.0, 60.0, 37.0) * t) / 255.0, 1.0);
}
