// gles-scene-test.cpp - the OpenGL ES 3.0 version of vk-scene-test: runs assets/shaders/scene.glsl (with the same prefix the
// app gives it) on Mesa's GLES, draws every case, and compares with the CPU frame. The GL calls here are the ones
// gpu/GlesPresenter.kt makes, in the same order.
//   ./run-gles.sh /path/to/cases [name-filter] [--save]
#include "common.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <X11/Xlib.h>
#include <unistd.h>

static const char* kVert = R"(#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));   // one triangle that covers the screen
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";
static const char* kFragPrefix = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\n#define GLES 1\n";

static GLuint shader(GLenum type, const std::string& src) {
    GLuint s = glCreateShader(type); const char* p = src.c_str(); glShaderSource(s, 1, &p, nullptr); glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[4096]; glGetShaderInfoLog(s, sizeof log, nullptr, log); std::fprintf(stderr, "shader compile failed:\n%s\n", log); std::exit(1); }
    return s;
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: gles-scene-test cases-dir [filter] [--save]\n"); return 2; }
    std::string dir = argv[1], filter; bool save = false;
    for (int i = 2; i < argc; i++) { if (!std::strcmp(argv[i], "--save")) save = true; else filter = argv[i]; }
    int W = 0, H = 0; { std::ifstream m(dir + "/meta.txt"); m >> W >> H; }
    if (W <= 0) { std::fprintf(stderr, "no meta.txt in %s\n", dir.c_str()); return 2; }

    Display* xd = XOpenDisplay(nullptr);
    if (!xd) { std::fprintf(stderr, "no X display\n"); return 2; }
    Window win = XCreateSimpleWindow(xd, DefaultRootWindow(xd), 0, 0, W, H, 0, 0, 0);
    XSelectInput(xd, win, StructureNotifyMask); XMapWindow(xd, win);
    for (XEvent e;;) { XNextEvent(xd, &e); if (e.type == MapNotify) break; }

    EGLDisplay dpy = eglGetDisplay((EGLNativeDisplayType)xd); EGLint maj, min;
    if (!eglInitialize(dpy, &maj, &min)) { std::fprintf(stderr, "eglInitialize failed\n"); return 1; }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint ca[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_NONE};
    EGLConfig cfg; EGLint nc = 0; eglChooseConfig(dpy, ca, &cfg, 1, &nc);
    if (!nc) { std::fprintf(stderr, "no ES 3.0 config\n"); return 1; }
    const EGLint xa[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, xa);
    EGLSurface surf = eglCreateWindowSurface(dpy, cfg, (EGLNativeWindowType)win, nullptr);
    if (ctx == EGL_NO_CONTEXT || surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) { std::fprintf(stderr, "EGL setup failed\n"); return 1; }
    std::printf("GL: %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    std::string glsl; { std::ifstream f("../../app/src/main/assets/shaders/scene.glsl"); glsl.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>()); }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, shader(GL_VERTEX_SHADER, kVert)); glAttachShader(prog, shader(GL_FRAGMENT_SHADER, std::string(kFragPrefix) + glsl));
    glLinkProgram(prog); GLint ok = 0; glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) { char log[4096]; glGetProgramInfoLog(prog, sizeof log, nullptr, log); std::fprintf(stderr, "link failed:\n%s\n", log); return 1; }
    glUseProgram(prog);
    glUniform1i(glGetUniformLocation(prog, "uData"), 0); glUniform1i(glGetUniformLocation(prog, "uTex"), 1); glUniform1i(glGetUniformLocation(prog, "uFrame"), 2);

    const int DW = 2048, DH = 8, TL = 8, TS = 64;
    GLuint t[3]; glGenTextures(3, t);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, t[0]);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, DW, DH, 0, GL_RGBA, GL_FLOAT, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D_ARRAY, t[1]);
    glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, TS, TS, TL, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, t[2]);     // the CPU-frame texture (open-world levels), not used by these cases
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

    std::printf("%-26s %8s %8s %8s %8s\n", "case", "block", "maxblock", "blk>4%", "pixel");
    int run = 0, bad = 0; double worst = 0;
    for (const std::string& n : listCases(dir)) {
        if (!filter.empty() && n.find(filter) == std::string::npos) continue;
        auto scene = readFile(dir + "/" + n + ".scene.f32"), tex = readFile(dir + "/" + n + ".tex.u32"), cpu = readFile(dir + "/" + n + ".cpu.raw");
        if (scene.size() != size_t(DW) * DH * 16 || tex.size() != size_t(TL) * TS * TS * 4 || cpu.size() != size_t(W) * H * 4) { std::printf("%-26s bad data sizes\n", n.c_str()); bad++; continue; }
        float* f = reinterpret_cast<float*>(scene.data());
        f[12 * 4] = float(W); f[12 * 4 + 1] = float(H); f[12 * 4 + 2] = 0.0f; f[12 * 4 + 3] = 0.0f;   // view size and mode: the presenter's job
        glActiveTexture(GL_TEXTURE0); glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, DW, DH, GL_RGBA, GL_FLOAT, f);
        glActiveTexture(GL_TEXTURE1); glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, TS, TS, TL, GL_RGBA, GL_UNSIGNED_BYTE, tex.data());
        glViewport(0, 0, W, H); glDrawArrays(GL_TRIANGLES, 0, 3);
        std::vector<uint8_t> rgba(size_t(W) * H * 4); glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
        GLenum err = glGetError(); if (err) { std::printf("%-26s GL error 0x%x\n", n.c_str(), err); return 1; }
        std::vector<uint32_t> gpu(size_t(W) * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) { const uint8_t* p = &rgba[(size_t(H - 1 - y) * W + x) * 4]; gpu[size_t(y) * W + x] = uint32_t(p[0]) << 16 | uint32_t(p[1]) << 8 | p[2]; }   // glReadPixels starts at the bottom row
        Metrics m = compare(gpu.data(), reinterpret_cast<const uint32_t*>(cpu.data()), W, H);
        run++; worst = std::max(worst, m.block);
        std::printf("%-26s %8.2f %8.2f %8.1f %8.2f\n", n.c_str(), m.block, m.maxBlock, m.over4, m.pixel); std::fflush(stdout);
        if (save) savePpm(dir + "/" + n + ".gles.ppm", gpu, W, H);
    }
    std::printf("\n%d cases, worst block error %.2f\n", run, worst);
    return bad ? 1 : 0;
}
