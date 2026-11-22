package org.scyllasband.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SamplePlayer {
    private const val BASE = "https://lowkeytea.dev/scyllasband/samples/audio"
    private const val GAIN = 5f // +8 dB soft-limited, same as the synthesis paths

    fun url(voiceId: String, kind: String = "hello"): String =
        "$BASE/${voiceId}_$kind.mp3"

    /** Streams a studio sample from the website, boosting it to the same level
     *  as on-device synthesis (tanh soft-limit, no clipping). */
    suspend fun play(context: Context, voiceId: String, kind: String = "hello"): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(url(voiceId, kind)).openStream().use { it.readBytes() }
                val (pcm, rate, channels) = decodeMp3(bytes)
                playPcm(pcm, rate, channels)
            }.onFailure { e ->
                if (e is IOException) withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.sample_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    /** Plays the bundled demo WAV with the same boost. */
    suspend fun playWav(context: Context, resId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            val (pcm, rate, channels) = decodeWav(bytes)
            playPcm(pcm, rate, channels)
        }
    }

    private fun decodeMp3(bytes: ByteArray): Triple<ByteArray, Int, Int> {
        val file = File.createTempFile("scy_sample", ".mp3")
        file.writeBytes(bytes)
        val extractor = MediaExtractor()
        val codec: MediaCodec
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val timeout = 10_000L
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(timeout)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, timeout)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size)
                    buf.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            codec.stop(); codec.release()
            return Triple(out.toByteArray(), rate, channels)
        } finally {
            extractor.release()
            file.delete()
        }
    }

    private fun decodeWav(bytes: ByteArray): Triple<ByteArray, Int, Int> {
        fun int32(off: Int) = (bytes[off].toInt() and 0xff) or
            ((bytes[off + 1].toInt() and 0xff) shl 8) or
            ((bytes[off + 2].toInt() and 0xff) shl 16) or
            ((bytes[off + 3].toInt() and 0xff) shl 24)
        val channels = ((bytes[22].toInt() and 0xff) or ((bytes[23].toInt() and 0xff) shl 8))
        val rate = int32(24)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = int32(offset + 4)
            if (id == "data") {
                val data = bytes.copyOfRange(offset + 8, (offset + 8 + size).coerceAtMost(bytes.size))
                return Triple(data, rate, channels)
            }
            offset += 8 + size
        }
        return Triple(ByteArray(0), rate, channels)
    }

    private fun playPcm(pcm: ByteArray, rate: Int, channels: Int) {
        if (pcm.isEmpty()) return
        // Apply the same soft-limited gain used by the synthesis paths.
        for (i in pcm.indices step 2) {
            val raw = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val s = if (raw > 32767) raw - 65536 else raw
            val v = (tanh((s / 32767f * GAIN).toDouble()) * 32767).toInt()
            pcm[i] = (v and 0xff).toByte()
            pcm[i + 1] = ((v shr 8) and 0xff).toByte()
        }
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(mask).build())
            .setBufferSizeInBytes(pcm.size)
            .build()
        val done = CountDownLatch(1)
        player.setNotificationMarkerPosition(pcm.size / 2 / channels)
        player.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) { done.countDown() }
            override fun onPeriodicNotification(track: AudioTrack?) {}
        })
        player.play()
        player.write(pcm, 0, pcm.size)
        done.await(pcm.size / (rate * channels * 2L) * 1000 + 1000, TimeUnit.MILLISECONDS)
        player.release()
    }
}
