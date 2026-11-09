package com.example.finsightai.llm.hf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufCompatibilityTest {
    private val compat = GgufCompatibility()
    private val mb = 1_000_000L
    private val goodRepo = HubRepo(
        id = "acme/Chat-1B-GGUF", tags = listOf("gguf", "license:mit", "text-generation"),
        pipelineTag = "text-generation", ggufArchitecture = "llama", downloads = 10,
    )

    private fun verdict(name: String, size: Long = 500 * mb) = compat.evaluateFile(HubFile(name, size))
    private fun assertRejected(name: String, size: Long = 500 * mb, containing: String? = null) {
        val v = verdict(name, size)
        assertTrue("$name should be rejected but was $v", v is FileVerdict.Rejected)
        if (containing != null) assertTrue((v as FileVerdict.Rejected).reason, containing in v.reason)
    }

    @Test fun parsesQuantizationsFromRealFileNames() {
        val cases = mapOf(
            "gemma-4-E2B_q4_0-it.gguf" to "Q4_0",
            "gemma-4-E2B-it-UD-Q4_K_XL.gguf" to "Q4_K_XL",
            "LFM3-1.7B-Q4_K_M.gguf" to "Q4_K_M",
            "LFM3-1.7B-QAD-Q4_0.gguf" to "Q4_0",
            "Bonsai-1.7B-Q1_0.gguf" to "Q1_0",
            "Ternary-Bonsai-2-2B-PQ2_0.gguf" to "PQ2_0",
            "Ternary-Bonsai-2-2B-PTQ1_0.gguf" to "PTQ1_0",
            "Ternary-Bonsai-2-2B-Q2_0.gguf" to "Q2_0",
            "gemma-4-E2B-it-IQ4_XS.gguf" to "IQ4_XS",
            "model-BF16.gguf" to "BF16",
        )
        for ((file, q) in cases) assertEquals(file, q, GgufCompatibility.parseQuantization(file))
        assertNull(GgufCompatibility.parseQuantization("Bonsai-1.7B.gguf"))
    }

    @Test fun acceptsGoodFiles() {
        for (n in listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf", "m-Q2_0.gguf", "m-Q1_0.gguf", "m-IQ3_M.gguf", "m-UD-Q3_K_XL.gguf")) {
            assertTrue("$n -> ${verdict(n)}", verdict(n) is FileVerdict.Accepted)
        }
    }

    @Test fun rejectsVisionProjectorsImatrixSplitsAndDrafts() {
        assertRejected("mmproj-F16.gguf", containing = "projector")
        assertRejected("mmproj-model-Q8_0.gguf")
        assertRejected("model.imatrix-Q4_K_M.gguf", containing = "importance")
        assertRejected("model-Q4_K_M-00001-of-00003.gguf", containing = "split")
        assertRejected("mtp-gemma-4-E2B-it.gguf", containing = "draft")
        assertRejected("MTP/mtp-gemma-4-E2B-it-Q8_0.gguf")
        assertRejected("README.md", containing = ".gguf")
    }

    @Test fun rejectsUnsupportedOrOversizedQuantizations() {
        assertRejected("m-F16.gguf", containing = "too large")
        assertRejected("m-BF16.gguf", containing = "too large")
        assertRejected("m-F32.gguf")
        assertRejected("Ternary-Bonsai-2-2B-PQ2_0.gguf", containing = "not supported")
        assertRejected("Ternary-Bonsai-2-2B-PTQ1_0.gguf", containing = "not supported")
        assertRejected("m-NVFP4.gguf", containing = "not supported")
        assertRejected("model.gguf", containing = "not recognised")
    }

    @Test fun enforcesSizeLimit() {
        assertRejected("m-Q4_K_M.gguf", size = 4L * 1024 * 1024 * 1024 + 1, containing = "limit")
        assertTrue(verdict("m-Q4_K_M.gguf", 4L * 1024 * 1024 * 1024) is FileVerdict.Accepted)
        assertTrue(GgufCompatibility(maxFileBytes = 1_000_000_000).evaluateFile(HubFile("m-Q4_K_M.gguf", 1_100_000_000)) is FileVerdict.Rejected)
        assertRejected("m-Q4_K_M.gguf", size = 0)
    }

    @Test fun repoLevelRules() {
        assertNull(compat.rejectRepo(goodRepo))
        assertNull(compat.rejectRepo(goodRepo.copy(pipelineTag = null)))
        assertNull(compat.rejectRepo(goodRepo.copy(pipelineTag = "image-text-to-text", ggufArchitecture = "gemma4")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(pipelineTag = "feature-extraction")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(pipelineTag = "automatic-speech-recognition")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(ggufArchitecture = "bert")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(ggufArchitecture = "dflash")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(ggufArchitecture = "some-new-arch")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(tags = listOf("safetensors"), ggufArchitecture = null)))
        assertNotNull(compat.rejectRepo(goodRepo.copy(id = "LiquidAI/LFM3-Audio-2B-GGUF", ggufArchitecture = "lfm3")))
        assertNotNull(compat.rejectRepo(goodRepo.copy(id = "x/bge-embed-GGUF")))
    }

    @Test fun architectureAllowlistMatchesTheFamiliesWeShip() {
        for (a in listOf("gemma4", "lfm3", "qwen35", "qwen3", "llama")) {
            assertTrue(a, a in GgufCompatibility.SUPPORTED_ARCHITECTURES)
        }
        for (a in listOf("bert", "clip", "t5encoder", "wavtokenizer-dec", "dflash", "eagle3")) {
            assertTrue(a, a !in GgufCompatibility.SUPPORTED_ARCHITECTURES)
        }
    }

    @Test fun evaluateSortsPreferredQuantFirstAndReportsRejections() {
        val files = listOf(
            HubFile("m-Q8_0.gguf", 900 * mb), HubFile("m-Q4_K_M.gguf", 500 * mb, "abc"),
            HubFile("m-F16.gguf", 1800 * mb), HubFile("mmproj-m-F16.gguf", 100 * mb),
        )
        val r = compat.evaluate(goodRepo, files)
        assertTrue(r.isUsable)
        assertEquals(listOf("m-Q4_K_M.gguf", "m-Q8_0.gguf"), r.usable.map { it.file.path })
        assertEquals(setOf("m-F16.gguf", "mmproj-m-F16.gguf"), r.rejectedFiles.keys)
        val spec = compat.toSpec(goodRepo, r.usable.first())
        assertEquals("acme/Chat-1B-GGUF/m-Q4_K_M.gguf", spec.id)
        assertEquals("abc", spec.sha256)
        assertEquals("mit", spec.license)
        assertTrue(spec.minRamMb > 500)
    }

    @Test fun repoWithNoUsableFilesIsNotUsable() {
        assertTrue(!compat.evaluate(goodRepo, listOf(HubFile("mmproj-F16.gguf", 100 * mb))).isUsable)
        assertTrue(!compat.evaluate(goodRepo.copy(ggufArchitecture = "bert"), listOf(HubFile("m-Q4_K_M.gguf", mb))).isUsable)
    }
}
