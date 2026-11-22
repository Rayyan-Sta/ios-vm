#include "gfx/guest_ram_map.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#include "common/log.h"

namespace iosvm {

GuestRamMap::~GuestRamMap() {
  if (map_) munmap(map_, size_);
}

bool GuestRamMap::open(const std::string& path, uint64_t ramBaseGpa, uint64_t expectedSize) {
  int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) { LOGE("ram open(%s): %s", path.c_str(), strerror(errno)); return false; }
  bool ok = openFd(fd, ramBaseGpa, expectedSize);
  ::close(fd);
  return ok;
}

bool GuestRamMap::openFd(int fd, uint64_t ramBaseGpa, uint64_t expectedSize) {
  struct stat st{};
  if (fstat(fd, &st) != 0) { LOGE("ram fstat: %s", strerror(errno)); return false; }
  const uint64_t fsz = static_cast<uint64_t>(st.st_size);
  if (expectedSize && fsz < expectedSize) {
    LOGE("ram backing %llu < expected %llu", (unsigned long long)fsz, (unsigned long long)expectedSize);
    return false;
  }
  const size_t len = static_cast<size_t>(expectedSize ? expectedSize : fsz);
  void* m = mmap(nullptr, len, PROT_READ, MAP_SHARED | MAP_NORESERVE, fd, 0);
  if (m == MAP_FAILED) { LOGE("ram mmap(%zu): %s", len, strerror(errno)); return false; }
  map_ = m; size_ = len; base_ = ramBaseGpa;
  LOGI("guest RAM mapped: %zu MiB @ gpa 0x%llx", len >> 20, (unsigned long long)ramBaseGpa);
  return true;
}

const uint8_t* GuestRamMap::ptr(uint64_t gpa, size_t len) const {
  if (!map_ || gpa < base_) return nullptr;
  const uint64_t off = gpa - base_;
  if (off > size_ || len > size_ - off) return nullptr;
  return static_cast<const uint8_t*>(map_) + off;
}

}  // namespace iosvm
