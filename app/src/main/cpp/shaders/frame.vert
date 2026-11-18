#version 450
// Full-screen triangle from gl_VertexIndex alone: no vertex buffer. Vulkan clip space has y pointing down,
// so uv (0,0) lands on the top-left corner, where row 0 of the frame belongs.
layout(location = 0) out vec2 vUv;

void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
