/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

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
  val channels: Int
) {
  private val TAG: String = Log.tag(PcmRecorder::class.java)

  private val frameMs: Int = 20

  private var record: AudioRecord? = null

  private var job: Job? = null

  suspend fun start(onPcm: suspend (ByteArray) -> Unit) = coroutineScope {
    stop()

    val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO

    val minBuf = AudioRecord.getMinBufferSize(
      sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
    )

    val bytesPerFrame = sampleRate * frameMs / 1000 * channels * 2
    val bufferSize = max(minBuf, bytesPerFrame * 2)

    record = AudioRecord(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      sampleRate,
      channelConfig,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize
    )

    record?.startRecording()

    val buf = ByteArray(bytesPerFrame)

    job = launch {
      while (isActive) {
        val n = record!!.read(buf, 0, buf.size)

        if (n > 0) {
          onPcm(if (n == buf.size) buf else buf.copyOf(n))
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