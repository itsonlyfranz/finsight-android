package com.example.finsightai.llm.hf

import com.example.finsightai.llm.ModelFamily
import com.example.finsightai.llm.ModelSource
import com.example.finsightai.llm.ModelSpec
import com.example.finsightai.llm.RamGuidance

/** What the Hub search tells us about a repo (from `expand[]=...`). */
data class HubRepo(
    val id: String,
    val tags: List<String> = emptyList(),
    val libraryName: String? = null,
    val pipelineTag: String? = null,
    val ggufArchitecture: String? = null,
    val gated: Boolean = false,
    val downloads: Long = 0,
    val likes: Long = 0,
) {
    val license: String
        get() = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:") ?: "unknown"
}

/** One file in a repo tree. [sha256] is the LFS oid when the Hub reports one. */
data class HubFile(val path: String, val sizeBytes: Long, val sha256: String? = null)

/** A file that this app can download and run, with its parsed quantization. */
data class UsableFile(val file: HubFile, val quantization: String)

data class CompatibilityReport(
    val repoRejection: String?,
    val usable: List<UsableFile>,
    /** path -> why it was rejected (diagnostics and tests; never shown as results). */
    val rejectedFiles: Map<String, String>,
) {
    val isUsable: Boolean get() = repoRejection == null && usable.isNotEmpty()
}

/**
 * Decides which Hub repos/files can run on-device with the bundled llama.cpp (v0.9.0).
 * Pure Kotlin so it is unit-testable on the JVM.
 */
class GgufCompatibility(
    /** Largest single GGUF we will offer. Phones cannot hold more than this in RAM anyway. */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {

    /** Returns null when the repo may contain usable models, else a human-readable reason. */
    fun rejectRepo(repo: HubRepo): String? {
        val lowerTags = repo.tags.map { it.lowercase() }
        if ("gguf" !in lowerTags && repo.libraryName?.lowercase() != "gguf" && repo.ggufArchitecture == null) {
            return "not a GGUF repository"
        }
        val pipeline = repo.pipelineTag?.lowercase()
        if (!pipeline.isNullOrBlank() && pipeline !in ALLOWED_PIPELINES) {
            return "pipeline '$pipeline' is not text generation"
        }
        val arch = repo.ggufArchitecture?.lowercase()
        if (arch != null && arch !in SUPPORTED_ARCHITECTURES) {
            return "architecture '$arch' is not supported by the on-device runtime"
        }
        val name = repo.id.substringAfter('/').lowercase()
        if (lowerTags.any { it in BLOCKED_TAGS } || BLOCKED_NAME_PARTS.any { name.contains(it) }) {
            return "audio/embedding/speech model, not a chat model"
        }
        return null
    }

    /** Returns the parsed quantization or a rejection reason for a single file. */
    fun evaluateFile(file: HubFile): FileVerdict {
        val path = file.path
        val base = path.substringAfterLast('/')
        val lower = base.lowercase()
        if (!lower.endsWith(".gguf")) return FileVerdict.Rejected("not a .gguf file")
        if ('/' in path) return FileVerdict.Rejected("nested file (drafts / extras live in sub-folders)")
        if (lower.contains("mmproj")) return FileVerdict.Rejected("vision projector, not a language model")
        if (lower.contains("imatrix")) return FileVerdict.Rejected("importance matrix, not a model")
        if (lower.startsWith("mtp-") || lower.contains("-mtp") || lower.contains("draft")) {
            return FileVerdict.Rejected("speculative-decoding draft model")
        }
        if (SPLIT_PATTERN.containsMatchIn(lower)) return FileVerdict.Rejected("split GGUF (multi-part) is not supported")
        if (file.sizeBytes <= 0) return FileVerdict.Rejected("unknown file size")
        if (file.sizeBytes > maxFileBytes) {
            return FileVerdict.Rejected("larger than the ${formatGiB(maxFileBytes)} limit for phones")
        }
        val quant = parseQuantization(base) ?: return FileVerdict.Rejected("quantization not recognised")
        if (quant in UNSUPPORTED_QUANTS) return FileVerdict.Rejected("$quant is not supported by the bundled llama.cpp")
        if (quant in TOO_LARGE_QUANTS) return FileVerdict.Rejected("$quant (unquantized) is too large for phones")
        if (!isSupportedQuant(quant)) return FileVerdict.Rejected("$quant is not supported")
        return FileVerdict.Accepted(quant)
    }

    fun evaluate(repo: HubRepo, files: List<HubFile>): CompatibilityReport {
        val repoReason = rejectRepo(repo)
        if (repoReason != null) return CompatibilityReport(repoReason, emptyList(), emptyMap())
        val usable = mutableListOf<UsableFile>()
        val rejected = linkedMapOf<String, String>()
        for (f in files) {
            when (val v = evaluateFile(f)) {
                is FileVerdict.Accepted -> usable += UsableFile(f, v.quantization)
                is FileVerdict.Rejected -> rejected[f.path] = v.reason
            }
        }
        usable.sortWith(compareBy({ quantRank(it.quantization) }, { it.file.sizeBytes }))
        return CompatibilityReport(null, usable, rejected)
    }

    /** Builds the downloadable spec for a usable file of [repo]. */
    fun toSpec(repo: HubRepo, usable: UsableFile): ModelSpec {
        val shortName = repo.id.substringAfter('/')
        return ModelSpec(
            id = ModelSpec.idFor(repo.id, usable.file.path),
            displayName = "$shortName · ${usable.quantization}",
            family = guessFamily(repo.id, repo.tags),
            repoId = repo.id,
            fileName = usable.file.path,
            sizeBytes = usable.file.sizeBytes,
            quantization = usable.quantization,
            minRamMb = RamGuidance.minRamMb(usable.file.sizeBytes),
            recommendedRamMb = RamGuidance.recommendedRamMb(usable.file.sizeBytes),
            gated = repo.gated,
            license = repo.license,
            description = buildString {
                append(repo.ggufArchitecture ?: "GGUF")
                append(" · ").append(formatMb(usable.file.sizeBytes))
                append(" · ").append(repo.downloads).append(" downloads")
            },
            source = ModelSource.SEARCH,
            sha256 = usable.file.sha256,
        )
    }

    private fun isSupportedQuant(q: String): Boolean = q in SUPPORTED_QUANTS || SUPPORTED_QUANT_PATTERN.matches(q)

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 4L * 1024 * 1024 * 1024

        /** Pipeline tags of chat-capable models. Missing pipeline_tag is also accepted. */
        val ALLOWED_PIPELINES = setOf(
            "text-generation", "image-text-to-text", "any-to-any", "conversational", "text2text-generation",
        )

        private val BLOCKED_TAGS = setOf(
            "audio", "speech", "tts", "asr", "text-to-speech", "automatic-speech-recognition",
            "embedding", "embeddings", "sentence-embeddings", "reranker", "diffusion", "text-to-image",
        )
        private val BLOCKED_NAME_PARTS = listOf("-audio", "embed", "rerank", "-tts", "whisper", "vocoder")

        /** ggml quantization types that llama.cpp v0.9.0 loads and has a CPU kernel for (ggml.h / ggml-cpu.c). */
        val SUPPORTED_QUANTS = setOf(
            "Q1_0", "Q2_0",
            "Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M",
            "Q5_0", "Q5_1", "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_0",
            "IQ1_S", "IQ1_M", "IQ2_XXS", "IQ2_XS", "IQ2_S", "IQ2_M",
            "IQ3_XXS", "IQ3_XS", "IQ3_S", "IQ3_M", "IQ4_NL", "IQ4_XS",
            "TQ1_0", "TQ2_0", "MXFP4",
        )

        /** Dynamic ("UD-*_K_XL") Unsloth variants of the K-quants load as regular K-quants. */
        private val SUPPORTED_QUANT_PATTERN = Regex("Q[2-8]_K_XL")

        /** Known types we deliberately refuse. PQ2_0 / PTQ1_0 exist only in the PrismML llama.cpp fork. */
        val UNSUPPORTED_QUANTS = setOf("PQ2_0", "PTQ1_0", "NVFP4")

        /** Unquantized weights: never fit a phone's RAM budget in a useful way. */
        val TOO_LARGE_QUANTS = setOf("F32", "F16", "BF16")

        /** Architectures (gguf `general.architecture`) of text-generation models in llama.cpp v0.9.0 (src/llama-arch.cpp). */
        val SUPPORTED_ARCHITECTURES = setOf(
            "llama", "llama4", "deci", "falcon", "gpt2", "gptj", "gptneox", "mpt", "baichuan", "starcoder",
            "refact", "bloom", "stablelm", "qwen", "qwen2", "qwen2moe", "qwen3", "qwen3moe", "qwen3next",
            "qwen35", "qwen35moe", "phi2", "phi3", "phimoe", "plamo", "plamo2", "plamo3", "codeshell",
            "orion", "internlm2", "minicpm", "minicpm3", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4",
            "starcoder2", "mamba", "mamba2", "jamba", "falcon-h1", "xverse", "command-r", "cohere2",
            "olmo", "olmo2", "olmoe", "openelm", "deepseek", "deepseek2", "chatglm", "glm4", "glm4moe",
            "bitnet", "exaone", "exaone4", "rwkv6", "rwkv6qwen2", "rwkv7", "arwkv7", "granite",
            "granitemoe", "granitehybrid", "smollm3", "gpt-oss", "lfm2", "lfm2moe", "lfm3", "arcee",
            "ernie4_5", "ernie4_5-moe", "hunyuan-moe", "hunyuan-dense", "seed_oss", "smallthinker",
            "nemotron", "nemotron_h", "nemotron_h_moe", "afmoe", "bailingmoe", "bailingmoe2", "apertus",
            "mistral3", "kimi-linear", "plm", "jais", "dots1",
        )

        private val SPLIT_PATTERN = Regex("-\\d{5}-of-\\d{5}")

        // Longest alternatives first so Q4_K_M is not read as Q4_K, IQ4_NL not as Q4_...
        private val QUANT_PATTERN = Regex(
            "(?<![A-Z0-9])(PTQ1_0|PQ2_0|TQ1_0|TQ2_0|NVFP4|MXFP4|BF16|F16|F32|" +
                "IQ[1-4]_(?:XXS|XS|NL|S|M)|Q[2-8]_K_(?:XL|S|M|L)|Q[2-8]_K|Q[0-9]_[01])(?![A-Z0-9])",
        )

        /** Extracts the ggml quantization label from a file name, or null. */
        fun parseQuantization(fileName: String): String? {
            val base = fileName.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF").uppercase()
            return QUANT_PATTERN.find(base)?.groupValues?.get(1)
        }

        /** Lower rank = preferred default. Q4_K_M first (best size/quality on phones), then other 4-bit, etc. */
        fun quantRank(q: String): Int {
            val order = listOf(
                "Q4_K_M", "Q4_0", "Q4_K_S", "Q4_1", "IQ4_XS", "IQ4_NL", "Q4_K_XL", "Q5_K_M", "Q5_K_S", "Q5_0",
                "Q5_1", "Q3_K_M", "Q3_K_L", "Q3_K_S", "Q3_K_XL", "Q6_K", "Q8_0", "Q2_K", "Q2_K_XL", "Q2_0", "Q1_0",
            )
            val i = order.indexOf(q)
            return if (i >= 0) i else order.size
        }

        fun guessFamily(repoId: String, tags: List<String>): ModelFamily {
            val s = (repoId + " " + tags.joinToString(" ")).lowercase()
            return when {
                "bonsai" in s -> ModelFamily.BONSAI
                "gemma" in s -> ModelFamily.GEMMA
                "lfm" in s || "liquid" in s -> ModelFamily.LFM
                else -> ModelFamily.COMMUNITY
            }
        }

        private fun formatGiB(bytes: Long) = "%.0f GiB".format(bytes / (1024.0 * 1024 * 1024))
        private fun formatMb(bytes: Long): String =
            if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%d MB".format(bytes / 1_000_000L)
    }
}

sealed interface FileVerdict {
    data class Accepted(val quantization: String) : FileVerdict
    data class Rejected(val reason: String) : FileVerdict
}
