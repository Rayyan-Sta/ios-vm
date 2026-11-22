#include <android/native_window_jni.h>
#include <android/sharedmem.h>
#include <jni.h>
#include <unistd.h>

#include <cstdio>
#include <memory>
#include <string>

#include "common/log.h"
#include "cpu/cpu_features.h"
#include "gfx/gl_renderer.h"
#include "gfx/guest_ram_map.h"
#include "qmp/qmp_client.h"

using namespace iosvm;

namespace {

struct Engine {
  std::shared_ptr<GuestRamMap> ram;
  int ramFd = -1;                 // anonymous shared memory handed to QEMU via /proc/<pid>/fd/<fd>
  GlRenderer renderer;
  QmpClient qmp;
};
Engine& eng() { static Engine* e = new Engine(); return *e; }

struct JStr {
  JNIEnv* env; jstring s; const char* c;
  JStr(JNIEnv* e, jstring str) : env(e), s(str), c(str ? e->GetStringUTFChars(str, nullptr) : nullptr) {}
  ~JStr() { if (c) env->ReleaseStringUTFChars(s, c); }
  std::string str() const { return c ? std::string(c) : std::string(); }
};

// ---- Phase 1 -------------------------------------------------------------
jstring nDetectCpu(JNIEnv* env, jobject, jboolean engineAvailable) {
  auto f = detectCpuFeatures();
  return env->NewStringUTF(toJson(f, planExecution(f, engineAvailable)).c_str());
}

// ---- Phase 2: guest RAM + renderer ---------------------------------------
// Creates the shared-memory backing for guest DRAM (memfd on Android 10+: RAM-only, never
// written back to flash). Returns the fd (kept open by this process) or -1.
jint nRamCreate(JNIEnv*, jobject, jlong size) {
  Engine& e = eng();
  if (e.ramFd >= 0) { ::close(e.ramFd); e.ramFd = -1; }
  e.ramFd = ASharedMemory_create("iosvm-dram", static_cast<size_t>(size));
  if (e.ramFd < 0) LOGE("ASharedMemory_create(%lld) failed", (long long)size);
  return e.ramFd;
}
jboolean nRamAttach(JNIEnv*, jobject, jint fd, jlong baseGpa, jlong size) {
  auto ram = std::make_shared<GuestRamMap>();
  if (!ram->openFd(fd, static_cast<uint64_t>(baseGpa), static_cast<uint64_t>(size))) return JNI_FALSE;
  eng().ram = ram;
  eng().renderer.setRam(ram);
  return JNI_TRUE;
}
jboolean nRamOpen(JNIEnv* env, jobject, jstring path, jlong baseGpa, jlong size) {
  JStr p(env, path);
  auto ram = std::make_shared<GuestRamMap>();
  if (!ram->open(p.str(), static_cast<uint64_t>(baseGpa), static_cast<uint64_t>(size))) return JNI_FALSE;
  eng().ram = ram;
  eng().renderer.setRam(ram);
  return JNI_TRUE;
}
void nRamClose(JNIEnv*, jobject) {
  Engine& e = eng();
  e.renderer.setRam(nullptr);
  e.ram.reset();
  if (e.ramFd >= 0) { ::close(e.ramFd); e.ramFd = -1; }
}

void nSetFramebuffer(JNIEnv*, jobject, jlong gpa, jint w, jint h, jint stride, jint fmt) {
  FbDescriptor d;
  d.gpa = static_cast<uint64_t>(gpa);
  d.width = static_cast<uint32_t>(w);
  d.height = static_cast<uint32_t>(h);
  d.strideBytes = static_cast<uint32_t>(stride);
  d.format = fmt == 1 ? PixelFormat::kRgba8888 : PixelFormat::kBgra8888;
  eng().renderer.setFramebuffer(d);
}
void nSetScaleMode(JNIEnv*, jobject, jint m) { eng().renderer.setScaleMode(static_cast<ScaleMode>(m)); }

void nSetSurface(JNIEnv* env, jobject, jobject surface) {
  ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;  // +1 ref, renderer owns it
  eng().renderer.setSurface(w);  // nullptr blocks until the render thread has released the old window
}
void nRendererStart(JNIEnv*, jobject) { eng().renderer.start(); }
void nRendererStop(JNIEnv*, jobject) { eng().renderer.stop(); }
jstring nGetStats(JNIEnv* env, jobject) {
  RenderStats s = eng().renderer.stats();
  char buf[160];
  snprintf(buf, sizeof buf, "{\"fps\":%.1f,\"upload_ms\":%.2f,\"frames\":%llu}", s.fps, s.uploadMs,
           static_cast<unsigned long long>(s.frames));
  return env->NewStringUTF(buf);
}

// ---- Phase 2: QMP --------------------------------------------------------
jboolean nQmpConnect(JNIEnv* env, jobject, jstring path, jint timeoutMs) {
  JStr p(env, path);
  return eng().qmp.connect(p.str(), timeoutMs) ? JNI_TRUE : JNI_FALSE;
}
void nQmpDisconnect(JNIEnv*, jobject) { eng().qmp.disconnect(); }
jboolean nQmpConnected(JNIEnv*, jobject) { return eng().qmp.connected() ? JNI_TRUE : JNI_FALSE; }
jstring nQmpExecute(JNIEnv* env, jobject, jstring cmd, jstring argsJson, jint timeoutMs) {
  JStr c(env, cmd), a(env, argsJson);
  std::string reply;
  if (!eng().qmp.execute(c.str(), a.str(), &reply, timeoutMs)) return nullptr;
  return env->NewStringUTF(reply.c_str());
}
void nSetTouchMode(JNIEnv*, jobject, jint m) { eng().qmp.setTouchMode(static_cast<TouchMode>(m)); }

jboolean nTouch(JNIEnv*, jobject, jint phase, jint pointerId, jfloat x, jfloat y, jfloat vw, jfloat vh) {
  Engine& e = eng();
  float nx = 0, ny = 0;
  bool inside = e.renderer.viewToGuest(x, y, vw, vh, /*clamp=*/true, &nx, &ny);
  auto ph = static_cast<TouchPhase>(phase);
  if (ph == TouchPhase::kDown && !inside) return JNI_FALSE;  // letterbox bars don't start touches
  return e.qmp.touch(ph, pointerId, nx, ny) ? JNI_TRUE : JNI_FALSE;
}
// Already-normalized guest coordinates (synthetic gestures: home/back swipes, slider drags).
jboolean nTouchGuest(JNIEnv*, jobject, jint phase, jint pointerId, jfloat nx, jfloat ny) {
  return eng().qmp.touch(static_cast<TouchPhase>(phase), pointerId, nx, ny) ? JNI_TRUE : JNI_FALSE;
}
void nTouchCancelAll(JNIEnv*, jobject) { eng().qmp.cancelAllTouches(); }

jboolean nKey(JNIEnv* env, jobject, jstring qcode, jboolean down) {
  JStr q(env, qcode);
  return eng().qmp.key(q.str(), down) ? JNI_TRUE : JNI_FALSE;
}

const JNINativeMethod kMethods[] = {
    {"nativeDetectCpu", "(Z)Ljava/lang/String;", (void*)nDetectCpu},
    {"nativeRamCreate", "(J)I", (void*)nRamCreate},
    {"nativeRamAttach", "(IJJ)Z", (void*)nRamAttach},
    {"nativeRamOpen", "(Ljava/lang/String;JJ)Z", (void*)nRamOpen},
    {"nativeRamClose", "()V", (void*)nRamClose},
    {"nativeSetFramebuffer", "(JIIII)V", (void*)nSetFramebuffer},
    {"nativeSetScaleMode", "(I)V", (void*)nSetScaleMode},
    {"nativeSetSurface", "(Landroid/view/Surface;)V", (void*)nSetSurface},
    {"nativeRendererStart", "()V", (void*)nRendererStart},
    {"nativeRendererStop", "()V", (void*)nRendererStop},
    {"nativeGetStats", "()Ljava/lang/String;", (void*)nGetStats},
    {"nativeQmpConnect", "(Ljava/lang/String;I)Z", (void*)nQmpConnect},
    {"nativeQmpDisconnect", "()V", (void*)nQmpDisconnect},
    {"nativeQmpConnected", "()Z", (void*)nQmpConnected},
    {"nativeQmpExecute", "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;", (void*)nQmpExecute},
    {"nativeSetTouchMode", "(I)V", (void*)nSetTouchMode},
    {"nativeTouch", "(IIFFFF)Z", (void*)nTouch},
    {"nativeTouchGuest", "(IIFF)Z", (void*)nTouchGuest},
    {"nativeTouchCancelAll", "()V", (void*)nTouchCancelAll},
    {"nativeKey", "(Ljava/lang/String;Z)Z", (void*)nKey},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass c = env->FindClass("com/iosvm/emu/NativeBridge");
  if (!c || env->RegisterNatives(c, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
    LOGE("RegisterNatives failed");
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
