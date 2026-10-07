package com.simplexray.an.core.runtime

import com.simplexray.an.core.runtime.assets.CoreAssetSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoreAssetSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun newServiceCleanupRemovesOnlyAbandonedCoreSnapshots() {
        val cache = temporary.newFolder()
        val stale = File(cache, "core-assets-old").apply { mkdir() }
        File(stale, "geoip.dat").writeText("old-snapshot")
        val unrelated = File(cache, "other-cache").apply { mkdir() }
        File(unrelated, "geoip.dat").writeText("keep")
        CoreAssetSnapshot.clearStale(cache)
        assertFalse(stale.exists())
        assertEquals("keep", File(unrelated, "geoip.dat").readText())
    }

    @Test
    fun atomicDatabaseReplacementCannotChangePreparedRuntimeData() {
        val source = temporary.newFolder()
        val cache = temporary.newFolder()
        val installed = File(source, "geosite.dat").apply { writeText("old") }
        val snapshot = CoreAssetSnapshot.create(source, cache, setOf("geosite.dat"))
        val replacement = File(source, "replacement").apply { writeText("new") }
        Files.move(
            replacement.toPath(),
            installed.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertEquals("old", File(snapshot.directory, "geosite.dat").readText())
        assertEquals("new", installed.readText())
        snapshot.close()
        assertFalse(snapshot.directory.exists())
        assertEquals("new", installed.readText())
    }

    @Test
    fun missingRequiredFileCleansPartialSnapshotAndEmptyRequirementsNeedNoGeodata() {
        val source = temporary.newFolder()
        val cache = temporary.newFolder()
        File(source, "geoip.dat").writeText("valid-existing")
        assertThrows(IllegalArgumentException::class.java) {
            CoreAssetSnapshot.create(source, cache, linkedSetOf("geoip.dat", "geosite.dat"))
        }
        assertEquals(0, cache.listFiles()!!.size)
        CoreAssetSnapshot.create(source, cache, emptySet()).use {
            assertEquals(0, it.directory.listFiles()!!.size)
        }
        assertTrue(File(source, "geoip.dat").exists())
    }
}
