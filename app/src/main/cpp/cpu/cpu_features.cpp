#include "cpu/cpu_features.h"

#include <sys/auxv.h>
#include <unistd.h>

#include <cstdio>

#include "common/log.h"

#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif

namespace iosvm {
namespace {
// arm64 uapi asm/hwcap.h, duplicated so we never depend on header vintage.
constexpr unsigned long HC_AES = 1UL << 3,  HC_SHA2 = 1UL << 6,  HC_CRC32 = 1UL << 7,
                        HC_ATOMICS = 1UL << 8, HC_FPHP = 1UL << 9, HC_ASIMDHP = 1UL << 10,
                        HC_ASIMDRDM = 1UL << 12, HC_JSCVT = 1UL << 13, HC_FCMA = 1UL << 14,
                        HC_LRCPC = 1UL << 15, HC_ASIMDDP = 1UL << 20, HC_ILRCPC = 1UL << 26,
                        HC_PACA = 1UL << 30, HC_PACG = 1UL << 31;
constexpr unsigned long HC2_BTI = 1UL << 17;
const char* b(bool v) { return v ? "true" : "false"; }
}  // namespace

std::string CpuFeatures::missing() const {
  std::string m;
  auto add = [&](bool ok, const char* n) { if (!ok) { if (!m.empty()) m += ","; m += n; } };
  add(lse, "lse"); add(rdm, "rdm"); add(dotprod, "dotprod"); add(fp16, "fp16"); add(rcpc, "rcpc");
  return m;
}

CpuFeatures detectCpuFeatures() {
  CpuFeatures f;
  f.hwcap = getauxval(AT_HWCAP);
  f.hwcap2 = getauxval(AT_HWCAP2);
  f.pageSize = sysconf(_SC_PAGESIZE);

  auto h = [&](unsigned long bit) { return (f.hwcap & bit) != 0; };
  f.lse = h(HC_ATOMICS);
  f.rdm = h(HC_ASIMDRDM);
  f.dotprod = h(HC_ASIMDDP);
  f.fp16 = h(HC_FPHP) && h(HC_ASIMDHP);
  f.rcpc = h(HC_LRCPC);
  f.rcpc2 = h(HC_ILRCPC);
  f.fcma = h(HC_FCMA);
  f.jscvt = h(HC_JSCVT);
  f.pauth = h(HC_PACA) && h(HC_PACG);
  f.bti = (f.hwcap2 & HC2_BTI) != 0;
  f.aes = h(HC_AES);
  f.sha2 = h(HC_SHA2);
  f.crc32 = h(HC_CRC32);

  LOGI("hwcap=0x%lx hwcap2=0x%lx page=%ld baseline=%d missing=[%s]", f.hwcap, f.hwcap2,
       f.pageSize, f.meetsIosBaseline(), f.missing().c_str());
  return f;
}

ExecPlan planExecution(const CpuFeatures& f, bool nativeEngineAvailable) {
  ExecPlan p;
  p.tcgCpu = "max";  // TCG implements LSE/RDM/DotProd/FP16/RCpc/PAuth regardless of host
  if (!nativeEngineAvailable) {
    p.mode = ExecMode::kTcg;
    p.reason = "native EL0 engine not enabled in this build";
  } else if (!f.meetsIosBaseline()) {
    p.mode = ExecMode::kTcg;
    p.reason = "host lacks: " + f.missing();
  } else {
    p.mode = ExecMode::kNativeEl0;
    p.reason = "host meets ARMv8.2+ iOS baseline";
  }
  return p;
}

std::string toJson(const CpuFeatures& f, const ExecPlan& p) {
  char buf[2048];
  snprintf(buf, sizeof buf,
      "{\"hwcap\":%lu,\"hwcap2\":%lu,\"page_size\":%ld,"
      "\"features\":{\"lse\":%s,\"rdm\":%s,\"dotprod\":%s,\"fp16\":%s,\"rcpc\":%s,\"rcpc2\":%s,"
      "\"fcma\":%s,\"jscvt\":%s,\"pauth\":%s,\"bti\":%s,\"aes\":%s,\"sha2\":%s,\"crc32\":%s},"
      "\"meets_ios_baseline\":%s,\"missing\":\"%s\","
      "\"plan\":{\"mode\":\"%s\",\"reason\":\"%s\",\"tcg_cpu\":\"%s\"}}",
      f.hwcap, f.hwcap2, f.pageSize, b(f.lse), b(f.rdm), b(f.dotprod), b(f.fp16), b(f.rcpc),
      b(f.rcpc2), b(f.fcma), b(f.jscvt), b(f.pauth), b(f.bti), b(f.aes), b(f.sha2), b(f.crc32),
      b(f.meetsIosBaseline()), f.missing().c_str(),
      p.mode == ExecMode::kNativeEl0 ? "native_el0" : "tcg", p.reason.c_str(), p.tcgCpu.c_str());
  return buf;
}

}  // namespace iosvm
