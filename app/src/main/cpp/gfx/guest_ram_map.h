#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace iosvm {

enum class PixelFormat : int { kBgra8888 = 0, kRgba8888 = 1 };

struct FbDescriptor {
  uint64_t gpa = 0;
  uint32_t width = 0, height = 0, strideBytes = 0;
  PixelFormat format = PixelFormat::kBgra8888;
  bool valid() const { return width && height && strideBytes >= width * 4u && (strideBytes & 3u) == 0; }
  size_t byteSize() const { return static_cast<size_t>(strideBytes) * height; }
};

// Read-only shared mapping of QEMU's guest RAM (memfd/ASharedMemory or a regular file).
class GuestRamMap {
 public:
  GuestRamMap() = default;
  ~GuestRamMap();
  GuestRamMap(const GuestRamMap&) = delete;
  GuestRamMap& operator=(const GuestRamMap&) = delete;

  // expectedSize == 0: use the file's size.
  bool open(const std::string& path, uint64_t ramBaseGpa, uint64_t expectedSize);
  // Borrows fd (caller keeps ownership). size == 0: use the fd's size.
  bool openFd(int fd, uint64_t ramBaseGpa, uint64_t size);
  // Pointer to guest-physical [gpa, gpa+len), or nullptr if out of range.
  const uint8_t* ptr(uint64_t gpa, size_t len) const;
  uint64_t base() const { return base_; }
  uint64_t size() const { return size_; }

 private:
  void* map_ = nullptr;
  size_t size_ = 0;
  uint64_t base_ = 0;
};

}  // namespace iosvm
