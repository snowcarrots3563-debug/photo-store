package dev.local.artistgallery.data.archive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveAdapterDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun productionRarAdapterMatchesIndependentZipReference() = verifyBundled("archive2.zip.0.rar", "RAR")
    @Test fun productionSevenZipAdapterMatchesIndependentZipReference() = verifyBundled("archive2.zip.0.7z", "7z")

    @Test fun independentlyGeneratedZipAndSevenZipVariantsMatchHashes() {
        assertEquals("dev.local.artistgallery.phase2test", instrumentation.targetContext.packageName)
        val folder = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "phase4-neutral-20261010")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        val archives = manifest.getJSONArray("archives")
        val results = org.json.JSONArray()
        for (index in 0 until archives.length()) {
            val variant = archives.getJSONObject(index)
            val main = File(folder, variant.getString("name"))
            val array = variant.getJSONArray("volumes")
            val volumes = (0 until array.length()).map { File(folder, array.getString(it)) }
            assertEquals(main.name, ArchiveBundle.mainName(variant.getString("format"), volumes.map { it.name }))
            val reader = if (variant.getString("format") == "ZIP" && !main.name.endsWith(".001")) ZipArchiveReader(main, volumes, 10_000_000)
                else NativeArchiveReader(main, volumes, 10_000_000, expectedFormat = variant.getString("format"))
            reader.use {
                if (variant.getBoolean("encrypted")) {
                    try { it.entries(); fail("Encrypted headers should require a password") }
                    catch (error: ArchiveReadException) { assertEquals(ArchiveError.PASSWORD, error.reason) }
                    it.setPassword(manifest.getString("fixturePassword").toCharArray())
                }
                val entries = it.entries().filterNot { entry -> entry.isDirectory }
                val expected = manifest.getJSONObject("entries")
                assertEquals(expected.length(), entries.size)
                entries.forEach { entry ->
                    val output = ByteArrayOutputStream()
                    assertEquals(entry.byteSize, it.extract(entry.index, output) {})
                    val wanted = expected.getJSONObject(entry.path)
                    assertEquals(wanted.getLong("bytes"), output.size().toLong())
                    assertEquals(wanted.getString("sha256"), hash(output.toByteArray()))
                }
            }
            println("phase4 production adapter variant=${main.name} passed")
            results.put(JSONObject().put("name", main.name).put("status", "PASSED"))
        }
        File(folder, "adapter-results.json").writeText(JSONObject().put("status", "PASSED").put("variants", results).toString(2))
    }

    private fun verifyBundled(name: String, format: String) {
        val assets = instrumentation.context.assets
        val expected = linkedMapOf<String, ByteArray>()
        assets.open("archive-fixtures/archive2.zip").use { ZipInputStream(it).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) expected[ArchivePaths.normalize(entry.name)] = zip.readBytes() }
        } }
        val folder = File(instrumentation.targetContext.cacheDir, "adapter-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val source = File(folder, name)
            assets.open("archive-fixtures/$name").use { input -> source.outputStream().use { input.copyTo(it) } }
            NativeArchiveReader(source, listOf(source), 1_000_000, expectedFormat = format).use { reader ->
                val entries = reader.entries().filterNot { it.isDirectory }
                assertEquals(expected.keys, entries.map { it.path }.toSet())
                entries.forEach { entry -> val output = ByteArrayOutputStream(); reader.extract(entry.index, output) {}; assertArrayEquals(entry.path, expected[entry.path], output.toByteArray()) }
            }
        } finally {
            require(requireNotNull(folder.parentFile).canonicalFile == instrumentation.targetContext.cacheDir.canonicalFile && folder.name.startsWith("adapter-"))
            assertTrue(folder.deleteRecursively())
        }
    }

    private fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
