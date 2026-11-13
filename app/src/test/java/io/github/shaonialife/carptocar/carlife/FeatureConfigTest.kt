package io.github.shaonialife.carptocar.carlife

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureConfigTest {
    @Test
    fun plaintextClientDoesNotRequestAnEncryptionHandshake() {
        // The engine starts an RSA/AES handshake for value 1 and disconnects on timeout.
        val fields = decodeFields(CarLifeProtocol.featureConfig())
        val entries = fields.filter { it.first == 2 }.map { decodeFields(it.second as ByteArray) }
        val capabilities = entries.associate { entry ->
            String(entry.single { it.first == 1 }.second as ByteArray, Charsets.UTF_8) to
                entry.single { it.first == 2 }.second
        }
        assertEquals(
            mapOf(
                "I_FRAME_INTERVAL" to 20L,
                "CONNECT_TYPE" to 5L,
                "DISABLE_RESOLUTION_OPTION" to 1L,
                "CONTENT_ENCRYPTION" to 0L,
                "MEDIA_LYRIC_SUPPORT" to 1L,
            ),
            capabilities,
        )
        assertEquals(entries.size.toLong(), fields.single { it.first == 1 }.second)
        assertEquals(entries.size, capabilities.size)
    }

    /** Independent protobuf wire reader; does not use the production encoder/decoder. */
    private fun decodeFields(bytes: ByteArray): List<Pair<Int, Any>> {
        val input = ByteArrayInputStream(bytes)
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                val byte = input.read()
                require(byte >= 0) { "Truncated varint" }
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
            }
            error("Invalid varint")
        }
        return buildList {
            while (input.available() > 0) {
                val tag = varint().toInt()
                val value: Any = when (tag and 7) {
                    0 -> varint()
                    2 -> {
                        val size = varint().toInt()
                        require(size >= 0 && size <= input.available())
                        ByteArray(size).also { assertEquals(size, input.read(it)) }
                    }
                    else -> error("Unexpected wire type")
                }
                add((tag ushr 3) to value)
            }
        }
    }
}
