package org.thoughtcrime.securesms.components.webrtc.livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

private data class Acc(val total: Int, val parts: Array<ByteArray?>, var received: Int = 0)

class PcmReassembler {
  private val inbox = ConcurrentHashMap<Int, Acc>()

  /**
   * @return Pair<pcm, Meta> when a full message is ready, else null
   */
  fun onChunk(data: ByteArray): Pair<ByteArray, Meta>? {
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    val seqId = bb.int
    val idx = bb.short.toInt() and 0xFFFF
    val total = bb.short.toInt() and 0xFFFF
    val sampleRate = bb.int
    val channels = (bb.get().toInt() and 0xFF).coerceAtLeast(1)

    val audioStart = bb.position()
    val chunk = data.copyOfRange(audioStart, data.size)

    val acc = inbox.computeIfAbsent(seqId) { Acc(total, arrayOfNulls(total)) }

    if (idx < total && acc.parts[idx] == null) {
      acc.parts[idx] = chunk
      acc.received++
    }

    if (acc.received == acc.total) {
      val size = acc.parts.filterNotNull().sumOf { it.size }
      val pcm = ByteArray(size)
      var o = 0
      acc.parts.forEach { part ->
        val p = part ?: return@forEach
        System.arraycopy(p, 0, pcm, o, p.size)
        o += p.size
      }
      inbox.remove(seqId)
      return pcm to Meta(sampleRate, channels)
    }
    return null
  }

  data class Meta(val sampleRate: Int, val channels: Int)
}