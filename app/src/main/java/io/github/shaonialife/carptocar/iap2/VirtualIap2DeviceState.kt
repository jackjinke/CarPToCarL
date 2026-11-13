package io.github.shaonialife.carptocar.iap2

import java.util.TimeZone
import java.util.UUID

/** Post-authentication device facts, scoped to the negotiated wireless link. */
internal class VirtualIap2DeviceState(
    private val send: (Int, ByteArray) -> Unit,
    private val log: (String) -> Unit,
) {
    private var sessionId: String? = null

    fun reset() {
        sessionId = null
    }

    fun sendGreetings() {
        val id = sessionId ?: UUID.randomUUID().toString().uppercase().also { sessionId = it }
        send(0x4E0C, Iap2Csm.param(0, id.toByteArray(Charsets.UTF_8)))
        send(0x4E0A, Iap2Csm.param(0, "zh".toByteArray(Charsets.UTF_8)))
        send(0x4E0B, timeParams())
        send(0x4E09, Iap2Csm.param(0, "iPhone".toByteArray(Charsets.UTF_8)))
        // The old Audi-captured location/AUX constants were not portable: BMW/Buick
        // reset immediately after those requests. Identification support isn't layout proof.
        log("IAPLINK: post-auth device state sent ($id); no captured AUX/location requests")
    }

    private fun timeParams(): ByteArray {
        val now = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetMin = zone.getOffset(now) / 60_000
        val dst = if (zone.inDaylightTime(java.util.Date(now))) 1 else 0
        val seconds = ByteArray(8) { index -> (now / 1000L ushr ((7 - index) * 8)).toByte() }
        val offset = byteArrayOf((offsetMin shr 8).toByte(), offsetMin.toByte())
        return Iap2Csm.param(0, seconds) + Iap2Csm.param(1, offset) + Iap2Csm.param(2, byteArrayOf(dst.toByte()))
    }
}
