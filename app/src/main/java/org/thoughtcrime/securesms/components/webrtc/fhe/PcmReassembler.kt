package org.thoughtcrime.securesms.components.webrtc.fhe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

private data class Acc(val total: Int, val parts: Array<ByteArray?>, var received: Int = 0)

class PcmReassembler {
  private val inbox = ConcurrentHashMap<Int, Acc>()

  /**
   * @return Pair<pcm, Meta> when a full message is ready, else null
   */
  fun onChunk(data: ByteArray): Pair<FloatArray, Meta>? {
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
      val decoded = FHEService.decrypt(pcm)
//      val decoded = bytesToFloats(pcm)
      return decoded to Meta(sampleRate, channels)
    }
    return null
  }

  private fun bytesToFloats(bytes: ByteArray): FloatArray {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val fb = bb.asFloatBuffer()
    val out = FloatArray(fb.remaining())
    fb.get(out)
    return out
  }

  private fun floatsToBytes(src: FloatArray): ByteArray {
    val bb = ByteBuffer.allocate(src.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    bb.asFloatBuffer().put(src, 0, src.size)
    return bb.array()
  }

  data class Meta(val sampleRate: Int, val channels: Int)
}