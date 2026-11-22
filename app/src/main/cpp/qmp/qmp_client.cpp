#include "qmp/qmp_client.h"

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>

#include "common/log.h"

namespace iosvm {
namespace {

constexpr int kAbsMax = 0x7fff;  // QEMU INPUT_EVENT_ABS_MAX
int toAbs(float n) { return static_cast<int>(std::lround(std::clamp(n, 0.0f, 1.0f) * kAbsMax)); }

bool safeToken(const std::string& s) {   // command / qcode names only: no JSON injection
  if (s.empty() || s.size() > 64) return false;
  return std::all_of(s.begin(), s.end(), [](unsigned char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
  });
}

std::string absEv(const char* axis, int v) {
  return std::string("{\"type\":\"abs\",\"data\":{\"axis\":\"") + axis + "\",\"value\":" + std::to_string(v) + "}}";
}
std::string btnEv(bool down) {
  return std::string("{\"type\":\"btn\",\"data\":{\"down\":") + (down ? "true" : "false") + ",\"button\":\"left\"}}";
}
// ASSUMPTION: wire shape of QEMU's InputMultiTouchEvent {type,slot,tracking-id,axis,value}.
// Validate against the fork's multitouch input handler; adjust only here.
std::string mttEv(const char* type, int slot, int tracking, const char* axis, int v) {
  return std::string("{\"type\":\"mtt\",\"data\":{\"type\":\"") + type + "\",\"slot\":" + std::to_string(slot) +
         ",\"tracking-id\":" + std::to_string(tracking) + ",\"axis\":\"" + axis + "\",\"value\":" +
         std::to_string(v) + "}}";
}

}  // namespace

// ---- connection -------------------------------------------------------------------------

bool QmpClient::connect(const std::string& path, int timeoutMs) {
  disconnect();
  sockaddr_un addr{};
  if (path.size() >= sizeof(addr.sun_path)) { LOGE("QMP path too long (%zu)", path.size()); return false; }
  addr.sun_family = AF_UNIX;
  memcpy(addr.sun_path, path.c_str(), path.size());

  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
  int fd = -1;
  for (;;) {
    fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd >= 0 && ::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof addr) == 0) break;
    if (fd >= 0) ::close(fd);
    fd = -1;
    if (std::chrono::steady_clock::now() >= deadline) { LOGE("QMP connect timeout: %s", path.c_str()); return false; }
    usleep(100 * 1000);
  }
  fd_ = fd; rx_.clear();

  std::string line;
  if (!readLine(&line, 5000) || line.find("\"QMP\"") == std::string::npos) { LOGE("bad QMP greeting"); goto fail; }
  if (!sendLine("{\"execute\":\"qmp_capabilities\"}")) goto fail;
  for (;;) {   // skip any events until the capabilities reply
    if (!readLine(&line, 5000)) { LOGE("no qmp_capabilities reply"); goto fail; }
    if (line.rfind("{\"return\"", 0) == 0) break;
    if (line.rfind("{\"error\"", 0) == 0) { LOGE("qmp_capabilities: %s", line.c_str()); goto fail; }
  }
  connected_ = true;
  reader_ = std::thread(&QmpClient::readerLoop, this);
  LOGI("QMP connected: %s", path.c_str());
  return true;

fail:
  ::close(fd_); fd_ = -1;
  return false;
}

void QmpClient::disconnect() {
  if (fd_ >= 0) ::shutdown(fd_, SHUT_RDWR);          // unblocks the reader's poll/recv
  if (reader_.joinable()) reader_.join();
  if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
  connected_ = false;
  std::lock_guard<std::mutex> lk(touchMu_);
  for (auto& s : slots_) s = Slot{};
  primary_ = -1;
}

void QmpClient::setEventCallback(EventCb cb) { std::lock_guard<std::mutex> lk(cbMu_); eventCb_ = std::move(cb); }

bool QmpClient::sendLine(const std::string& s) {
  std::lock_guard<std::mutex> lk(sendMu_);
  if (fd_ < 0) return false;
  std::string m = s; m.push_back('\n');
  size_t off = 0;
  while (off < m.size()) {
    ssize_t n = ::send(fd_, m.data() + off, m.size() - off, MSG_NOSIGNAL);
    if (n < 0) { if (errno == EINTR) continue; return false; }
    off += static_cast<size_t>(n);
  }
  return true;
}

bool QmpClient::readLine(std::string* out, int timeoutMs) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(timeoutMs, 0));
  for (;;) {
    size_t nl = rx_.find('\n');
    if (nl != std::string::npos) {
      *out = rx_.substr(0, nl);
      if (!out->empty() && out->back() == '\r') out->pop_back();
      rx_.erase(0, nl + 1);
      return true;
    }
    int wait = -1;
    if (timeoutMs >= 0) {
      auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
      if (left <= 0) return false;
      wait = static_cast<int>(left);
    }
    pollfd p{fd_, POLLIN, 0};
    int r = ::poll(&p, 1, wait);
    if (r < 0 && errno == EINTR) continue;
    if (r <= 0) return false;
    char buf[4096];
    ssize_t n = ::recv(fd_, buf, sizeof buf, 0);
    if (n <= 0) return false;                         // EOF or error
    rx_.append(buf, static_cast<size_t>(n));
  }
}

void QmpClient::readerLoop() {
  std::string line;
  while (readLine(&line, -1)) dispatch(line);
  connected_ = false;
  LOGW("QMP reader exited");
  std::lock_guard<std::mutex> lk(waitMu_);              // wake any sync callers
  for (auto& kv : waiters_) {
    std::lock_guard<std::mutex> wl(kv.second->m);
    kv.second->line = "{\"error\":{\"class\":\"Disconnected\",\"desc\":\"QMP closed\"}}";
    kv.second->done = true;
    kv.second->cv.notify_all();
  }
}

void QmpClient::dispatch(const std::string& line) {
  const bool isReply = line.rfind("{\"return\"", 0) == 0 || line.rfind("{\"error\"", 0) == 0;
  if (!isReply) {                                       // asynchronous event
    EventCb cb; { std::lock_guard<std::mutex> lk(cbMu_); cb = eventCb_; }
    if (cb) cb(line); else LOGI("QMP event: %s", line.c_str());
    return;
  }
  size_t p = line.rfind("\"id\":");                     // QEMU appends the echoed id last
  if (p == std::string::npos) return;                   // fire-and-forget input reply: drop
  const int id = atoi(line.c_str() + p + 5);
  std::shared_ptr<Waiter> w;
  { std::lock_guard<std::mutex> lk(waitMu_); auto it = waiters_.find(id); if (it != waiters_.end()) w = it->second; }
  if (!w) return;
  std::lock_guard<std::mutex> wl(w->m);
  w->line = line; w->done = true; w->cv.notify_all();
}

bool QmpClient::execute(const std::string& cmd, const std::string& argsJson, std::string* reply, int timeoutMs) {
  if (!connected() || !safeToken(cmd)) return false;
  const int id = nextId_++;
  auto w = std::make_shared<Waiter>();
  { std::lock_guard<std::mutex> lk(waitMu_); waiters_[id] = w; }
  std::string msg = "{\"execute\":\"" + cmd + "\"";
  if (!argsJson.empty()) msg += ",\"arguments\":" + argsJson;
  msg += ",\"id\":" + std::to_string(id) + "}";
  bool ok = sendLine(msg);
  if (ok) {
    std::unique_lock<std::mutex> lk(w->m);
    ok = w->cv.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return w->done; });
  }
  { std::lock_guard<std::mutex> lk(waitMu_); waiters_.erase(id); }
  if (ok && reply) { std::lock_guard<std::mutex> lk(w->m); *reply = w->line; }
  return ok;
}

// ---- input ------------------------------------------------------------------------------

bool QmpClient::sendEvents(const std::string& events) {
  return sendLine("{\"execute\":\"input-send-event\",\"arguments\":{\"events\":[" + events + "]}}");
}

void QmpClient::setTouchMode(TouchMode m) {
  std::lock_guard<std::mutex> lk(touchMu_);
  mode_ = m;
}

bool QmpClient::touchLocked(TouchPhase ph, int slot, float nx, float ny) {
  Slot& s = slots_[slot];
  const int ax = toAbs(nx), ay = toAbs(ny);
  std::string ev;

  if (mode_ == TouchMode::kAbsPointer) {
    switch (ph) {
      case TouchPhase::kDown:
        if (primary_ != -1) return false;               // single-touch: only the first finger
        primary_ = slot; s.active = true;
        ev = absEv("x", ax) + "," + absEv("y", ay) + "," + btnEv(true);
        break;
      case TouchPhase::kMove:
        if (primary_ != slot) return false;
        ev = absEv("x", ax) + "," + absEv("y", ay);
        break;
      case TouchPhase::kUp:
      case TouchPhase::kCancel:
        if (primary_ != slot) return false;
        primary_ = -1; s.active = false;
        ev = absEv("x", ax) + "," + absEv("y", ay) + "," + btnEv(false);
        break;
    }
  } else {
    const char* type = nullptr;
    switch (ph) {
      case TouchPhase::kDown:   s.active = true; s.tracking = nextTracking_++; type = "begin"; break;
      case TouchPhase::kMove:   if (!s.active) return false; type = "update"; break;
      case TouchPhase::kUp:     if (!s.active) return false; type = "end"; s.active = false; break;
      case TouchPhase::kCancel: if (!s.active) return false; type = "cancel"; s.active = false; break;
    }
    ev = mttEv(type, slot, s.tracking, "x", ax) + "," + mttEv(type, slot, s.tracking, "y", ay);
  }
  s.nx = nx; s.ny = ny;
  return sendEvents(ev);
}

bool QmpClient::touch(TouchPhase ph, int pointerId, float nx, float ny) {
  if (!connected()) return false;
  const int slot = ((pointerId % kMaxSlots) + kMaxSlots) % kMaxSlots;
  std::lock_guard<std::mutex> lk(touchMu_);
  return touchLocked(ph, slot, nx, ny);
}

void QmpClient::cancelAllTouches() {   // call on focus loss / pause so iOS never sees stuck fingers
  if (!connected()) return;
  std::lock_guard<std::mutex> lk(touchMu_);
  for (int i = 0; i < kMaxSlots; ++i)
    if (slots_[i].active) touchLocked(TouchPhase::kCancel, i, slots_[i].nx, slots_[i].ny);
}

bool QmpClient::key(const std::string& qcode, bool down) {
  if (!connected() || !safeToken(qcode)) return false;
  return sendEvents(std::string("{\"type\":\"key\",\"data\":{\"down\":") + (down ? "true" : "false") +
                    ",\"key\":{\"type\":\"qcode\",\"data\":\"" + qcode + "\"}}}");
}

}  // namespace iosvm
