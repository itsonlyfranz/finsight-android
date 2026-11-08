package com.example.finsightai.llm

import kotlinx.serialization.Serializable

/** Model families offered in the curated picker. [COMMUNITY] covers anything found through Hub search. */
@Serializable
enum class ModelFamily(val label: String) {
    GEMMA("Gemma 4"),
    BONSAI("Bonsai"),
    LFM("LFM 3"),
    COMMUNITY("Community"),
}

@Serializable
enum class ModelSource { CURATED, SEARCH }

/**
 * One downloadable GGUF file on the Hugging Face Hub, plus what the UI needs to describe it.
 * [sizeBytes] is the real byte size reported by the Hub (used for progress and verification);
 * [sha256] is the LFS object id when known and is verified after download.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val quantization: String,
    val minRamMb: Int,
    val recommendedRamMb: Int,
    val gated: Boolean,
    val license: String,
    val description: String,
    val source: ModelSource,
    val sha256: String? = null,
) {
    /** Path component(s) after `resolve/main/` for this file. */
    val resolvePath: String get() = "$repoId/resolve/main/$fileName"

    val hubUrl: String get() = "https://huggingface.co/$repoId"

    companion object {
        fun idFor(repoId: String, fileName: String) = "$repoId/$fileName"
    }
}

/** Rough memory guidance for GGUF files run through llama.cpp (mmap'd weights + KV cache at ~4k context). */
object RamGuidance {
    private const val MIB = 1024L * 1024L

    fun minRamMb(sizeBytes: Long): Int = (sizeBytes * 1.2 / MIB + 512).toInt()

    fun recommendedRamMb(sizeBytes: Long): Int = (sizeBytes * 1.5 / MIB + 1024).toInt()
}

/**
 * The curated list. Every entry was checked against the live Hub API (repo metadata and
 * /tree/main for real byte sizes and LFS sha256) -- see docs/ in the PR description. Only files that
 * the bundled llama.cpp (v0.9.0) can load are listed: Q1_0/Q2_0 Bonsai builds (the PQ2_0/PTQ1_0
 * builds need the PrismML fork and are deliberately not offered), the gemma4 and lfm3 architectures.
 */
object CuratedModels {
    private fun spec(
        name: String,
        family: ModelFamily,
        repoId: String,
        fileName: String,
        sizeBytes: Long,
        quantization: String,
        sha256: String,
        license: String,
        description: String,
        gated: Boolean = false,
    ) = ModelSpec(
        id = ModelSpec.idFor(repoId, fileName),
        displayName = name,
        family = family,
        repoId = repoId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        quantization = quantization,
        minRamMb = RamGuidance.minRamMb(sizeBytes),
        recommendedRamMb = RamGuidance.recommendedRamMb(sizeBytes),
        gated = gated,
        license = license,
        description = description,
        source = ModelSource.CURATED,
        sha256 = sha256,
    )

    val gemma4E2bQat = spec(
        name = "Gemma 4 E2B (Google QAT)",
        family = ModelFamily.GEMMA,
        repoId = "google/gemma-4-E2B-it-qat-q4_0-gguf",
        fileName = "gemma-4-E2B_q4_0-it.gguf",
        sizeBytes = 3_349_516_256L,
        quantization = "Q4_0 (QAT)",
        sha256 = "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634",
        license = "apache-2.0",
        description = "Google's official quantization-aware-trained 4-bit build. Best quality; needs a flagship phone.",
    )

    val gemma4E2bQ4 = spec(
        name = "Gemma 4 E2B (smaller)",
        family = ModelFamily.GEMMA,
        repoId = "ggml-org/gemma-4-E2B-it-GGUF",
        fileName = "gemma-4-E2B-it-Q4_0.gguf",
        sizeBytes = 2_841_481_184L,
        quantization = "Q4_0",
        sha256 = "8e30dff3ac4c8434c49a7036fa15564bdbb6044e42bf04550bf1a096ad7e6a52",
        license = "apache-2.0",
        description = "Same Gemma 4 E2B model, plain 4-bit quantization from the llama.cpp team. ~500 MB smaller.",
    )

    val bonsai17b = spec(
        name = "Bonsai 1.7B (1-bit)",
        family = ModelFamily.BONSAI,
        repoId = "prism-ml/Bonsai-1.7B-gguf",
        fileName = "Bonsai-1.7B-Q1_0.gguf",
        sizeBytes = 248_302_272L,
        quantization = "Q1_0",
        sha256 = "3d7c6c90dd98717a203adb22d5eacd2581850e40aa5327e144b97766cae5f7e3",
        license = "apache-2.0",
        description = "PrismML's end-to-end 1-bit model. Tiny download, runs on almost any phone.",
    )

    val ternaryBonsai2b = spec(
        name = "Ternary Bonsai 2B",
        family = ModelFamily.BONSAI,
        repoId = "prism-ml/Ternary-Bonsai-2-2B-gguf",
        fileName = "Ternary-Bonsai-2-2B-Q2_0.gguf",
        sizeBytes = 546_689_504L,
        quantization = "Q2_0",
        sha256 = "c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650",
        license = "apache-2.0",
        description = "2B ternary (1.58-bit) Bonsai in the Q2_0 packing that upstream llama.cpp runs. Good quality for ~0.5 GB.",
    )

    val lfm3 = spec(
        name = "LFM3 1.7B",
        family = ModelFamily.LFM,
        repoId = "LiquidAI/LFM3-1.7B-GGUF",
        fileName = "LFM3-1.7B-Q4_K_M.gguf",
        sizeBytes = 1_099_627_808L,
        quantization = "Q4_K_M",
        sha256 = "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4",
        license = "other (LFM Open License)",
        description = "Liquid AI's edge-first LFM3 text model. Fast on CPU and a good all-round default.",
    )

    val all: List<ModelSpec> = listOf(lfm3, bonsai17b, ternaryBonsai2b, gemma4E2bQ4, gemma4E2bQat)

    fun byFamily(family: ModelFamily): List<ModelSpec> = all.filter { it.family == family }

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
