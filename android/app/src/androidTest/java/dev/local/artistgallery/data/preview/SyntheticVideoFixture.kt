package dev.local.artistgallery.data.preview

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import dev.local.artistgallery.data.importing.MediaFileStore
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Shared synthetic codec fixtures only. Own UUID files use the registered FileProvider root. */
class SyntheticVideoFixture(private val context: Context) {
    val files = MediaFileStore(context)
    val reader = AssetReader(context, files)
    private val directory = File(context.cacheDir, "video-test-${UUID.randomUUID()}").apply { check(mkdir()) }.canonicalFile
    private val sources = mutableSetOf<File>()
    private val originals = mutableSetOf<String>()
    private val staging = mutableSetOf<String>()

    fun video(width: Int = 320, height: Int = 160, rotation: Int = 0, blackFirst: Boolean = false, frames: Int = 12): String {
        val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).let { caps ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats &&
                        caps.videoCapabilities.areSizeAndRateSupported(width, height, 10.0)
                }
        }
        assumeTrue("Device has no flexible YUV AVC encoder for ${width}x$height", encoderInfo != null)
        val source = sourceFile()
        val encoder = MediaCodec.createByCodecName(requireNotNull(encoderInfo).name)
        val muxer = MediaMuxer(source.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var codecStarted = false
        try {
            muxer.setOrientationHint(rotation)
            encoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 3).coerceAtLeast(500_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var frame = 0
            var track = -1
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "Synthetic AVC encode timed out" }
                if (!inputEnded) {
                    val input = encoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        if (frame == frames) {
                            encoder.queueInputBuffer(input, 0, 0, frame * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            requireNotNull(encoder.getInputImage(input)).use { image ->
                                image.planes.forEachIndexed { planeIndex, plane ->
                                    val planeWidth = if (planeIndex == 0) width else width / 2
                                    val planeHeight = if (planeIndex == 0) height else height / 2
                                    val normal = if (frame == 0) {
                                        if (blackFirst) intArrayOf(16, 128, 128) else intArrayOf(82, 90, 240)
                                    } else intArrayOf(41, 240, 110)
                                    val white = intArrayOf(235, 128, 128)
                                    val base = plane.buffer.position()
                                    val row = ByteArray(planeWidth)
                                    for (y in 0 until planeHeight) {
                                        for (x in 0 until planeWidth) {
                                            val marker = frame == 0 && !blackFirst && x < planeWidth / 4 && y < planeHeight / 4
                                            val value = (if (marker) white[planeIndex] else normal[planeIndex]).toByte()
                                            if (plane.pixelStride == 1) row[x] = value
                                            else plane.buffer.put(base + y * plane.rowStride + x * plane.pixelStride, value)
                                        }
                                        if (plane.pixelStride == 1) {
                                            plane.buffer.position(base + y * plane.rowStride)
                                            plane.buffer.put(row)
                                        }
                                    }
                                }
                            }
                            encoder.queueInputBuffer(input, 0, width * height * 3 / 2, frame * 100_000L, 0)
                            frame++
                        }
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!started)
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                } else if (output >= 0) {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(started)
                            val buffer = requireNotNull(encoder.getOutputBuffer(output))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { encoder.releaseOutputBuffer(output, false) }
                }
            }
        } finally {
            try { if (codecStarted) encoder.stop() } finally {
                encoder.release()
                try { if (started) muxer.stop() } finally { muxer.release() }
            }
        }
        return store(source)
    }

    fun broken(): String = sourceFile().apply { writeText("not an MP4") }.let(::store)

    fun noTrack(): String = sourceFile().apply {
        writeBytes(ByteBuffer.allocate(32).putInt(24).put("ftyp".toByteArray()).put("isom".toByteArray())
            .putInt(0).put("isom".toByteArray()).put("mp42".toByteArray()).putInt(8).put("moov".toByteArray()).array())
    }.let(::store)

    private fun sourceFile(): File = File(directory, "${UUID.randomUUID()}.mp4").also { sources += it }

    private fun store(source: File): String = runBlocking {
        val staged = UUID.randomUUID().toString()
        val key = UUID.randomUUID().toString()
        check(!files.hasStaging(staged) && !files.hasOriginal(key))
        staging += staged
        originals += key
        files.writeToStaging(staged) { output -> source.inputStream().use { it.copyTo(output) } }
        files.promote(staged, key)
        key
    }

    fun cleanup() = runBlocking {
        val root = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        originals.forEach { key ->
            check(files.originalFile(key).canonicalFile == File(root, "originals/$key"))
            assertTrue(files.deleteOriginal(key))
        }
        staging.forEach { key ->
            check(files.stagingFile(key).canonicalFile == File(root, ".artistgallery-imports/$key.part"))
            assertTrue(files.deleteStaging(key))
        }
        check(directory.parentFile == context.cacheDir.canonicalFile && directory.name.startsWith("video-test-"))
        sources.forEach { file -> check(file.canonicalFile.parentFile == directory); if (file.exists()) assertTrue(file.delete()) }
        assertTrue(directory.delete())
    }
}
