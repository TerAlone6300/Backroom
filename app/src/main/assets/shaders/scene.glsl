// scene.glsl - the GPU renderer: draws the whole 3D view from the scene data (core/Scene.kt, filled by Renderer.buildScene).
// One source for both backends: OpenGL ES 3.0 (the app puts "#version 300 es", precision lines and "#define GLES" in front)
// and Vulkan (compile_shaders.sh puts "#version 450" in front; glslang defines VULKAN itself).
// It follows Renderer.render() line by line: colours are kept as 0..255 numbers and cut down like the CPU's Int maths, so
// the two pictures stay within a level or two of each other (tools/scene-test measures it). Only the film grain differs:
// it is the same amount of noise, drawn from a different random stream.

#ifdef VULKAN
layout(set = 0, binding = 0) uniform sampler2D uData;       // the scene data: RGBA32F, 2048 x 8
layout(set = 0, binding = 1) uniform sampler2DArray uTex;   // the level's 64x64 textures, 8 layers
layout(set = 0, binding = 2) uniform sampler2D uFrame;      // a frame the CPU drew (open-world levels)
layout(location = 0) out vec4 outColor;
#define SWZ(v) (v).rgb
#define FLIPY(y, h) (y)
#else
uniform sampler2D uData;
uniform sampler2DArray uTex;
uniform sampler2D uFrame;
out vec4 outColor;
#define SWZ(v) (v).bgr                                       // uploaded as bytes B,G,R,A and read as RGBA
#define FLIPY(y, h) ((h) - (y))                              // gl_FragCoord.y counts from the bottom in OpenGL
#endif

// Layout of the scene data: the same numbers as core/Scene.kt
const int SPR = 64, GLI = 192, MAP = 256, HUDL = 288, LMP = 512, LW = 65, COL = 8192;

vec4 D(int i) { return texelFetch(uData, ivec2(i & 2047, i >> 11), 0); }
int imod(int a, int b) { int m = a - b * int(floor(float(a) / float(b))); return m; }   // % is undefined for negative operands
float sq(float a) { return a * a; }

// ---- header, read once per pixel ----
float gX, gY, gDx, gDy, gPlX, gPlY, gHz, gVs, gFk, gGl, gT, gPs;
float gPeriod, gLamp0, gIp, gAmbient, gFalloff, gLantern;
vec3 gTn; bool gZones;
float gW, gH; int gKx0, gKy0;
bool gWater, gManilaOn; int gManilaX, gManilaY;
int gBx, gBy; float gFx, gFy;
uint gSeed;
int gQuality;   // 0 Low / 1 Medium / 2 High — from Settings.quality

void loadHeader() {
    vec4 a;
    a = D(0);  gX = a.x; gY = a.y; gDx = a.z; gDy = a.w;
    a = D(1);  gPlX = a.x; gPlY = a.y; gHz = a.z; gVs = a.w;
    a = D(2);  gFk = a.x; gGl = a.y; gT = a.z; gPs = a.w;
    a = D(3);  gPeriod = a.x; gLamp0 = a.y; gIp = a.z; gAmbient = a.w;
    a = D(4);  gFalloff = a.x; gLantern = a.y;
    a = D(5);  gTn = a.xyz; gZones = a.w > 0.5;
    a = D(6);  gW = a.x; gH = a.y; gKx0 = int(a.z); gKy0 = int(a.w);
    a = D(7);  gWater = a.x > 0.5; gManilaOn = a.y > 0.5; gManilaX = int(a.z); gManilaY = int(a.w);
    a = D(9);  gSeed = uint(a.y) | (uint(a.z) << 16u); gQuality = int(a.w);
    a = D(13); gBx = int(a.x); gBy = int(a.y); gFx = a.z; gFy = a.w;
}

float hashd(int x, int y, int s) {   // core/Util.kt h()
    uint n = uint(x) * 374761393u + uint(y) * 668265263u + uint(s) * 1274126177u + gSeed;
    n = (n ^ (n >> 13u)) * 1274126177u;
    n = n ^ (n >> 16u);
    return float(n & 65535u) / 65536.0;
}

// ---- texture lookups and lighting ----
vec3 texel(int layer, int tx, int ty) { return floor(SWZ(texelFetch(uTex, ivec3(tx, ty, layer), 0)) * 255.0 + 0.5); }

// Renderer.mul(): colour * brightness * level tint, cut down like the CPU's Int maths
vec3 mulc(vec3 c, float f, vec3 tn) {
    float k = clamp(floor(f * 256.0), 0.0, 640.0);
    return min(floor(c * (k * tn) / 65536.0), 255.0);
}

vec2 lampTex(int kx, int ky) {      // (flicker, blackout zone) of a lamp; outside the window: a plain lamp
    int i = kx - gKx0, j = ky - gKy0;
    if (i < 0 || j < 0 || i >= LW || j >= LW) return vec2(1.0, 1.0);
    return D(LMP + j * LW + i).xy;
}

// Renderer.lightAt(). (lx, ly) is the point relative to the camera's cell, off its offset from the camera.
float lightAt(float lx, float ly, vec2 off) {
    int p = int(gPeriod);
    int kx = int(floor((float(gBx) - gLamp0 + lx) * gIp + 0.5));
    int ky = int(floor((float(gBy) - gLamp0 + ly) * gIp + 0.5));
    float ddx = float(gBx - kx * p) + lx - gLamp0;
    float ddy = float(gBy - ky * p) + ly - gLamp0;
    vec2 lz = lampTex(kx, ky);
    float lamp = (gAmbient + lz.x / (1.0 + (ddx * ddx + ddy * ddy) * gFalloff)) * (gZones ? lz.y : 1.0);
    if (gLantern > 0.0) lamp += gLantern / (1.0 + (off.x * off.x + off.y * off.y) * 0.35);
    return lamp;
}

// ---- floor and ceiling (Renderer.render(): the rows) ----
vec3 floorCeil(int xx, int yy) {
    int dr = yy - int(gHz);
    if (dr == 0) return vec3(8.0, 6.0, 4.0);
    float rd = 0.5 * gH * gVs / abs(float(dr));
    float fog = 1.0 / (1.0 + rd * rd * gFk);
    float cx = 2.0 * float(xx) / gW - 1.0;
    vec2 off = vec2(gDx + gPlX * cx, gDy + gPlY * cx) * rd;
    float lx = gFx + off.x, ly = gFy + off.y;             // position relative to the camera's cell
    float flx = floor(lx), fly = floor(ly);
    int cellX = gBx + int(flx), cellY = gBy + int(fly);
    int tx = int((lx - flx) * 64.0) & 63, ty = int((ly - fly) * 64.0) & 63;
    bool man = gManilaOn && cellX >= gManilaX && cellX < gManilaX + 9 && cellY >= gManilaY && cellY < gManilaY + 9;
    int base = man ? 4 : 0;
    float l = man ? 1.05 : lightAt(lx, ly, off) * gGl;
    if (dr > 0) {
        float wat = 1.0;
        // Low quality: skip water ripple (ALU + trig) — flat wet look still via 0.85
        if (gWater) wat = (gQuality < 1) ? 0.85 : (0.85 + 0.2 * sin((float(gBx) + lx) * 9.0 + gT * 1.5) * sin((float(gBy) + ly) * 9.0 + gT * 1.1));
        return mulc(texel(base + 1, tx, ty), l * fog * 0.85 * wat, gTn);
    }
    int p = int(gPeriod);
    if (imod(cellX, p) == 1 && imod(cellY, p) == 1) return mulc(texel(base + 3, tx, ty), l * max(fog, 0.6), gTn);
    return mulc(texel(base + 2, tx, ty), l * fog, gTn);
}

// ---- a wall pixel; A = (distance, line height, top row, texture column), B = (kr, kg, kb, brightness), C = (unstable, manila) ----
vec3 wallPixel(int xx, int yy, vec4 A, vec4 B, vec4 C) {
    int lineH = int(A.y), start = int(A.z), tx = int(A.w);
    int ty = min(63, int(float(yy - start) * (64.0 / float(lineH))));
    int base = C.y > 0.5 ? 4 : 0;
    if (C.x > 0.5) {   // an unstable wall wobbles and turns pale blue
        // Low quality: fixed pale-blue unstable look, no per-pixel sin wobble
        int tx2 = (gQuality < 1) ? tx : ((tx + int(sin(gT * 11.0 + float(yy) * 0.25) * 4.0)) & 63);
        float brw = (gQuality < 1) ? B.w * 0.95 : (B.w * (0.85 + 0.3 * sin(gT * 7.0 + float(xx) * 0.1)));
        vec3 c0 = mulc(texel(base, tx2, ty), brw, gTn);
        return clamp(vec3(floor(c0.r * 3.0 / 4.0), c0.g + 25.0, c0.b + 60.0), 0.0, 255.0);
    }
    return min(floor(texel(base, tx, ty) * B.xyz / 65536.0), 255.0);
}

// ---- sprites: the same shapes as Renderer.sprite(); the result is a colour (0..255) and 1.0, or 0 for "see-through" ----
float segd(vec2 p, vec2 a, vec2 b) { vec2 d = b - a; float t = clamp(dot(p - a, d) / dot(d, d), 0.0, 1.0); return length(p - (a + t * d)); }
vec4 SC(float r, float g, float b, float dim) { return vec4(clamp(floor(vec3(r, g, b) * dim), 0.0, 255.0), 1.0); }
vec4 PC(float r, float g, float b) { return vec4(r, g, b, 1.0); }

vec4 figure(int type, float u, float v, float dim, bool fr) {   // types 0, 3, 4, 7, 8, 9: people-shaped
    bool inside;
    if (v < 0.17) inside = sq(u / 0.42) + sq((v - 0.09) / 0.085) < 1.0;
    else if (v < 0.22) inside = abs(u) < 0.18;
    else if (v < 0.62) inside = abs(u) < 0.8 - (v - 0.22) * 0.5;
    else inside = abs(abs(u) - 0.35) < 0.22;
    if (!inside) return vec4(0.0);
    if (type == 4) {
        if (v < 0.17) {
            float sm = exp(-(sq((abs(u) - 0.12) / 0.07) + sq((v - 0.075) / 0.02))) * 0.25 + exp(-(sq(u / 0.1) + sq((v - 0.135) / 0.012))) * 0.2;
            float f = 1.0 - 0.5 * sm;
            return SC(floor(226.0 * f), floor(206.0 * f), floor(190.0 * f), dim);
        }
        return v < 0.58 ? SC(62.0, 68.0, 90.0, dim) : SC(38.0, 40.0, 50.0, dim);
    }
    if (type == 7) {
        if (v < 0.17) {
            bool eye = sq((abs(u) - 0.12) / 0.05) + sq((v - 0.07) / 0.03) < 1.0;
            bool smile = abs(u) < 0.2 && abs(v - (0.11 + 0.05 * sq(u / 0.2))) < 0.012;
            return (eye || smile) ? SC(30.0, 20.0, 10.0, dim) : SC(240.0, 208.0, 60.0, dim);
        }
        return SC(120.0, 30.0, 30.0, dim);
    }
    if (type == 9) {
        if (v < 0.17) {
            if (v < 0.07 && abs(u) < 0.3) return SC(200.0, 40.0, 40.0, dim);
            if (v >= 0.09 && v <= 0.11 && abs(u) < 0.25) return SC(20.0, 20.0, 24.0, dim);
            return SC(150.0, 156.0, 168.0, dim);
        }
        if (v >= 0.52 && v <= 0.56) return SC(200.0, 160.0, 50.0, dim);
        return v < 0.6 ? SC(120.0, 128.0, 142.0, dim) : SC(76.0, 80.0, 92.0, dim);
    }
    if (type == 8) return v < 0.17 ? SC(18.0, 16.0, 20.0, dim) : SC(72.0, 72.0, 84.0, dim);
    if (type == 0 && v >= 0.075 && v <= 0.10 && abs(abs(u) - 0.17) < 0.07) return PC(255.0, 255.0, 225.0);
    if (fr && type == 3 && v >= 0.075 && v <= 0.10 && abs(abs(u) - 0.17) < 0.07) return PC(130.0, 255.0, 160.0);
    return SC(16.0, 14.0, 10.0, dim);
}

vec4 sprite(int type, float u, float v, float dim, bool fr) {
    if (type == 0 || type == 3 || type == 4 || type == 7 || type == 8 || type == 9) return figure(type, u, v, dim, fr);
    if (type == 5) {   // death moth
        float au = abs(u);
        bool body = au < 0.1 && v >= 0.15 && v <= 0.85;
        bool wing = au >= 0.1 && au <= 0.95 && abs(v - 0.45) < 0.38 * (1.0 - (au - 0.1) / 0.9);
        bool eye = sq((au - 0.5) / 0.16) + sq((v - 0.45) / 0.2) < 1.0;
        if (eye && sq((au - 0.5) / 0.07) + sq((v - 0.45) / 0.09) < 1.0) return SC(240.0, 230.0, 200.0, dim);
        if (eye) return SC(40.0, 30.0, 30.0, dim);
        if (body) return SC(90.0, 80.0, 70.0, dim);
        if (wing) return SC(176.0, 158.0, 134.0, dim);
        if (v < 0.2 && abs(au - 0.2) < 0.04) return SC(90.0, 80.0, 70.0, dim);
        return vec4(0.0);
    }
    if (type == 6) {   // clump
        bool lump = sq(u / 0.8) + sq((v - 0.62) / 0.36) < 1.0 || sq((u + 0.45) / 0.4) + sq((v - 0.45) / 0.3) < 1.0
                 || sq((u - 0.5) / 0.42) + sq((v - 0.5) / 0.32) < 1.0 || sq((u - 0.05) / 0.35) + sq((v - 0.3) / 0.24) < 1.0;
        if (!lump) return vec4(0.0);
        float f = 0.7 + 0.5 * hashd(int(u * 30.0), int(v * 30.0), 777);
        bool f1 = sq((u + 0.5) / 0.13) + sq((v - 0.5) / 0.11) < 1.0;
        bool f2 = sq((u - 0.45) / 0.13) + sq((v - 0.42) / 0.11) < 1.0;
        bool f3 = sq((u - 0.05) / 0.13) + sq((v - 0.28) / 0.11) < 1.0;
        if (f1 || f2 || f3) {
            float cu = f1 ? -0.5 : (f2 ? 0.45 : 0.05), cv = f1 ? 0.5 : (f2 ? 0.42 : 0.28);
            if (abs(abs(u - cu) - 0.05) < 0.03 && abs(v - cv + 0.02) < 0.025) return SC(20.0, 16.0, 16.0, dim);
            return SC(225.0, 200.0, 180.0, dim);
        }
        return SC(floor(130.0 * f), floor(66.0 * f), floor(60.0 * f), dim);
    }
    if (type == 10) {  // tarantula
        float au = abs(u);
        bool body = sq(u / 0.32) + sq((v - 0.6) / 0.2) < 1.0;
        bool head = sq(u / 0.14) + sq((v - 0.43) / 0.12) < 1.0;
        bool leg = false;
        for (int j = 0; j < 4; j++) {
            float fj = float(j), v0 = 0.5 + 0.05 * fj, k1 = 0.62 + 0.09 * fj, kv = 0.28 + 0.08 * fj;
            if (segd(vec2(au, v), vec2(0.28, v0), vec2(k1, kv)) < 0.03 || segd(vec2(au, v), vec2(k1, kv), vec2(0.97, 0.92)) < 0.03) leg = true;
        }
        if (head && abs(au - 0.06) < 0.035 && abs(v - 0.42) < 0.03) return PC(255.0, 40.0, 30.0);
        if (body || head) return SC(54.0, 38.0, 30.0, dim);
        if (leg) return SC(70.0, 50.0, 38.0, dim);
        return vec4(0.0);
    }
    if (type == 22) {  // lamp item
        if (v < 0.06 && abs(u) < 0.9) return PC(255.0, 236.0, 170.0);
        return abs(u) < 0.18 ? SC(70.0, 72.0, 80.0, dim) : vec4(0.0);
    }
    if (type == 20) {  // ration
        if (abs(u) > 0.7 || v < 0.15) return vec4(0.0);
        if (v < 0.3 || v > 0.9) return SC(196.0, 200.0, 204.0, dim);
        return (v >= 0.45 && v <= 0.75) ? SC(236.0, 226.0, 190.0, dim) : SC(170.0, 60.0, 46.0, dim);
    }
    if (type == 21) {  // water bottle
        bool neck = abs(u) < 0.28 && v >= 0.08 && v <= 0.3, body = abs(u) < 0.7 && v >= 0.3 && v <= 1.0;
        if (!neck && !body) return vec4(0.0);
        if (v < 0.14) return SC(210.0, 170.0, 60.0, dim);
        if (body && v >= 0.52 && v <= 0.82) return (abs(u) < 0.2 && v >= 0.6 && v <= 0.74) ? SC(150.0, 98.0, 52.0, dim) : SC(240.0, 226.0, 190.0, dim);
        return SC(170.0, 212.0, 238.0, dim);
    }
    if (type == 1) {   // hound
        bool body = sq((u - 0.1) / 0.7) + sq((v - 0.55) / 0.32) < 1.0, head = sq((u + 0.72) / 0.26) + sq((v - 0.38) / 0.22) < 1.0;
        bool legs = v > 0.78 && (abs(u - 0.65) < 0.09 || abs(u - 0.35) < 0.09 || abs(u + 0.25) < 0.09 || abs(u + 0.5) < 0.09);
        if (sq((u + 0.82) / 0.07) + sq((v - 0.33) / 0.07) < 1.0) return fr ? PC(120.0, 255.0, 150.0) : PC(255.0, 50.0, 30.0);
        return (body || head || legs) ? SC(16.0, 14.0, 10.0, dim) : vec4(0.0);
    }
    // smiler (2)
    float yc = 0.47 + 0.06 * (1.0 - sq(u / 0.62));
    bool glow = sq((abs(u) - 0.34) / 0.16) + sq((v - 0.34) / 0.03) < 1.0 || (abs(u) < 0.62 && abs(v - yc) < 0.022);
    if (glow) return fr ? PC(190.0, 255.0, 200.0) : PC(255.0, 250.0, 215.0);
    if (sq(u / 0.88) + sq((v - 0.4) / 0.22) < 1.0) return PC(26.0, 22.0, 20.0);
    return vec4(0.0);
}

vec3 withSprites(vec3 col, int xx, int yy, float perp) {
    int n = int(D(10).z);
    for (int i = 0; i < 64; i++) {
        if (i >= n) break;
        vec4 a = D(SPR + 2 * i);                       // sx, sw, top, sh
        int sx = int(a.x), sw = int(a.y), top = int(a.z), sh = int(a.w);
        if (yy < top || yy > top + sh) continue;
        if (xx < sx - sw / 2 || xx > sx + sw / 2) continue;
        vec4 b = D(SPR + 2 * i + 1);                   // type, dim, friendly, depth
        if (b.w >= perp) continue;                     // a wall is in front
        vec4 s = sprite(int(b.x), float(xx - sx) / (float(sw) / 2.0), float(yy - top) / float(sh), b.y, b.z > 0.5);
        if (s.a > 0.5) col = s.rgb;
    }
    return col;
}

// ---- post effects, HUD, row shifts ----
uint hash3(uint x, uint y, uint s) {
    uint n = x * 374761393u + y * 668265263u + s * 1274126177u;
    n = (n ^ (n >> 13u)) * 1274126177u;
    return n ^ (n >> 16u);
}

int rowShift(int y) {   // glitch rows plus the hallucination sway: rotations just add up
    vec4 e = D(10);
    int ng = int(e.y);
    float s = 0.0;
    // Low quality: no glitch row scan (CPU already wrote ng=0); skip halluc sway trig
    if (gQuality >= 1) {
        for (int i = 0; i < 64; i++) {
            if (i >= ng) break;
            vec4 g = D(GLI + i);
            if (int(g.x) == y) s += g.y;
        }
        if (e.x > 0.0) {
            float amp = e.x * 5.0 * gPs, yy = float(y);
            s += float(int(sin(yy * 0.11 + gT * 2.3) * amp + sin(yy * 0.047 - gT * 1.1) * amp * 0.6));
        }
    }
    return int(s);
}

vec3 post(vec3 c, int xx, int yy) {
    vec4 p = D(8);                                     // red pulse, fade, wash, grain amount
    float nx = (float(xx) - gW / 2.0) / (gW / 2.0), ny = (float(yy) - gH / 2.0) / (gH / 2.0);
    float vig = floor(clamp(1.0 - 0.38 * (nx * nx + ny * ny), 0.3, 1.0) * 256.0);
    float v = floor(vig * p.y / 256.0);
    // Low quality / zero grain amount: skip film-grain hash (expensive per pixel on Adreno)
    float nz = 0.0;
    if (p.w != 0.0 && gQuality >= 2) {
        nz = (float((hash3(uint(xx), uint(yy), uint(D(9).x)) >> 8u) & 15u) - 8.0) * p.w;
    }
    vec3 o = floor(c * v / 256.0) + nz;
    if (p.x != 0.0 || p.z != 0.0) {
        o.r += floor((256.0 - vig) * p.x / 256.0) + p.z;
        o.g += p.z; o.b += p.z;
    }
    return clamp(o, 0.0, 255.0);
}

vec3 hud(vec3 c, int xx, int yy) {
    int nh = int(D(10).w);
    for (int i = 0; i < 24; i++) {
        if (i >= nh) break;
        vec4 a = D(288 + 2 * i), b = D(288 + 2 * i + 1);
        vec3 col; float al;
        if (b.x < 0.0) {                               // the minimap's walls: A = (x, y, cell size)
            int cs = int(a.z), cx = xx - int(a.x), cy = yy - int(a.y);
            if (cx < 0 || cy < 0) continue;
            cx /= cs; cy /= cs;
            if (cx > 16 || cy > 16) continue;
            if (((int(D(MAP + cy).x) >> cx) & 1) == 0) continue;
            col = b.yzw; al = 210.0;
        } else {                                       // a rectangle: A = (x0, y0, x1, y1), B = (colour, alpha)
            if (xx < int(a.x) || xx >= int(a.z) || yy < int(a.y) || yy >= int(a.w)) continue;
            col = b.rgb; al = b.w;
        }
        c = floor((c * (256.0 - al) + col * al) / 256.0);
    }
    return c;
}

void main() {
    vec4 vw = D(12);                                   // view size and mode, set by the presenter
    vec2 view = vw.xy;
    vec2 fc = vec2(gl_FragCoord.x, FLIPY(gl_FragCoord.y, view.y));
    if (vw.z > 0.5) {                                  // open-world levels: the CPU drew this frame
        outColor = vec4(SWZ(texture(uFrame, fc / view)), 1.0);
        return;
    }
    loadHeader();
    int W = int(gW), H = int(gH);
    int xs = min(W - 1, int(floor(fc.x * gW / view.x))), ys = min(H - 1, int(floor(fc.y * gH / view.y)));
    int sx = imod(xs - rowShift(ys), W);               // the pixel this one shows after the row shifts
    int o = COL + sx * 3;
    vec4 A = D(o);
    int start = int(A.z), lineH = int(A.y);
    vec3 col;
    if (ys >= max(0, start) && ys <= min(H - 1, start + lineH)) col = wallPixel(sx, ys, A, D(o + 1), D(o + 2));
    else col = floorCeil(sx, ys);
    col = withSprites(col, sx, ys, A.x);
    col = post(col, sx, ys);
    col = hud(col, xs, ys);
    outColor = vec4(col / 255.0, 1.0);
}
