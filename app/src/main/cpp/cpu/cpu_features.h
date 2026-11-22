#pragma once
#include <string>

namespace iosvm {

struct CpuFeatures {
  unsigned long hwcap = 0, hwcap2 = 0;
  long pageSize = 4096;
  // ARMv8.2+ baseline required by iOS userland
  bool lse = false;      // HWCAP_ATOMICS
  bool rdm = false;      // HWCAP_ASIMDRDM (SQRDMLAH/SQRDMLSH)
  bool dotprod = false;  // HWCAP_ASIMDDP
  bool fp16 = false;     // HWCAP_FPHP && HWCAP_ASIMDHP
  bool rcpc = false;     // HWCAP_LRCPC (LDAPR)
  // Informational
  bool rcpc2 = false;    // HWCAP_ILRCPC
  bool fcma = false, jscvt = false;   // ARMv8.3 (JavaScriptCore uses both)
  bool pauth = false, bti = false;
  bool aes = false, sha2 = false, crc32 = false;

  bool meetsIosBaseline() const { return lse && rdm && dotprod && fp16 && rcpc; }
  std::string missing() const;
  bool is16kPages() const { return pageSize == 16384; }
};

enum class ExecMode { kNativeEl0, kTcg };

struct ExecPlan {
  ExecMode mode = ExecMode::kTcg;
  std::string reason;
  std::string tcgCpu = "max";   // -cpu model for the TCG fallback
};

CpuFeatures detectCpuFeatures();
ExecPlan planExecution(const CpuFeatures& f, bool nativeEngineAvailable);
std::string toJson(const CpuFeatures& f, const ExecPlan& p);

}  // namespace iosvm
