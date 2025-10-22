/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.RoomException
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class FheGroupCall(val context: Context) {
  private val TAG: String = Log.tag(FheGroupCall::class.java)

  private val sampleRate = 48000

  private val channels = 1

  private val room = LiveKit.create(context)

  private val recorder = PcmRecorder(sampleRate, channels)

  private val pcmReassembler = PcmReassembler()

  private val player = PcmPlayer()

  private val scope = CoroutineScope(Dispatchers.IO)

  val seq = AtomicInteger(0)

  fun connect(recipient: Boolean = false)
  {
    val url = "wss://sandbox-rbbg1evh.livekit.cloud"
    val token = if (recipient)
      "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MTEyNzQzNSwibmJmIjowLCJzdWIiOiJtb2JpbGUifQ.mh14M4djGs5eDs7ksKpQZOECZmdHeNq0WeeSX2nPfLw"
      else "eyJhbGciOiJIUzI1NiJ9.eyJ2aWRlbyI6eyJyb29tSm9pbiI6dHJ1ZSwicm9vbSI6Im1vcmF2aW8tc2lnbmFsIn0sImlzcyI6IkFQSUs3cWhScnFHY2ttUyIsImV4cCI6MTc2MTEyNzQzNSwibmJmIjowLCJzdWIiOiJtb2JpbGUyIn0.77IkIYpde8wakCBf6-gvRI3AuAbytfM4flNg1t-029M"

    runBlocking {
      room.connect(url = url, token = token)
    }

    startRecording()

    scope.launch {
      launch {
        player.start()
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

          player.stop()
          stopRecording()
        }
      }
    }
  }

  private fun startRecording()
  {
    scope.launch {
      recorder.start { pcm -> sendPcm(pcm) }
    }
  }

  private fun stopRecording()
  {
    recorder.stop()
  }

  fun setOutgoingAudioMuted(muted: Boolean)
  {
    if (muted) {
      stopRecording();
    } else {
      startRecording()
    }
  }

  fun disconnect()
  {
    room.disconnect()
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
        put(channels.toByte())
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