package org.thoughtcrime.securesms.components.webrtc.fhe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import org.signal.core.util.logging.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

class PcmPlayer {
  companion object {
    private val TAG: String = Log.tag(PcmPlayer::class.java)
  }

  private val channel = Channel<Pair<FloatArray, Meta>>(
    capacity = 10,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
  )

  private var track: AudioTrack? = null

  private var job: Job? = null

  data class Meta(val sampleRate: Int, val channels: Int)

  suspend fun start() = coroutineScope {
    Log.i(TAG, "Starting player")

    job = launch {
      try {
        for ((audioData, meta) in channel) {
          ensureTrack(meta)

          track?.write(audioData, 0, audioData.size, AudioTrack.WRITE_BLOCKING)
        }
      } finally {
        stop()
      }
    }
  }

  fun stop() {
    Log.i(TAG, "Stopping player")

    job?.cancel()
    job = null
    track?.let {
      try { it.stop() } catch (_: Throwable) {}
      it.release()
    }
    track = null

    channel.close()
  }

  fun enqueue(audioData: FloatArray, sampleRate: Int, channels: Int) {
    channel.trySend(audioData to Meta(sampleRate, channels))
  }

  private fun ensureTrack(meta: Meta) {
    val channelConfig = if (meta.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

    val existing = track
    val needsNew = existing == null ||
      existing.sampleRate != meta.sampleRate ||
      existing.channelCount != meta.channels

    if (!needsNew) return

    existing?.let {
      try { it.stop() } catch (_: Throwable) {}
      it.release()
    }

    val bufferSize = AudioTrack.getMinBufferSize(
      meta.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT
    )

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
          .setChannelMask(channelConfig)
          .build()
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(bufferSize)
      .build()
      .also { it.play() }
  }
}