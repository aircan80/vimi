#include <bgfx_shader.sh>
// y: frame height; z: 1 when the frame origin is bottom-left; w: 1 selects the horizon gradient.
uniform vec4 u_terrainMode;
// Horizon gradient only. rgb: zenith-side colour in 0..255.
uniform vec4 u_skyTop;
// Horizon gradient only. rgb: horizon colour in 0..255, the fog colour.
uniform vec4 u_skyHorizon;
// Horizon gradient only. x: camera pitch, y: focal length in pixels, z: elevation of u_skyTop.
uniform vec4 u_skyView;
void main()
{
    float row = floor(gl_FragCoord.y);
    if (u_terrainMode.z > 0.5) row = u_terrainMode.y - 1.0 - row;
    if (u_terrainMode.w > 0.5) {
        // Elevation of this pixel row above the horizon: at and below it the sky is the fog.
        float elevation = u_skyView.x + atan((u_terrainMode.y * 0.5 - (row + 0.5)) / u_skyView.y);
        float t = clamp(elevation / u_skyView.z, 0.0, 1.0);
        gl_FragColor = vec4(floor(mix(u_skyHorizon.xyz, u_skyTop.xyz, t)) / 255.0, 1.0);
    } else {
        float t = row / u_terrainMode.y;
        gl_FragColor = vec4(floor(vec3(91.0, 141.0, 177.0) + vec3(80.0, 60.0, 37.0) * t) / 255.0, 1.0);
    }
}
