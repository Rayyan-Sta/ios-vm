#include <unistd.h>

#include <cstdio>
#include <cstring>

#include "qmp/qmp_client.h"
using namespace iosvm;

static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL: %s\n", #c); ++fails; } } while (0)

int main(int argc, char** argv) {
  QmpClient q;
  CHECK(q.connect(argv[1], 3000));
  std::string r;
  CHECK(q.execute("x-ios-vm-info", "", &r, 2000));
  CHECK(r.find("dram-base") != std::string::npos);
  CHECK(!q.execute("x\"y", "", &r, 100));                          // token sanitising
  CHECK(q.touch(TouchPhase::kDown, 0, 0.5f, 0.25f));
  CHECK(q.touch(TouchPhase::kMove, 0, 0.6f, 0.3f));
  CHECK(!q.touch(TouchPhase::kDown, 1, 0.1f, 0.1f));              // abs mode is single-touch
  CHECK(q.touch(TouchPhase::kUp, 0, 0.6f, 0.3f));
  CHECK(q.key("audiovolup", true));
  CHECK(q.key("audiovolup", false));
  q.setTouchMode(TouchMode::kQemuMtt);
  CHECK(q.touch(TouchPhase::kDown, 3, 1, 1));
  CHECK(q.touch(TouchPhase::kUp, 3, 1, 1));
  usleep(300000);
  q.disconnect();
  printf(fails ? "qmp_test: %d failure(s)\n" : "qmp_test: ok\n", fails);
  return fails ? 1 : 0;
}
