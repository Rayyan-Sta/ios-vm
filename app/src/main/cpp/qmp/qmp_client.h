#pragma once
#include <atomic>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

namespace iosvm {

enum class TouchPhase : int { kDown = 0, kMove = 1, kUp = 2, kCancel = 3 };
// kAbsPointer: single-touch via abs axes + left button. Works on any QEMU input stack (bring-up).
// kQemuMtt:    multi-touch via input-send-event "mtt". Needs a multitouch consumer in the fork.
enum class TouchMode : int { kAbsPointer = 0, kQemuMtt = 1 };

class QmpClient {
 public:
  using EventCb = std::function<void(const std::string& jsonLine)>;

  ~QmpClient() { disconnect(); }
  bool connect(const std::string& unixPath, int timeoutMs);   // retries until QEMU is listening
  void disconnect();
  bool connected() const { return connected_.load(); }
  void setEventCallback(EventCb cb);

  // Synchronous command. argsJson is a JSON object string or empty.
  // reply receives the raw {"return":..}/{"error":..} line.
  bool execute(const std::string& cmd, const std::string& argsJson, std::string* reply, int timeoutMs);

  // Fire-and-forget input
  void setTouchMode(TouchMode m);
  bool touch(TouchPhase ph, int pointerId, float nx, float ny);
  void cancelAllTouches();
  bool key(const std::string& qcode, bool down);

 private:
  static constexpr int kMaxSlots = 10;
  struct Waiter { std::mutex m; std::condition_variable cv; bool done = false; std::string line; };
  struct Slot { bool active = false; int tracking = 0; float nx = 0, ny = 0; };

  bool sendLine(const std::string& s);
  bool sendEvents(const std::string& eventsJson);
  bool readLine(std::string* out, int timeoutMs);   // timeoutMs < 0: block until data/EOF
  void readerLoop();
  void dispatch(const std::string& line);
  bool touchLocked(TouchPhase ph, int slot, float nx, float ny);

  int fd_ = -1;
  std::atomic<bool> connected_{false};
  std::thread reader_;
  std::string rx_;
  std::mutex sendMu_;

  std::mutex waitMu_;
  std::unordered_map<int, std::shared_ptr<Waiter>> waiters_;
  std::atomic<int> nextId_{1};

  std::mutex cbMu_;
  EventCb eventCb_;

  std::mutex touchMu_;
  TouchMode mode_ = TouchMode::kAbsPointer;
  Slot slots_[kMaxSlots];
  int primary_ = -1;
  int nextTracking_ = 1;
};

}  // namespace iosvm
