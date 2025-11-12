/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.signal.core.util.logging.Log
import kotlin.math.max

class PcmRecorder(
  val sampleRate: Int,
  val channels: Int,
  val frameDurationMs: Int
) {
  private val TAG: String = Log.tag(PcmRecorder::class.java)

  private var record: AudioRecord? = null

  private var job: Job? = null

  @RequiresApi(Build.VERSION_CODES.M)
  suspend fun start(onAudioFrame: suspend (FloatArray) -> Unit) = coroutineScope {
    stop()

    val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    val minBuf = AudioRecord.getMinBufferSize(
      sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT
    )

    val samplesPerFrame = sampleRate / 1000 * frameDurationMs
    val bytesPerFrame = samplesPerFrame * channels * 4
    val bufferSize = max(minBuf, bytesPerFrame * 2)

    Log.i(TAG, "Starting audio record [bufferSizeInBytes=${bytesPerFrame}]")

    record = AudioRecord(
      MediaRecorder.AudioSource.MIC,
      sampleRate,
      channelConfig,
      AudioFormat.ENCODING_PCM_FLOAT,
      bufferSize
    )

    record?.startRecording()

    val buf = FloatArray(samplesPerFrame)

    job = launch {
      while (isActive) {
        val totalRead = record!!.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)

//        Log.d(TAG, "AudioRecord read $totalRead bytes")

        if (totalRead > 0) {
          onAudioFrame(buf)
        }
      }
    }
  }

  fun stop() {
    job?.cancel()
    record?.run {
      try { stop() } catch (_: Throwable) {}

      release()
    }

    record = null
  }
}