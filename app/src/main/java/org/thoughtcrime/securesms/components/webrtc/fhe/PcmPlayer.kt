package org.thoughtcrime.securesms.components.webrtc.fhe

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

class PcmPlayer {
  private val queue = LinkedBlockingQueue<Pair<FloatArray, Meta>>()

  private var track: AudioTrack? = null

  private var job: Job? = null

  data class Meta(val sampleRate: Int, val channels: Int)

  @SuppressLint("NewApi")
  suspend fun start() = coroutineScope {
    job = launch {
      while (isActive) {
        val (pcm, meta) = queue.take()
        ensureTrack(meta)
        track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    track?.let {
      try { it.stop() } catch (_: Throwable) {}
      it.release()
    }
    track = null
    queue.clear()
  }

  fun enqueue(pcm: FloatArray, sampleRate: Int, channels: Int) {
    queue.offer(pcm to Meta(sampleRate, channels))
  }

  @SuppressLint("NewApi")
  private fun ensureTrack(meta: Meta) {
    val chOut = if (meta.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    val existing = track
    val needsNew = existing == null ||
      existing.sampleRate != meta.sampleRate ||
      existing.channelCount != meta.channels

    if (!needsNew) return

    existing?.let {
      try { it.stop() } catch (_: Throwable) {}
      it.release()
    }

    val minBuf = AudioTrack.getMinBufferSize(
      meta.sampleRate, chOut, AudioFormat.ENCODING_PCM_FLOAT
    )

    val bufferSize = max(minBuf, meta.sampleRate / 1000 * 90 * meta.channels * 4 * 2)

    track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(meta.sampleRate)
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setChannelMask(chOut)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(bufferSize)
      .build()
      .also { it.play() }
  }
}