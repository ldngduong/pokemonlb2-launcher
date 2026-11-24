package com.legendblack2.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object FastBpsPatcher {

    private fun decodeNumber(data: ByteArray, offset: IntArray): Long {
        var valResult = 0L
        var shift = 1L
        while (true) {
            val b = data[offset[0]].toInt() and 0xFF
            offset[0]++
            valResult += (b and 0x7F) * shift
            if ((b and 0x80) != 0) break
            shift = shift shl 7
            valResult += shift
        }
        return valResult
    }

    fun applyPatch(src: ByteArray, patch: ByteArray): ByteArray {
        if (patch.size < 16 || patch[0] != 'B'.code.toByte() || patch[1] != 'P'.code.toByte() || patch[2] != 'S'.code.toByte() || patch[3] != '1'.code.toByte()) {
            throw IllegalArgumentException("Invalid BPS patch header")
        }

        val offset = intArrayOf(4)
        decodeNumber(patch, offset) // sLen
        val tLen = decodeNumber(patch, offset).toInt()
        val mLen = decodeNumber(patch, offset).toInt()
        offset[0] += mLen // Skip metadata

        val target = ByteArray(tLen)
        var outPos = 0
        var srcPos = 0
        val patchEnd = patch.size - 12

        while (offset[0] < patchEnd) {
            val data = decodeNumber(patch, offset)
            val action = (data and 3).toInt()
            val length = ((data shr 2) + 1).toInt()

            when (action) {
                0 -> { // SourceRead
                    System.arraycopy(src, srcPos, target, outPos, length)
                    outPos += length
                    srcPos += length
                }
                1 -> { // TargetRead
                    System.arraycopy(patch, offset[0], target, outPos, length)
                    outPos += length
                    offset[0] += length
                }
                2 -> { // SourceCopy
                    val offsetData = decodeNumber(patch, offset)
                    val diff = if ((offsetData and 1L) == 0L) (offsetData shr 1).toInt() else -((offsetData shr 1).toInt())
                    srcPos += diff
                    System.arraycopy(src, srcPos, target, outPos, length)
                    outPos += length
                    srcPos += length
                }
                3 -> { // TargetCopy
                    val offsetData = decodeNumber(patch, offset)
                    val diff = if ((offsetData and 1L) == 0L) (offsetData shr 1).toInt() else -((offsetData shr 1).toInt())
                    var tCopy = outPos + diff
                    for (i in 0 until length) {
                        target[outPos++] = target[tCopy++]
                    }
                }
            }
        }

        val footer = ByteBuffer.wrap(patch, patchEnd, 12).order(ByteOrder.LITTLE_ENDIAN)
        val sCrcExpected = footer.int.toLong() and 0xFFFFFFFFL
        val tCrcExpected = footer.int.toLong() and 0xFFFFFFFFL

        val crc = CRC32()
        crc.update(src)
        if (crc.value != sCrcExpected) {
            throw IllegalStateException("Bản ROM gốc không khớp checksum!")
        }

        crc.reset()
        crc.update(target)
        if (crc.value != tCrcExpected) {
            throw IllegalStateException("Bản ROM sau khi vá không khớp checksum!")
        }

        return target
    }
}
