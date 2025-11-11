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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.JsonUtils
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class FheGroupCall(val context: Context, val groupId: String) {
  private val TAG: String = Log.tag(FheGroupCall::class.java)

  private val sampleRate = 11025

  private val channels = 1

  private val frameDurationMs = 90

  private val room = LiveKit.create(context)

  private val recorder = PcmRecorder(sampleRate, channels, frameDurationMs)

  private val pcmReassembler = PcmReassembler()

  private val player = PcmPlayer()

  private val scope = CoroutineScope(Dispatchers.IO)

  val frameSeq = AtomicInteger(0)

  data class TokenResponse(val url: String, val token: String)

  init {
    FHEService.loadKeys(context.assets)
  }

  fun connect()
  {
    val client = OkHttpClient()

    val tokenUrl = HttpUrl.Builder()
      .scheme("https")
      .host("dark-trams-like.loca.lt")
      .addPathSegment("getToken")
      .addQueryParameter("roomName", groupId)
      .addQueryParameter("identity", Recipient.self().aci.toString())
      .build()

    val request = Request.Builder()
      .url(tokenUrl)
      .build()

    val response: String = client.newCall(request).execute().use { response ->
      if (!response.isSuccessful || response.body == null) {
        throw IOException("Failed to fetch LiveKit token")
      }

      response.body!!.string()
    }

    val (url, token) = JsonUtils.fromJson(response, TokenResponse::class.java)

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
        if (event is RoomEvent.DataReceived && event.topic == "audio") {
          Log.i(TAG, "Received audio data ${event.data.size}")

          val result = pcmReassembler.onChunk(event.data)

          if (result != null) {
            val (pcm, meta) = result

            player.enqueue(pcm, meta.sampleRate, meta.channels)
          }
        }

        if (event is RoomEvent.ParticipantConnected) {
          val pubKey = context.assets.open("keys/key_pub.bin").use { input ->
            input.readBytes()
          }

          val cryptoContext = context.assets.open("keys/crypto_context.bin").use { input ->
            input.readBytes()
          }

          val packet = ByteBuffer.allocate(8 + cryptoContext.size + pubKey.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(pubKey.size)
            .putInt(cryptoContext.size)
            .put(pubKey)
            .put(cryptoContext)
            .array()

          Log.i(TAG, "Sending system packet of size ${packet.size}")

          room.localParticipant.publishData(
            data = packet,
            reliability = DataPublishReliability.RELIABLE,
            topic = "system"
          )
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
      recorder.start { audioFrame -> sendAudioFrame(audioFrame) }
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

  private fun floatsToBytes(src: FloatArray): ByteArray {
    val bb = ByteBuffer.allocate(src.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bb.asFloatBuffer().put(src, 0, src.size)
    return bb.array()
  }

  @Serializable
  data class AudioMetaData(
    val id: Int,
    val chunkIdx: Int,
    val totalChunks: Int,
    val sampleRate: Int,
    val duration: Int,
    val channels: Int,
    val timestamp: Long,
    val speakers: List<String>
  )

  suspend fun sendAudioFrame(audioFrame: FloatArray) {
    val encryptedFrame = FHEService.encrypt(audioFrame)
//    val encryptedFrame = floatsToBytes(pcm);

    // Livekit has 15KiB max data packet size
    val maxChunkBytes = 14000
    val totalChunks = (encryptedFrame.size + maxChunkBytes - 1) / maxChunkBytes
    val frameId = frameSeq.getAndIncrement()

    var offset = 0
    var idx = 0

    while (offset < encryptedFrame.size) {
      val metadata = AudioMetaData(
        id = frameId,
        chunkIdx = idx,
        totalChunks = totalChunks,
        sampleRate = sampleRate,
        duration = frameDurationMs,
        channels = channels,
        timestamp = System.currentTimeMillis(),
        speakers = listOf()
      )

      val json = Json { encodeDefaults = true }
      val metadataBytes = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

      val end = minOf(offset + maxChunkBytes, encryptedFrame.size)
      val dataChunk = encryptedFrame.sliceArray(offset until end)

      val packet = ByteBuffer.allocate(4 + metadataBytes.size + dataChunk.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(metadataBytes.size)
        .put(metadataBytes)
        .put(dataChunk)

      try {
        if (room.state == Room.State.CONNECTED) {
          room.localParticipant.publishData(
            data = packet.array(),
            reliability = DataPublishReliability.RELIABLE,
            topic = "audio"
          )
        }
      } catch (e: RoomException) {
        Log.e(TAG, e.message)
      }

      offset += maxChunkBytes
      idx++
    }
  }
}