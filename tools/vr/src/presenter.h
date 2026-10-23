#pragma once
#include <cstdint>
<<<<<<< HEAD
=======
#include "xr_frame.h"
>>>>>>> upstream/main

namespace droiddeck {

struct EyeFrame {
    uint64_t texture[2];
    float bounds[2][4];
<<<<<<< HEAD
=======
    DroidDeckXrFrameHeader render;
    uint32_t layers = 0;
>>>>>>> upstream/main
};

class Presenter {
public:
<<<<<<< HEAD
    static void AddTextures(const uint64_t handles[3], const int fds[3], uint32_t vkFormat, uint32_t width, uint32_t height);
=======
    static void AddTextures(const uint64_t handles[3], const int fds[3], uint32_t vkFormat, uint32_t width, uint32_t height,
                            uint32_t producerPid);
>>>>>>> upstream/main
    static void RemoveTexture(uint64_t handle);
    static void Submit(const EyeFrame &frame);
    static void Shutdown();
};

}
