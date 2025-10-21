/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.R
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class FHEGroupCall(private val room: Room) {
  val TAG: String = Log.tag(FHEGroupCall::class.java)

  private var recordJob: Job? = null

  private var record: AudioRecord? = null

  private val sampleRate: Int = 48000

  private val frameMs: Int = 20

  private val channels: Int = 1

  fun connect(recipient: Boolean = false)
  {
    val url = "wss://sandbox-rbbg1evh.livekit.cloud"
    val token = if (recipient)
      "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MDk3MDc4MywibmJmIjowLCJzdWIiOiJtb2JpbGUifQ.pTX2XmbwlCRXGL1Pbg2Lob9_-dfaKpEOGA7qfyusfwQ"
      else "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MDk3MDc4MywibmJmIjowLCJzdWIiOiJtb2JpbGUifQ.pTX2XmbwlCRXGL1Pbg2Lob9_-dfaKpEOGA7qfyusfwQ"

    runBlocking {
      room.connect(url = url, token = token)
    }

    val sendScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    val receiveScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    receiveScope.launch {
      room.localParticipant.events.collect { event ->
        Log.i(TAG, "LP event ${event}")
      }
    }

    val pcmReassembler = PcmReassembler()
    val player = PcmPlayer(receiveScope)

    sendScope.launch {
      player.start()
    }

    receiveScope.launch {
      room.events.collect { event ->
        if (event is RoomEvent.DataReceived && event.topic == "pcm") {
          val result = pcmReassembler.onChunk(event.data)

          if (result != null) {
            val (pcm, meta) = result
            player.enqueue(pcm, meta.sampleRate, meta.channels)
          }
        }

        if (event is RoomEvent.Disconnected) {
          Log.i(TAG, "Disconnected")

          sendScope.cancel()
        }
      }
    }

    // Comment/Uncomment to send actual audio
//    startRecording(sendScope) {
//      pcmFrame -> sendPcm(pcmFrame, sendScope)
//    }
  }

  fun startRecording(scope: CoroutineScope, onPcm: (ByteArray) -> Unit) {
    stopRecording()

    val minBuf = AudioRecord.getMinBufferSize(
      sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )

    val bytesPerFrame = sampleRate * frameMs / 1000 * channels * 2
    val bufferSize = max(minBuf, bytesPerFrame * 2)

    record = AudioRecord(
      MediaRecorder.AudioSource.MIC,
      sampleRate,
      if(channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
      AudioFormat.ENCODING_PCM_16BIT,
      bufferSize
    )

    record?.startRecording()

    recordJob = scope.launch {
      val buf = ByteArray(bytesPerFrame)

      while (isActive) {
        val n = record?.read(buf, 0, buf.size)

        if (n != null && n > 0) {
          onPcm(if (n == buf.size) buf else buf.copyOf(n))
        }
      }
    }
  }

  fun stopRecording() {
    recordJob?.cancel()
    recordJob = null

    record?.run {
      try { stop() } catch (_: Throwable) {}

      release()
    }

    record = null
  }

  fun sendPcm(pcm: ByteArray, scope: CoroutineScope) {
    val seq = AtomicInteger(0)

    val headerSize = 4 + 2 + 2 + 4 + 1 // seq, idx, total, sr, ch
    val mtu = 1300 // 1400 is MTU
    val maxChunk = mtu - headerSize
    val total = ((pcm.size + maxChunk - 1) / maxChunk).toShort()
    val seqId = seq.getAndIncrement()

    var off = 0
    var idx = 0

    while (off < pcm.size) {
      val take = min(maxChunk, pcm.size - off)
      val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(seqId)
        putShort(idx.toShort())
        putShort(total)
        putInt(sampleRate)
        put(1.toByte())
      }.array()

      val payload = ByteArray(header.size + take)
      System.arraycopy(header, 0, payload, 0, header.size)
      System.arraycopy(pcm, off, payload, header.size, take)

      scope.launch {
        if (room.state == Room.State.CONNECTED) {
          room.localParticipant.publishData(data = payload, reliability = DataPublishReliability.LOSSY, topic = "pcm")
        }
      }

      off += take
      idx++
    }
  }
}