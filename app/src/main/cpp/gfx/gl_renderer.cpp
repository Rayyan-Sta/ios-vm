#include "gfx/gl_renderer.h"

#include <EGL/eglext.h>

#include <algorithm>
#include <chrono>

#include "common/log.h"

namespace iosvm {
namespace {

using Clock = std::chrono::steady_clock;

// Attribute-less full-screen strip. Guest row 0 (top) -> screen top.
constexpr const char* kVs = R"(#version 300 es
out vec2 vUv;
void main() {
  vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
  vUv = vec2(p.x, 1.0 - p.y);
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// GLES3 has no core GL_BGRA upload: upload bytes as RGBA, swizzle here.
constexpr const char* kFs = R"(#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform int uBgra;
in vec2 vUv;
out vec4 oColor;
void main() {
  vec3 c = texture(uTex, vUv).rgb;
  oColor = vec4(uBgra != 0 ? c.bgr : c, 1.0);
})";

GLuint compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = 0;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[512]; glGetShaderInfoLog(s, sizeof log, nullptr, log);
    LOGE("shader compile: %s", log);
    glDeleteShader(s);
    return 0;
  }
  return s;
}

ViewRect computeRect(ScaleMode m, int sw, int sh, int gw, int gh) {
  float dw = static_cast<float>(sw), dh = static_cast<float>(sh);
  if (m != ScaleMode::kStretch) {
    const float sx = static_cast<float>(sw) / gw, sy = static_cast<float>(sh) / gh;
    const float s = (m == ScaleMode::kFit) ? std::min(sx, sy) : std::max(sx, sy);
    dw = gw * s; dh = gh * s;
  }
  ViewRect r;
  r.fw = dw / sw; r.fh = dh / sh;
  r.fx = (1.0f - r.fw) * 0.5f; r.fy = (1.0f - r.fh) * 0.5f;
  return r;
}

}  // namespace

// ---- public API -------------------------------------------------------------------------

void GlRenderer::start() {
  std::lock_guard<std::mutex> lk(mu_);
  if (running_) return;
  quit_ = false; running_ = true;
  thread_ = std::thread(&GlRenderer::threadMain, this);
}

void GlRenderer::stop() {
  {
    std::lock_guard<std::mutex> lk(mu_);
    if (!running_) return;
    quit_ = true;
  }
  cv_.notify_all();
  if (thread_.joinable()) thread_.join();
  std::lock_guard<std::mutex> lk(mu_);
  running_ = false;
  if (pendingWin_) { ANativeWindow_release(pendingWin_); pendingWin_ = nullptr; }
  ackCv_.notify_all();
}

void GlRenderer::setRam(std::shared_ptr<GuestRamMap> ram) {
  std::lock_guard<std::mutex> lk(mu_);
  ram_ = std::move(ram);
}
void GlRenderer::setFramebuffer(const FbDescriptor& d) {
  std::lock_guard<std::mutex> lk(mu_);
  fb_ = d;
}
void GlRenderer::setScaleMode(ScaleMode m) {
  std::lock_guard<std::mutex> lk(mu_);
  mode_ = m;
}

void GlRenderer::setSurface(ANativeWindow* w) {
  std::unique_lock<std::mutex> lk(mu_);
  if (pendingWin_) ANativeWindow_release(pendingWin_);
  pendingWin_ = w;
  const uint64_t gen = ++winGenReq_;
  cv_.notify_all();
  if (!w && running_) ackCv_.wait(lk, [&] { return winGenApplied_ >= gen || !running_; });
}

bool GlRenderer::viewToGuest(float x, float y, float vw, float vh, bool clamp, float* nx, float* ny) const {
  ViewRect r;
  { std::lock_guard<std::mutex> lk(rectMu_); r = rect_; }
  if (vw <= 0 || vh <= 0 || r.fw <= 0 || r.fh <= 0) { *nx = *ny = 0; return false; }
  float u = (x / vw - r.fx) / r.fw, v = (y / vh - r.fy) / r.fh;
  const bool inside = u >= 0 && u <= 1 && v >= 0 && v <= 1;
  if (clamp) { u = std::clamp(u, 0.0f, 1.0f); v = std::clamp(v, 0.0f, 1.0f); }
  *nx = u; *ny = v;
  return inside;
}

RenderStats GlRenderer::stats() const {
  std::lock_guard<std::mutex> lk(statMu_);
  return stats_;
}

// ---- render thread ----------------------------------------------------------------------

bool GlRenderer::initEgl() {
  dpy_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (dpy_ == EGL_NO_DISPLAY || !eglInitialize(dpy_, nullptr, nullptr)) { LOGE("eglInitialize failed"); return false; }
  const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                            EGL_DEPTH_SIZE, 0, EGL_NONE};
  EGLint n = 0;
  if (!eglChooseConfig(dpy_, cfgAttr, &cfg_, 1, &n) || n < 1) { LOGE("no ES3 EGL config"); return false; }
  const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  ctx_ = eglCreateContext(dpy_, cfg_, EGL_NO_CONTEXT, ctxAttr);
  if (ctx_ == EGL_NO_CONTEXT) { LOGE("eglCreateContext: 0x%x", eglGetError()); return false; }
  return true;
}

void GlRenderer::teardownEgl() {
  if (dpy_ == EGL_NO_DISPLAY) return;
  eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (ctx_ != EGL_NO_CONTEXT) eglDestroyContext(dpy_, ctx_);  // frees program/texture with it
  eglTerminate(dpy_);
  dpy_ = EGL_NO_DISPLAY; ctx_ = EGL_NO_CONTEXT; prog_ = tex_ = 0; texW_ = texH_ = 0;
}

bool GlRenderer::initGl() {
  GLuint vs = compile(GL_VERTEX_SHADER, kVs), fs = compile(GL_FRAGMENT_SHADER, kFs);
  if (!vs || !fs) return false;
  prog_ = glCreateProgram();
  glAttachShader(prog_, vs); glAttachShader(prog_, fs);
  glLinkProgram(prog_);
  glDeleteShader(vs); glDeleteShader(fs);
  GLint ok = 0; glGetProgramiv(prog_, GL_LINK_STATUS, &ok);
  if (!ok) { LOGE("program link failed"); return false; }
  uTex_ = glGetUniformLocation(prog_, "uTex");
  uBgra_ = glGetUniformLocation(prog_, "uBgra");
  glGenTextures(1, &tex_);
  glBindTexture(GL_TEXTURE_2D, tex_);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glDisable(GL_DEPTH_TEST); glDisable(GL_BLEND);
  LOGI("GL: %s | %s", reinterpret_cast<const char*>(glGetString(GL_RENDERER)),
       reinterpret_cast<const char*>(glGetString(GL_VERSION)));
  return true;
}

void GlRenderer::drawFrame(EGLSurface surf) {
  EGLint sw = 0, sh = 0;
  eglQuerySurface(dpy_, surf, EGL_WIDTH, &sw);
  eglQuerySurface(dpy_, surf, EGL_HEIGHT, &sh);

  FbDescriptor fb; ScaleMode mode; std::shared_ptr<GuestRamMap> ram;
  { std::lock_guard<std::mutex> lk(mu_); fb = fb_; mode = mode_; ram = ram_; }

  glViewport(0, 0, sw, sh);
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  if (!ram || !fb.valid() || sw <= 0 || sh <= 0) return;

  const uint8_t* src = ram->ptr(fb.gpa, fb.byteSize());
  if (!src) return;

  glBindTexture(GL_TEXTURE_2D, tex_);
  if (fb.width != texW_ || fb.height != texH_) {   // dynamic resolution
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fb.width, fb.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    texW_ = fb.width; texH_ = fb.height;
  }
  const auto t0 = Clock::now();
  glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, static_cast<GLint>(fb.strideBytes / 4));
  // Uploads directly from the shared guest-RAM mapping: no intermediate CPU copy.
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, fb.width, fb.height, GL_RGBA, GL_UNSIGNED_BYTE, src);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
  const float upMs = std::chrono::duration<float, std::milli>(Clock::now() - t0).count();

  const ViewRect r = computeRect(mode, sw, sh, fb.width, fb.height);
  { std::lock_guard<std::mutex> lk(rectMu_); rect_ = r; }
  glViewport(static_cast<GLint>(r.fx * sw), static_cast<GLint>((1.0f - r.fy - r.fh) * sh),
             static_cast<GLsizei>(r.fw * sw), static_cast<GLsizei>(r.fh * sh));
  glUseProgram(prog_);
  glActiveTexture(GL_TEXTURE0);
  glUniform1i(uTex_, 0);
  glUniform1i(uBgra_, fb.format == PixelFormat::kBgra8888 ? 1 : 0);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

  static thread_local Clock::time_point winStart = Clock::now();
  static thread_local uint32_t winFrames = 0;
  ++winFrames;
  std::lock_guard<std::mutex> lk(statMu_);
  stats_.frames++; stats_.uploadMs = upMs;
  const float el = std::chrono::duration<float>(Clock::now() - winStart).count();
  if (el >= 1.0f) { stats_.fps = winFrames / el; winFrames = 0; winStart = Clock::now(); }
}

void GlRenderer::threadMain() {
  if (!initEgl()) { teardownEgl(); return; }
  EGLSurface surf = EGL_NO_SURFACE;
  ANativeWindow* win = nullptr;
  bool glReady = false;

  auto dropSurface = [&] {
    if (surf != EGL_NO_SURFACE) {
      eglMakeCurrent(dpy_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      eglDestroySurface(dpy_, surf);
      surf = EGL_NO_SURFACE;
    }
    if (win) { ANativeWindow_release(win); win = nullptr; }
  };

  for (;;) {
    bool swap = false; uint64_t gen = 0; ANativeWindow* next = nullptr;
    {
      std::unique_lock<std::mutex> lk(mu_);
      cv_.wait(lk, [&] { return quit_ || winGenReq_ != winGenApplied_ || surf != EGL_NO_SURFACE; });
      if (quit_) break;
      if (winGenReq_ != winGenApplied_) { swap = true; gen = winGenReq_; next = pendingWin_; pendingWin_ = nullptr; }
    }
    if (swap) {
      dropSurface();
      if (next) {
        win = next;
        surf = eglCreateWindowSurface(dpy_, cfg_, win, nullptr);
        if (surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy_, surf, surf, ctx_)) {
          LOGE("window surface/makeCurrent failed: 0x%x", eglGetError());
          dropSurface();
        } else {
          eglSwapInterval(dpy_, 1);                       // vsync-paced
          if (!glReady) glReady = initGl();
          if (!glReady) dropSurface();
        }
      }
      { std::lock_guard<std::mutex> lk(mu_); winGenApplied_ = gen; }
      ackCv_.notify_all();
    }
    if (surf == EGL_NO_SURFACE) continue;

    drawFrame(surf);
    if (!eglSwapBuffers(dpy_, surf)) {
      const EGLint e = eglGetError();
      if (e == EGL_BAD_SURFACE || e == EGL_BAD_NATIVE_WINDOW) dropSurface();
    }
  }
  dropSurface();
  teardownEgl();
}

}  // namespace iosvm
