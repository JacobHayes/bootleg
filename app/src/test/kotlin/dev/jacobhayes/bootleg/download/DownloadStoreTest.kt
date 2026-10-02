package dev.jacobhayes.bootleg.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DownloadStoreTest {
    @TempDir
    lateinit var dir: File

    private fun record(name: String) = DownloadRecord(fileName = name, title = name, url = "https://example.org/$name", createdAtMs = 0)

    @Test
    fun `id slots are unique, reused after removal and survive a restart`() {
        val file = File(dir, "downloads.json")
        val store = DownloadStore(file)
        // Names whose 16-bit hashes collided under the old scheme still get distinct ids.
        val names = (0 until DownloadLimits.RECORDS_MAX).map { "image-$it.iso" }
        val slots = names.map { store.put(record(it)).idSlot }
        assertEquals((0 until DownloadLimits.RECORDS_MAX).toList(), slots)
        assertThrows<IllegalStateException> { store.put(record("one-too-many.iso")) }

        store.remove("image-5.iso")
        assertEquals(5, store.put(record("new.iso")).idSlot)
        assertEquals(5, store.put(store["new.iso"]!!.copy(title = "renamed")).idSlot, "re-put keeps its slot")

        val reloaded = DownloadStore(file)
        assertEquals(store.records.value.mapValues { it.value.idSlot }, reloaded.records.value.mapValues { it.value.idSlot })
    }

    @Test
    fun `duplicate or missing slots in an old file are repaired on load`() {
        val file = File(dir, "downloads.json")
        file.writeText(
            """[{"fileName":"a.iso","title":"a","url":"https://x/a.iso","createdAtMs":1,"idSlot":3},""" +
                """{"fileName":"b.iso","title":"b","url":"https://x/b.iso","createdAtMs":2,"idSlot":3},""" +
                """{"fileName":"c.iso","title":"c","url":"https://x/c.iso","createdAtMs":3}]""",
        )
        val slots =
            DownloadStore(file)
                .records.value.values
                .map { it.idSlot }
        assertEquals(3, slots.toSet().size)
        assertEquals(true, slots.all { it in 0 until DownloadLimits.RECORDS_MAX })
    }
}
