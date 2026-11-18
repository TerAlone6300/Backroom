#version 450
// The frame texture is VK_FORMAT_B8G8R8A8_UNORM, so sampling already returns RGB in the right order.
layout(set = 0, binding = 0) uniform sampler2D uTex;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
