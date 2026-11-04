$input v_globalUv
#include <bgfx_shader.sh>
SAMPLER2D(s_globalDiffuse, 0);
void main()
{
    gl_FragColor = vec4(texture2D(s_globalDiffuse, v_globalUv).rgb, 1.0);
}
