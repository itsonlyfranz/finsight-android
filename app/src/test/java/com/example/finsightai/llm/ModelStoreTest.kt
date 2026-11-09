package com.example.finsightai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun spec(name: String, size: Long) = CuratedModels.bonsai17b.copy(
        id = "acme/$name", repoId = "acme", fileName = "$name.gguf", sizeBytes = size, sha256 = null,
    )

    private fun newStore(kv: KeyValueStore = InMemoryKeyValueStore()) = ModelStore(File(tmp.root, "models"), kv)

    private fun stage(store: ModelStore, s: ModelSpec): File =
        store.partFile(s).also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(s.sizeBytes.toInt()) { 7 }) }

    @Test fun commitRegistersModelAndAutoActivatesFirst() {
        val store = newStore()
        val a = spec("a", 100); val b = spec("b", 200)
        val ia = store.commit(a, stage(store, a))
        store.commit(b, stage(store, b))
        assertEquals(100L, ia.sizeOnDisk)
        assertEquals(setOf("acme/a", "acme/b"), store.installed.value.map { it.id }.toSet())
        assertEquals("acme/a", store.activeModel.value?.id)
        assertFalse(store.partFile(a).exists())
        assertTrue(store.targetFile(a).isFile)
    }

    @Test fun persistsAcrossInstancesIncludingSearchMetadataAndActive() {
        val kv = InMemoryKeyValueStore()
        val s1 = newStore(kv)
        val a = spec("a", 10).copy(displayName = "Found via search", source = ModelSource.SEARCH, license = "mit")
        val b = spec("b", 20)
        s1.commit(a, stage(s1, a)); s1.commit(b, stage(s1, b))
        s1.setActive("acme/b")

        val s2 = newStore(kv)
        assertEquals(2, s2.installed.value.size)
        assertEquals("Found via search", s2.find("acme/a")?.spec?.displayName)
        assertEquals(ModelSource.SEARCH, s2.find("acme/a")?.spec?.source)
        assertEquals("acme/b", s2.activeModel.value?.id)
    }

    @Test fun setActiveRejectsUnknownAndNullClears() {
        val store = newStore()
        val a = spec("a", 10)
        store.commit(a, stage(store, a))
        assertFalse(store.setActive("nope"))
        assertEquals("acme/a", store.activeModel.value?.id)
        assertTrue(store.setActive(null))
        assertNull(store.activeModel.value)
    }

    @Test fun deleteInactiveKeepsActiveAndReportsFreedBytes() {
        val store = newStore()
        val a = spec("a", 100); val b = spec("b", 40)
        store.commit(a, stage(store, a)); store.commit(b, stage(store, b))
        val freed = store.delete("acme/b")
        assertEquals(40L, freed)
        assertEquals("acme/a", store.activeModel.value?.id)
        assertEquals(listOf("acme/a"), store.installed.value.map { it.id })
        assertFalse(store.targetFile(b).exists())
    }

    @Test fun deleteActiveClearsActiveSelection() {
        val kv = InMemoryKeyValueStore()
        val store = newStore(kv)
        val a = spec("a", 100)
        store.commit(a, stage(store, a))
        assertEquals("acme/a", store.activeModel.value?.id)
        assertEquals(100L, store.delete("acme/a"))
        assertNull(store.activeModel.value)
        assertTrue(store.installed.value.isEmpty())
        assertNull(newStore(kv).activeModel.value) // persisted
    }

    @Test fun deleteAlsoRemovesPartFileAndCountsIt() {
        val store = newStore()
        val a = spec("a", 100)
        store.commit(a, stage(store, a))
        store.partFile(a).writeBytes(ByteArray(25)) // leftover partial of a re-download
        assertEquals(125L, store.delete("acme/a"))
        assertFalse(store.partFile(a).exists())
        assertEquals(0L, store.delete("acme/a")) // idempotent
    }

    @Test fun discardPartialRemovesAndReportsBytes() {
        val store = newStore()
        val a = spec("a", 100)
        store.partFile(a).also { it.parentFile!!.mkdirs() }.writeBytes(ByteArray(30))
        assertEquals(30L, store.discardPartial(a))
        assertFalse(store.partFile(a).exists())
    }

    @Test fun vanishedOrTruncatedFilesAreDroppedOnReload() {
        val kv = InMemoryKeyValueStore()
        val s1 = newStore(kv)
        val a = spec("a", 100); val b = spec("b", 50)
        s1.commit(a, stage(s1, a)); s1.commit(b, stage(s1, b))
        s1.targetFile(a).delete()
        s1.targetFile(b).writeBytes(ByteArray(3)) // corrupt
        val s2 = newStore(kv)
        assertTrue(s2.installed.value.isEmpty())
        assertNull(s2.activeModel.value)
    }

    @Test fun corruptManifestIsIgnored() {
        val store = newStore()
        File(tmp.root, "models/manifest.json").writeText("{not json")
        assertTrue(newStore().installed.value.isEmpty())
        assertNull(store.activeModel.value)
    }
}
