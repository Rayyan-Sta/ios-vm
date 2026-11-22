#pragma once
#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <thread>

#include "gfx/guest_ram_map.h"

namespace iosvm {

enum class ScaleMode : int { kFit = 0, kFill = 1, kStretch = 2 };

// Where the guest image sits inside the surface, as fractions (origin top-left).
struct ViewRect { float fx = 0, fy = 0, fw = 1, fh = 1; };

struct RenderStats { float fps = 0; float uploadMs = 0; uint64_t frames = 0; };

class GlRenderer {
 public:
  GlRenderer() = default;
  ~GlRenderer() { stop(); }

  void start();
  void stop();
  void setRam(std::shared_ptr<GuestRamMap> ram);
  void setFramebuffer(const FbDescriptor& d);   // live: resolution changes realloc the texture
  void setScaleMode(ScaleMode m);
  // Takes ownership of one ANativeWindow reference. nullptr detaches and BLOCKS until the
  // render thread has released the old window (required by surfaceDestroyed()).
  void setSurface(ANativeWindow* w);

  // View-pixel -> normalized guest coords [0,1]. Returns true if the point was inside the
  // guest image; with clamp=true the outputs are clamped either way.
  bool viewToGuest(float x, float y, float viewW, float viewH, bool clamp, float* nx, float* ny) const;
  RenderStats stats() const;

 private:
  void threadMain();
  bool initEgl();
  void teardownEgl();
  bool initGl();
  void drawFrame(EGLSurface surf);

  mutable std::mutex mu_;
  std::condition_variable cv_, ackCv_;
  std::thread thread_;
  bool running_ = false, quit_ = false;
  ANativeWindow* pendingWin_ = nullptr;
  uint64_t winGenReq_ = 0, winGenApplied_ = 0;
  std::shared_ptr<GuestRamMap> ram_;
  FbDescriptor fb_;
  ScaleMode mode_ = ScaleMode::kFit;

  mutable std::mutex rectMu_;
  ViewRect rect_;
  mutable std::mutex statMu_;
  RenderStats stats_;

  // render-thread-only state
  EGLDisplay dpy_ = EGL_NO_DISPLAY;
  EGLContext ctx_ = EGL_NO_CONTEXT;
  EGLConfig cfg_ = nullptr;
  GLuint prog_ = 0, tex_ = 0;
  GLint uTex_ = -1, uBgra_ = -1;
  uint32_t texW_ = 0, texH_ = 0;
};

}  // namespace iosvm
