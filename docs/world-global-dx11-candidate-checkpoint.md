# Standalone global candidate DX11 artifacts

Actual Windows compilation succeeded in GitHub run41879735899 at93bd9ee883222ebf087f05afd09912078449d61d
(PR161). Downloaded artifact bgfx-world-global-candidate-shaders contains eight
candidate binaries. Independent review verifies pinned Windows compiler SHA
`d5eb81c22d68283bda50791242312e3a34f3b71ca25be883b0ff924633525a7a`,
API156, matching bgfx780f7bf/LWJGL3.4.3/shaderc1.19.156, exact input hashes and
DXBC-bearing shader headers. The six previous binaries are byte-identical.

The exact authoritative binary digests are:

- Vertex: `1cb8b5a984d662eb08753af7de4dcaeeac79261c0b28a7b40ff554cf1e1fc40b`
- Fragment: `6381aa3eb0030330e3e46ea8e5c1fe8961706a75166bd6be3e6f3b8c1ef5e1bd`

Independent review caught Windows CRLF translation in generated digest sidecars.
Staging retains canonical65-byte ASCII SHA+LF, the generator now writes and
verifies raw bytes, and targeted Git attributes preserve LF on Windows checkout.
The runtime's strict digest predicate remains unchanged.

All eight resources remain test-only candidate shaders. Explicit full verification:
`python3 scripts/rendering/compile_bgfx_world_global_candidate.py --include-dx11 --verify`.
The private renderer can load the compiled DX11 candidate; this is NOT a Windows
GPU rendering proof. Production forty blobs/default runtime probe are unchanged.
No instance GUI, child process, Metal/D3D hardware qualification or default48blob
production distribution is enabled by this checkpoint.
