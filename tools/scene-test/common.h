// common.h - reading the case data and comparing two frames, shared by vk-scene-test.cpp and gles-scene-test.cpp.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

static std::vector<uint8_t> readFile(const std::string& p) {
    std::ifstream f(p, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

struct Metrics { double block = 0, maxBlock = 0, over4 = 0, pixel = 0; };

// gpu and cpu: 0x??RRGGBB per pixel
static Metrics compare(const uint32_t* gpu, const uint32_t* cpu, int W, int H) {
    Metrics m; const int B = 16; int nb = 0, over = 0; double px = 0;
    for (int by = 0; by + B <= H; by += B) for (int bx = 0; bx + B <= W; bx += B) {
        double sg[3] = {0, 0, 0}, sc[3] = {0, 0, 0};
        for (int y = by; y < by + B; y++) for (int x = bx; x < bx + B; x++) {
            uint32_t g = gpu[y * W + x], c = cpu[y * W + x];
            for (int k = 0; k < 3; k++) { int s = 16 - 8 * k; sg[k] += (g >> s) & 255; sc[k] += (c >> s) & 255; px += std::abs(int((g >> s) & 255) - int((c >> s) & 255)); }
        }
        double e = 0; for (int k = 0; k < 3; k++) e += std::fabs(sg[k] - sc[k]) / (B * B);
        e /= 3; m.block += e; m.maxBlock = std::max(m.maxBlock, e); if (e > 4) over++; nb++;
    }
    m.block /= nb; m.over4 = 100.0 * over / nb; m.pixel = px / (double(nb) * B * B * 3);
    return m;
}

/** The names of the cases in a directory (every X with an X.scene.f32). */
static std::vector<std::string> listCases(const std::string& dir) {
    std::vector<std::string> names;
    if (DIR* d = opendir(dir.c_str())) { while (dirent* e = readdir(d)) { std::string n = e->d_name; auto p = n.find(".scene.f32"); if (p != std::string::npos) names.push_back(n.substr(0, p)); } closedir(d); }
    std::sort(names.begin(), names.end());
    return names;
}

static void savePpm(const std::string& path, const std::vector<uint32_t>& px, int W, int H) {
    std::ofstream o(path, std::ios::binary); o << "P6\n" << W << " " << H << "\n255\n";
    for (uint32_t c : px) { char b[3] = {char(c >> 16), char(c >> 8), char(c)}; o.write(b, 3); }
}
