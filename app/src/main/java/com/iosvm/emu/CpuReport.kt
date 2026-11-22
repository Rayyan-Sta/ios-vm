package com.iosvm.emu

import org.json.JSONObject

data class CpuReport(
    val lse: Boolean, val rdm: Boolean, val dotprod: Boolean, val fp16: Boolean, val rcpc: Boolean,
    val pauth: Boolean, val pageSize: Long, val meetsIosBaseline: Boolean, val missing: String,
    val mode: String, val reason: String, val tcgCpu: String,
) {
    /** Helper-VM kernel asset matching the host page size. */
    val helperKernelAsset get() = if (pageSize == 16384L) "Image16k.gz" else "Image.gz"

    fun summary() = "LSE=$lse RDM=$rdm DotProd=$dotprod FP16=$fp16 RCpc=$rcpc PAuth=$pauth " +
        "page=${pageSize}B -> $mode ($reason) [-cpu $tcgCpu]"

    companion object {
        fun detect(engineAvailable: Boolean = false): CpuReport {
            val j = JSONObject(NativeBridge.nativeDetectCpu(engineAvailable))
            val f = j.getJSONObject("features"); val p = j.getJSONObject("plan")
            return CpuReport(
                f.getBoolean("lse"), f.getBoolean("rdm"), f.getBoolean("dotprod"), f.getBoolean("fp16"),
                f.getBoolean("rcpc"), f.getBoolean("pauth"), j.getLong("page_size"),
                j.getBoolean("meets_ios_baseline"), j.getString("missing"),
                p.getString("mode"), p.getString("reason"), p.getString("tcg_cpu"),
            )
        }
    }
}
