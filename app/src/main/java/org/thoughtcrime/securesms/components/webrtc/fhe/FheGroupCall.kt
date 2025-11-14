/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.components.webrtc.fhe

import android.content.Context
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.DataPublishReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class FheGroupCall(val context: Context, val groupId: String) {
  companion object {
    private val TAG: String = Log.tag(FheGroupCall::class.java)

    private const val SAMPLE_RATE = 11025

    private const val CHANNELS = 1

    private const val SAMPLES_PER_FRAME = 1024

    /** LiveKit data channel has 15KiB max messages size. We choose 14KiB to keep 1KiB for potential metadata being sent with payload */
    private const val DATA_PACKET_CHUNK_BYTES = 14000
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val room = LiveKit.create(context)

  private val recorder = PcmRecorder(SAMPLE_RATE, CHANNELS, SAMPLES_PER_FRAME)

  private val player = PcmPlayer()

  private val frameSeq = AtomicInteger(0)

  private val localDeviceState = LocalDeviceState(audioMuted = true)

  @Volatile private var mixerHandshakeDone = false

  data class TokenResponse(val url: String, val token: String)

  data class LocalDeviceState(
    var audioMuted: Boolean
  )

  @Serializable
  data class MixerAckMessage(
    val success: Boolean
  )

  @Serializable
  data class AudioMetaData(
    val id: Int,
    val chunkIdx: Int? = null,
    val totalChunks: Int? = null,
    val sampleRate: Int,
    val duration: Float,
    val channels: Int,
    val timestamp: Long,
    val speakers: List<String>
  )

  init {
    FHEService.loadKeys(context.assets)
  }

  fun connect()
  {
    Log.i(TAG, "Connecting to LiveKit [roomName=$groupId]")

    val (url, token) = getRoomToken()

    runBlocking {
      room.connect(url = url, token = token, ConnectOptions(audio = false, video = false))
    }

    Log.i(TAG, "Connected to Room [roomName=$groupId]")

    scope.launch {
      player.start()
    }

    scope.launch {
      while (isActive) {
        try {
          if (room.state == Room.State.CONNECTED) {
            room.localParticipant.publishData("Ping".toByteArray(Charsets.US_ASCII), reliability = DataPublishReliability.RELIABLE)
          }
        } catch (e: Exception) {
          Log.e(TAG, e.message)
        }

        delay(5000)
      }
    }

    scope.launch {
      room.events.collect { event ->
        if (event is RoomEvent.DataReceived && event.topic == "audio") {
          onAudioData(event.data)
        }

        if (event is RoomEvent.DataReceived && event.topic == "system") {
          onSystemData(event.data)
        }

        if (event is RoomEvent.Connected) {
          if (mixerConnected()) {
            sendSystemPacket()
          }
        }

        if (event is RoomEvent.ParticipantConnected) {
          if (mixerConnected()) {
            sendSystemPacket()
          }
        }

        if (event is RoomEvent.Disconnected) {
          player.stop()
          stopRecording()
        }
      }
    }
  }

  fun setOutgoingAudioMuted(muted: Boolean)
  {
    Log.i(TAG, "setOutgoingAudioMuted [muted=$muted]")

    localDeviceState.audioMuted = muted

    if (muted) {
      stopRecording();
    } else {
      startRecording()
    }
  }

  fun disconnect()
  {
    Log.i(TAG, "disconnect")

    scope.cancel()
    room.disconnect()
  }

  private fun getRoomToken(): TokenResponse
  {
    val client = OkHttpClient()

    val tokenUrl = HttpUrl.Builder()
      .scheme("https")
      .host("slimy-buses-arrive.loca.lt")
      .addPathSegment("getToken")
      .addQueryParameter("roomName", groupId)
      .addQueryParameter("identity", Recipient.self().aci.toString())
      .build()

    val request = Request.Builder()
      .url(tokenUrl)
      .build()

    val response: String = client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("Failed to fetch LiveKit token")
      }

      response.body.string()
    }

    return JsonUtils.fromJson(response, TokenResponse::class.java)
  }

  private fun requireMixer(): Participant.Identity
  {
    return room.remoteParticipants.keys.firstOrNull { it.value.startsWith("mixer") }!!
  }

  private fun mixerConnected(): Boolean
  {
    return room.remoteParticipants.keys.any { it.value.startsWith("mixer") }
  }

  private fun onSystemData(data: ByteArray)
  {
    val jsonString = data.toString(Charsets.UTF_8)
    val json = Json { ignoreUnknownKeys = true }

    val ackMessage = json.decodeFromString(MixerAckMessage.serializer(), jsonString)

    Log.i(TAG, "Received system packet [ackMessage=$ackMessage]")

    if (ackMessage.success) {
      mixerHandshakeDone = true
    }
  }

  private fun onAudioData(data: ByteArray)
  {
    try {
      val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val metaLen = buffer.int

      val metaBytes = data.copyOfRange(4, 4 + metaLen)
      val jsonString = metaBytes.toString(Charsets.UTF_8)

      val json = Json { ignoreUnknownKeys = true }
      val metadata = json.decodeFromString(AudioMetaData.serializer(), jsonString)

      val payload = data.copyOfRange(4 + metaLen, data.size)

      player.enqueue(
        FHEService.decrypt(payload),
        metadata.sampleRate,
        metadata.channels
      )
    } catch (e: Exception ) {
      Log.e(TAG, e.message)
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

  private suspend fun sendSystemPacket() {
    val pubKey = context.assets.open("keys/key_pub.bin").use { input ->
      input.readBytes()
    }

    val cryptoContext = context.assets.open("keys/crypto_context.bin").use { input ->
      input.readBytes()
    }

    val payload = ByteArray(pubKey.size + cryptoContext.size)

    System.arraycopy(pubKey, 0, payload, 0, pubKey.size)
    System.arraycopy(cryptoContext, 0, payload, pubKey.size, cryptoContext.size)

    var offset = 0;

    while (offset < payload.size) {
      val end = minOf(offset + DATA_PACKET_CHUNK_BYTES,  payload.size)
      val dataChunk = payload.sliceArray(offset until end)

      val packet = if (offset == 0)
          ByteBuffer.allocate(8 + dataChunk.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(pubKey.size)
            .putInt(cryptoContext.size)
            .put(dataChunk)
            .array()
          else
            ByteBuffer.allocate(dataChunk.size)
              .order(ByteOrder.LITTLE_ENDIAN)
              .put(dataChunk)
              .array()

      try {
        room.localParticipant.publishData(
          data = packet,
          reliability = DataPublishReliability.RELIABLE,
          topic = "system",
          identities = listOf(requireMixer())
        )
      } catch (e: Exception) {
        Log.e(TAG, e.message)
      }

      offset += DATA_PACKET_CHUNK_BYTES
    }
  }

  suspend fun sendAudioFrame(audioFrame: FloatArray) {
    val encryptedFrame = FHEService.encrypt(audioFrame)

    val totalChunks = (encryptedFrame.size + DATA_PACKET_CHUNK_BYTES - 1) / DATA_PACKET_CHUNK_BYTES
    val frameId = frameSeq.getAndIncrement()
    val timestamp = System.currentTimeMillis()

    var offset = 0
    var idx = 0

    while (offset < encryptedFrame.size) {
      val metadata = AudioMetaData(
        id = frameId,
        chunkIdx = idx,
        totalChunks = totalChunks,
        sampleRate = SAMPLE_RATE,
        duration = SAMPLES_PER_FRAME.toFloat() / SAMPLE_RATE.toFloat(),
        channels = CHANNELS,
        timestamp = timestamp,
        speakers = listOf()
      )

      val json = Json { encodeDefaults = true }
      val metadataBytes = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

      val end = minOf(offset + DATA_PACKET_CHUNK_BYTES, encryptedFrame.size)
      val dataChunk = encryptedFrame.sliceArray(offset until end)

      val packet = ByteBuffer.allocate(4 + metadataBytes.size + dataChunk.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(metadataBytes.size)
        .put(metadataBytes)
        .put(dataChunk)
        .array()

      try {
        if (room.state == Room.State.CONNECTED && mixerConnected() && mixerHandshakeDone) {
          room.localParticipant.publishData(
            data = packet,
            reliability = DataPublishReliability.RELIABLE,
            topic = "audio",
            identities = listOf(requireMixer())
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, e.message)
      }

      offset += DATA_PACKET_CHUNK_BYTES
      idx++
    }
  }
}