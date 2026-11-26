package com.example.wearable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

private const val MEDIA_GEN_TAG = "MockSceneMediaGen"

/**
 * Generates and caches genuine still JPEG images and H.264 MP4 video loops
 * from bundled inspection scene drawables using YUV420 ByteBuffer/Image input
 * (no Surface/Gralloc dependency) so MockCameraKit.setCameraFeed(Uri) and
 * MockCameraCaptureKit.setCapturedPhoto(Uri) work reliably on emulators and devices.
 */
object MockSceneMediaGenerator {

    private val genMutex = Mutex()

    suspend fun loadSceneBitmap(context: Context, scene: MockFeedScene): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeResource(context.resources, scene.drawableRes)
            }.getOrNull()
        }

    suspend fun ensureSceneImageUri(context: Context, scene: MockFeedScene): Uri =
        withContext(Dispatchers.IO) {
            genMutex.withLock {
                val outFile = File(context.cacheDir, "mock_scene_${scene.name.lowercase()}.jpg")
                if (!outFile.exists() || outFile.length() == 0L) {
                    val bmp = BitmapFactory.decodeResource(context.resources, scene.drawableRes)
                    try {
                        FileOutputStream(outFile).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                Uri.fromFile(outFile)
            }
        }

    suspend fun ensureSceneVideoUri(context: Context, scene: MockFeedScene): Uri? =
        withContext(Dispatchers.IO) {
            genMutex.withLock {
                val outFile = File(context.cacheDir, "mock_feed_${scene.name.lowercase()}.mp4")
                if (outFile.exists() && outFile.length() > 1024L) {
                    return@withLock Uri.fromFile(outFile)
                }

                val tempFile = File(context.cacheDir, "mock_feed_${scene.name.lowercase()}.tmp.mp4")
                if (tempFile.exists()) tempFile.delete()

                val rawBmp = runCatching {
                    BitmapFactory.decodeResource(context.resources, scene.drawableRes)
                }.getOrNull() ?: return@withLock null

                val width = 320
                val height = 240
                val fps = 15
                val totalFrames = 10
                val frameDurationUs = 1_000_000L / fps

                val scaledBmp = if (rawBmp.width == width && rawBmp.height == height) {
                    rawBmp
                } else {
                    Bitmap.createScaledBitmap(rawBmp, width, height, true).also {
                        if (it !== rawBmp) rawBmp.recycle()
                    }
                }

                val yuvPlanes = argbBitmapToYuv420(scaledBmp)
                scaledBmp.recycle()

                var encoder: MediaCodec? = null
                var muxer: MediaMuxer? = null
                var muxerStarted = false
                var muxerStopped = false
                var samplesWritten = 0

                try {
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, 450_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }

                    encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }

                    muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    var pendingOutputFormat: MediaFormat? = null
                    var trackIndex = -1
                    var lastPtsUs = -1L
                    val bufferInfo = MediaCodec.BufferInfo()

                    fun drainOutput(waitForEos: Boolean) {
                        val enc = encoder ?: return
                        val mx = muxer ?: return
                        var emptyRetries = 0
                        val maxIterations = if (waitForEos) 200 else 16
                        for (iter in 0 until maxIterations) {
                            val outIdx = enc.dequeueOutputBuffer(bufferInfo, 10_000L)
                            when {
                                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    if (!waitForEos) break
                                    emptyRetries++
                                    if (emptyRetries > 60) break
                                }
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    pendingOutputFormat = enc.outputFormat
                                }
                                outIdx >= 0 -> {
                                    val encodedData = enc.getOutputBuffer(outIdx)
                                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                    if (encodedData != null && bufferInfo.size > 0 && !isConfig) {
                                        if (!muxerStarted) {
                                            val fmt = pendingOutputFormat ?: enc.outputFormat
                                            trackIndex = mx.addTrack(fmt)
                                            mx.start()
                                            muxerStarted = true
                                        }
                                        if (muxerStarted && trackIndex >= 0) {
                                            if (bufferInfo.presentationTimeUs <= lastPtsUs) {
                                                bufferInfo.presentationTimeUs = if (lastPtsUs < 0L) 0L else lastPtsUs + frameDurationUs
                                            }
                                            lastPtsUs = bufferInfo.presentationTimeUs
                                            encodedData.position(bufferInfo.offset)
                                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                            mx.writeSampleData(trackIndex, encodedData, bufferInfo)
                                            samplesWritten++
                                        }
                                    }
                                    val reachedEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                    enc.releaseOutputBuffer(outIdx, false)
                                    if (reachedEos) break
                                }
                            }
                        }
                    }

                    val enc = encoder!!
                    var frameIdx = 0
                    var eosSent = false
                    var inputAttempts = 0
                    while (!eosSent && inputAttempts < 100) {
                        inputAttempts++
                        val inIdx = enc.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            if (frameIdx < totalFrames) {
                                val ptsUs = frameIdx * frameDurationUs
                                val img = runCatching { enc.getInputImage(inIdx) }.getOrNull()
                                val bytesQueued = if (img != null) {
                                    fillInputImageFromYuv(img, yuvPlanes, width, height)
                                } else {
                                    val buf = enc.getInputBuffer(inIdx)
                                    if (buf != null) fillInputBufferI420(buf, yuvPlanes) else 0
                                }
                                enc.queueInputBuffer(inIdx, 0, bytesQueued, ptsUs, 0)
                                frameIdx++
                            } else {
                                enc.queueInputBuffer(
                                    inIdx,
                                    0,
                                    0,
                                    frameIdx * frameDurationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                eosSent = true
                            }
                        }
                        drainOutput(waitForEos = false)
                    }
                    drainOutput(waitForEos = true)

                    if (muxerStarted && samplesWritten > 0) {
                        muxer.stop()
                        muxerStopped = true
                        if (tempFile.exists() && tempFile.length() > 1024L) {
                            if (outFile.exists()) outFile.delete()
                            tempFile.renameTo(outFile)
                            Uri.fromFile(outFile)
                        } else {
                            tempFile.delete()
                            null
                        }
                    } else {
                        tempFile.delete()
                        null
                    }
                } catch (t: Throwable) {
                    Log.w(MEDIA_GEN_TAG, "Unable to encode mock H.264 feed for ${scene.name}", t)
                    tempFile.delete()
                    null
                } finally {
                    runCatching { encoder?.stop() }
                    runCatching { encoder?.release() }
                    if (muxerStarted && samplesWritten > 0 && !muxerStopped) {
                        runCatching { muxer?.stop() }
                    }
                    runCatching { muxer?.release() }
                }
            }
        }

    private data class Yuv420Planes(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray
    )

    private fun argbBitmapToYuv420(bitmap: Bitmap): Yuv420Planes {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)

        val yPlane = ByteArray(w * h)
        val uPlane = ByteArray((w / 2) * (h / 2))
        val vPlane = ByteArray((w / 2) * (h / 2))

        var yIndex = 0
        var uvIndex = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = argb[j * w + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[yIndex++] = y.coerceIn(0, 255).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uPlane[uvIndex] = u.coerceIn(0, 255).toByte()
                    vPlane[uvIndex] = v.coerceIn(0, 255).toByte()
                    uvIndex++
                }
            }
        }
        return Yuv420Planes(yPlane, uPlane, vPlane)
    }

    private fun fillInputImageFromYuv(
        image: Image,
        yuv: Yuv420Planes,
        width: Int,
        height: Int
    ): Int {
        val planes = image.planes
        // Y plane
        val yBuf = planes[0].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        if (yPixelStride == 1 && yRowStride == width && yBuf.remaining() >= yuv.y.size) {
            yBuf.put(yuv.y)
        } else {
            for (row in 0 until height) {
                val srcOff = row * width
                for (col in 0 until width) {
                    yBuf.put(row * yRowStride + col * yPixelStride, yuv.y[srcOff + col])
                }
            }
        }

        // U and V planes
        val chromaW = width / 2
        val chromaH = height / 2
        val uBuf = planes[1].buffer
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vBuf = planes[2].buffer
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        for (row in 0 until chromaH) {
            val srcOff = row * chromaW
            for (col in 0 until chromaW) {
                uBuf.put(row * uRowStride + col * uPixelStride, yuv.u[srcOff + col])
                vBuf.put(row * vRowStride + col * vPixelStride, yuv.v[srcOff + col])
            }
        }
        return yuv.y.size + yuv.u.size + yuv.v.size
    }

    private fun fillInputBufferI420(buffer: ByteBuffer, yuv: Yuv420Planes): Int {
        buffer.clear()
        val total = yuv.y.size + yuv.u.size + yuv.v.size
        if (buffer.remaining() >= total) {
            buffer.put(yuv.y)
            buffer.put(yuv.u)
            buffer.put(yuv.v)
            return total
        }
        return 0
    }
}
