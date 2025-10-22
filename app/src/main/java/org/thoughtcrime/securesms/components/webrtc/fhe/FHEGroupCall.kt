/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.RoomException
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class FHEGroupCall(val context: Context) {
  val TAG: String = Log.tag(FHEGroupCall::class.java)

  private var record: AudioRecord? = null

  private val sampleRate: Int = 48000

  private val frameMs: Int = 20

  private val channels: Int = 1

  private val room: Room

  val seq = AtomicInteger(0)

  init {
    room = LiveKit.create(context)
  }

  fun connect(recipient: Boolean = false)
  {
    val url = "wss://sandbox-rbbg1evh.livekit.cloud"
    val token = if (recipient)
      "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MTEyNzQzNSwibmJmIjowLCJzdWIiOiJtb2JpbGUifQ.mh14M4djGs5eDs7ksKpQZOECZmdHeNq0WeeSX2nPfLw"
      else "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MTEyNzQzNSwibmJmIjowLCJzdWIiOiJtb2JpbGUyIn0.77IkIYpde8wakCBf6-gvRI3AuAbytfM4flNg1t-029M"

    runBlocking {
      room.connect(url = url, token = token)
    }

    val scope = CoroutineScope(Dispatchers.IO)

    val pcmReassembler = PcmReassembler()
    val player = PcmPlayer()

    scope.launch {
      launch {
        player.start()
      }

      val job = launch {
        startRecording {
            pcmFrame -> sendPcm(pcmFrame)
        }
      }

      launch {
        while (isActive) {
          try {
            if (room.state == Room.State.CONNECTED) {
              room.localParticipant.publishData("Ping".toByteArray(Charsets.US_ASCII), reliability = DataPublishReliability.RELIABLE)
            }
          } catch (e: RoomException) {
            Log.e(TAG, e.message)
          }

          delay(5000)
        }
      }

      room.events.collect { event ->
        if (event is RoomEvent.DataReceived && event.topic == "pcm") {
          Log.i(TAG, "Received PCM")

          val result = pcmReassembler.onChunk(event.data)

          if (result != null) {
            val (pcm, meta) = result
            player.enqueue(pcm, meta.sampleRate, meta.channels)
          }
        }

        if (event is RoomEvent.Disconnected) {
          Log.i(TAG, "Disconnected")

          job.cancel()
          player.stop()
          stopRecording()
        }
      }
    }
  }

  fun disconnect()
  {
    room.disconnect()
  }

  suspend fun startRecording(onPcm: suspend (ByteArray) -> Unit) = coroutineScope {
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

    val buf = ByteArray(bytesPerFrame)

    launch {
      while (isActive) {
        val n = record?.read(buf, 0, buf.size)

        if (n != null && n > 0) {
          onPcm(if (n == buf.size) buf else buf.copyOf(n))
        }
      }
    }
  }

  fun stopRecording() {
    record?.run {
      try { stop() } catch (_: Throwable) {}

      release()
    }

    record = null
  }

  suspend fun sendPcm(pcm: ByteArray) {
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

      try {
        if (room.state == Room.State.CONNECTED) {
          Log.i(TAG, "Sending PCM")
          room.localParticipant.publishData(data = payload, reliability = DataPublishReliability.LOSSY, topic = "pcm")
        }
      } catch (e: RoomException) {
        Log.e(TAG, e.message)
      }

      off += take
      idx++
    }
  }
}