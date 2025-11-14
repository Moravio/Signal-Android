/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import kotlin.math.max

class PcmRecorder(
  val sampleRate: Int,
  val channels: Int,
  val samplesPerFrame: Int
) {
  companion object {
    private val TAG: String = Log.tag(PcmRecorder::class.java)
  }

  private var record: AudioRecord? = null

  private var job: Job? = null

  @SuppressLint("MissingPermission")
  suspend fun start(onAudioFrame: suspend (FloatArray) -> Unit) = coroutineScope {
    stop()

    val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    val minBuf = AudioRecord.getMinBufferSize(
      sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT
    )

    val bytesPerFrame = samplesPerFrame * channels * 4
    val bufferSize = max(minBuf, bytesPerFrame)

    Log.i(TAG, "Starting audio record [bufferSize=${bufferSize}]")

    record = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      sampleRate,
      channelConfig,
      AudioFormat.ENCODING_PCM_FLOAT,
      bufferSize
    )

    record?.startRecording()

    val buf = FloatArray(bufferSize / 4)

    job = launch {
      try {
        while (isActive) {
          val totalRead = record!!.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)

          if (totalRead > 0) {
            onAudioFrame(if (totalRead == buf.size) buf else buf.copyOf(totalRead))
          }
        }
      } finally {
        stop()
      }
    }
  }

  fun stop() {
    Log.i(TAG, "stop called")

    job?.cancel()
    record?.run {
      try { stop() } catch (_: Throwable) {}

      release()
    }

    record = null
  }
}