package com.vexa.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object VideoGenerator {

    @Synchronized
    fun createCinematicMp4(
        ctx: Context,
        scenes: List<Bitmap>,
        titleOverlay: String
    ): FileGenerator.GeneratedFile? {
        if (scenes.isEmpty()) return null
        val outDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VexaVideos")
        if (!outDir.exists()) outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(6)
        val mp4File = File(outDir, "Vexa_Video_${stamp}_$suffix.mp4")

        val ok = tryEncodeMp4(mp4File, scenes, titleOverlay)
        return if (ok && mp4File.exists() && mp4File.length() > 1500L) {
            FileGenerator.GeneratedFile(mp4File, "video/mp4", mp4File.name)
        } else {
            null
        }
    }

    private fun tryEncodeMp4(
        outFile: File,
        scenes: List<Bitmap>,
        titleOverlay: String
    ): Boolean {
        val width = 720
        val height = 720
        val fps = 24
        val secondsPerScene = 2
        val framesPerScene = fps * secondsPerScene
        val bitRate = 2_400_000

        var videoEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null

        return try {
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrack = -1
            var muxerStarted = false

            val info = MediaCodec.BufferInfo()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(145, 9, 9, 11) }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#10A37F")
                textSize = 22f
                isFakeBoldText = true
            }

            val totalFrames = scenes.size * framesPerScene
            val frameUs = 1_000_000L / fps

            for (fIdx in 0 until totalFrames) {
                val sIdx = (fIdx / framesPerScene).coerceAtMost(scenes.size - 1)
                val frameInScene = fIdx % framesPerScene
                val progress = frameInScene.toFloat() / framesPerScene.toFloat()
                val bmp = scenes[sIdx]
                val nextBmp = scenes[(sIdx + 1).coerceAtMost(scenes.size - 1)]

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    val baseScale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                    val zoom = if (sIdx % 2 == 0) 1.0f + 0.14f * progress else 1.14f - 0.14f * progress
                    val scale = baseScale * zoom
                    val dx = (width - bmp.width * scale) * 0.5f + (if (sIdx % 2 == 0) -18f * progress else 18f * progress)
                    val dy = (height - bmp.height * scale) * 0.5f
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(dx, dy)
                    }
                    canvas.drawBitmap(bmp, m, paint)

                    if (sIdx < scenes.size - 1 && progress > 0.78f) {
                        val alpha = (((progress - 0.78f) / 0.22f) * 255f).toInt().coerceIn(0, 255)
                        fadePaint.alpha = alpha
                        val nScale = maxOf(width.toFloat() / nextBmp.width, height.toFloat() / nextBmp.height)
                        val nm = Matrix().apply {
                            postScale(nScale, nScale)
                            postTranslate((width - nextBmp.width * nScale) * 0.5f, (height - nextBmp.height * nScale) * 0.5f)
                        }
                        canvas.drawBitmap(nextBmp, nm, fadePaint)
                    }

                    canvas.drawRect(Rect(0, height - 48, width, height), badgePaint)
                    canvas.drawText("VEXA AI  •  ${titleOverlay.take(38)}", 22f, height - 16f, textPaint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                while (true) {
                    val st = videoEncoder.dequeueOutputBuffer(info, 1000)
                    if (st == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrack < 0) {
                            videoTrack = muxer.addTrack(videoEncoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (st >= 0) {
                        val outBuf = videoEncoder.getOutputBuffer(st)
                        if (outBuf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                info.presentationTimeUs = fIdx * frameUs
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, outBuf, info)
                            }
                        }
                        videoEncoder.releaseOutputBuffer(st, false)
                    }
                }
            }

            videoEncoder.signalEndOfInputStream()
            while (true) {
                val st = videoEncoder.dequeueOutputBuffer(info, 2500)
                if (st < 0) break
                val outBuf = videoEncoder.getOutputBuffer(st)
                if (outBuf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, outBuf, info)
                    }
                }
                videoEncoder.releaseOutputBuffer(st, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            muxerStarted
        } catch (_: Exception) {
            false
        } finally {
            try { inputSurface?.release() } catch (_: Exception) { }
            try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) { }
            try { if (muxer != null) { muxer.stop(); muxer.release() } } catch (_: Exception) { }
        }
    }
}
