package com.example.finsightai.llm

import com.example.finsightai.llm.hf.FileVerdict
import com.example.finsightai.llm.hf.GgufCompatibility
import com.example.finsightai.llm.hf.HubFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedModelsTest {
    private val compat = GgufCompatibility()

    @Test fun coversAllThreeFamilies() {
        assertEquals(
            setOf(ModelFamily.GEMMA, ModelFamily.BONSAI, ModelFamily.LFM),
            CuratedModels.all.map { it.family }.toSet(),
        )
    }

    @Test fun everyEntryIsAUsableGgufFile() {
        for (m in CuratedModels.all) {
            assertTrue(m.fileName.endsWith(".gguf"))
            assertTrue("${m.id} size", m.sizeBytes > 0)
            assertEquals("${m.id} id", "${m.repoId}/${m.fileName}", m.id)
            assertEquals(ModelSource.CURATED, m.source)
            assertTrue("${m.id} sha", m.sha256?.length == 64)
            val verdict = compat.evaluateFile(HubFile(m.fileName, m.sizeBytes, m.sha256))
            assertTrue("${m.id}: $verdict", verdict is FileVerdict.Accepted)
            assertTrue(m.minRamMb in 1..m.recommendedRamMb)
        }
    }

    @Test fun idsAreUniqueAndLookupWorks() {
        assertEquals(CuratedModels.all.size, CuratedModels.all.map { it.id }.toSet().size)
        assertEquals(CuratedModels.lfm3, CuratedModels.byId(CuratedModels.lfm3.id))
    }

    @Test fun noUnsupportedBonsaiPackingsAreOffered() {
        assertTrue(CuratedModels.all.none { "PQ2_0" in it.fileName || "PTQ1_0" in it.fileName })
    }
}
