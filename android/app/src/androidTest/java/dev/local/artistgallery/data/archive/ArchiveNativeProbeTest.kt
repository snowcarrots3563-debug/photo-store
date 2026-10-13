package dev.local.artistgallery.data.archive

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipInputStream
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Candidate gate in .phase2test only; never opens the normal gallery database. */
@RunWith(AndroidJUnit4::class)
class ArchiveNativeProbeTest {
    @Test fun nativeLibraryLoadsOnDeviceAbi() {
        initialize()
        assertTrue(SevenZip.isInitializedSuccessfully())
        println("phase4 native ABI=${Build.SUPPORTED_ABIS.joinToString()} version=${SevenZip.getSevenZipJBindingVersion()}")
    }

    @Test fun rarFixtureMatchesReferenceBytes() = verifyFixture("archive2.zip.0.rar")
    @Test fun sevenZipFixtureMatchesReferenceBytes() = verifyFixture("archive2.zip.0.7z")

    private fun initialize() {
        synchronized(SevenZip::class.java) {
            if (!SevenZip.isInitializedSuccessfully()) {
                System.loadLibrary("7-Zip-JBinding")
                SevenZip.initLoadedLibraries()
            }
        }
    }

    private fun verifyFixture(name: String) {
        initialize()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val reference = linkedMapOf<String, ByteArray>()
        assets.open("archive-fixtures/archive2.zip").use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) reference[ArchivePaths.normalize(entry.name)] = zip.readBytes()
                }
            }
        }
        val file = File(instrumentation.targetContext.cacheDir, "phase4-${UUID.randomUUID()}")
        try {
            assets.open("archive-fixtures/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            RandomAccessFile(file, "r").use { random ->
                val stream = RandomAccessFileInStream(random)
                try {
                    SevenZip.openInArchive(null, stream).use { archive ->
                        val actual = linkedMapOf<String, ByteArray>()
                        for (index in 0 until archive.numberOfItems) {
                            if (archive.getProperty(index, PropID.IS_FOLDER) == true) continue
                            val path = ArchivePaths.normalize(archive.getStringProperty(index, PropID.PATH))
                            val output = ByteArrayOutputStream()
                            val result = archive.extractSlow(index, { bytes ->
                                if (output.size() + bytes.size > 1024 * 1024) throw AssertionError("Fixture extraction limit exceeded")
                                output.write(bytes)
                                bytes.size
                            })
                            assertEquals(ExtractOperationResult.OK, result)
                            actual[path] = output.toByteArray()
                        }
                        assertEquals(reference.keys, actual.keys)
                        reference.forEach { (path, bytes) -> assertArrayEquals(path, bytes, actual[path]) }
                    }
                } finally { stream.close() }
            }
        } finally { assertTrue(!file.exists() || file.delete()) }
    }
}
