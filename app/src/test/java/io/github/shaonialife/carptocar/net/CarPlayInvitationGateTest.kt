package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Token-consumption seam only; these do not claim Android discovery/socket coverage. */
class CarPlayInvitationGateTest {
    private fun invitation(
        serial: Long = 1L,
        network: Long? = 138L,
        receivedAt: Long = 1_000L,
    ) = CarPlayInvitationGate.Invitation(
        serial = serial,
        receiverId = "18591430405809",
        peerHost = "172.16.222.97",
        localHost = "172.16.222.105",
        networkHandle = network,
        receivedAtMillis = receivedAt,
    )

    private fun consume(
        gate: CarPlayInvitationGate,
        receiver: String = "10:E8:A7:9A:EE:B1",
        peers: List<String> = listOf("172.16.222.97"),
        locals: List<String> = listOf("172.16.222.105"),
        network: Long? = 138L,
        now: Long = 2_000L,
    ) = gate.consume(receiver, peers, locals, network, now)

    @Test
    fun matchingDecimalReceiverInvitationIsConsumedOnce() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertEquals(1L, consume(gate)?.serial)
        assertNull(consume(gate))
    }

    @Test
    fun lifetimeBoundaryIsInclusiveAndExpiredInvitationCannotBeRevived() {
        val atBoundary = CarPlayInvitationGate()
        atBoundary.offer(invitation())
        assertEquals(1L, consume(atBoundary, now = 31_000L)?.serial)

        val expired = CarPlayInvitationGate()
        expired.offer(invitation())
        assertNull(consume(expired, now = 31_001L))
        assertNull(consume(expired, now = 31_000L))
    }

    @Test
    fun receiverChangeDropsInvitationEvenWhenPeerAndNetworkMatch() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertNull(consume(gate, receiver = "10:E8:A7:9A:EE:B2"))
        assertNull(consume(gate))
    }

    @Test
    fun differentPeerCannotUseInvitationForSameReceiver() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertNull(consume(gate, peers = listOf("172.16.222.98")))
        assertNull(consume(gate))
    }

    @Test
    fun networkHandleChangeDropsInvitationEvenWithReusedAddresses() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertNull(consume(gate, network = 139L))
        assertNull(consume(gate))
    }

    @Test
    fun acceptedLocalAddressMustBelongToCurrentNetwork() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertNull(consume(gate, locals = listOf("172.16.222.106")))
        assertNull(consume(gate))
    }

    @Test
    fun earlyUnknownNetworkInvitationSurvivesUntilActualBinding() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation(network = null))

        assertNull(consume(gate, network = null, locals = emptyList()))
        assertNull(consume(gate, network = null))
        assertNull(consume(gate, locals = emptyList()))
        assertEquals(1L, consume(gate)?.serial)
        assertNull(consume(gate))
    }

    @Test
    fun knownNetworkInvitationAlsoSurvivesTemporarilyUnavailableBinding() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation())

        assertNull(consume(gate, network = null))
        assertNull(consume(gate, locals = emptyList()))
        assertEquals(1L, consume(gate)?.serial)
    }

    @Test
    fun unknownNetworkAtReceiptStillRequiresExactAcceptedLocalAddress() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation(network = null))

        assertNull(consume(gate, network = null, locals = emptyList()))
        assertNull(consume(gate, locals = listOf("172.16.222.106")))
        assertNull(consume(gate))
    }

    @Test
    fun waitingForBindingDoesNotExtendInvitationLifetime() {
        val gate = CarPlayInvitationGate()
        gate.offer(invitation(network = null))

        assertNull(consume(gate, network = null, locals = emptyList(), now = 31_000L))
        assertNull(consume(gate, now = 31_001L))
    }

    @Test
    fun delayedOlderDiscardCannotEraseNewerPendingInvitation() {
        val gate = CarPlayInvitationGate()
        val old = invitation(serial = 1L)
        gate.offer(old)
        gate.offer(invitation(serial = 2L))

        gate.discard(old)

        assertEquals(2L, consume(gate)?.serial)
        assertNull(consume(gate))
    }

    @Test
    fun discardingHealthyDuplicateFencesItAndDelayedOlderCallbacks() {
        val gate = CarPlayInvitationGate()
        val old = invitation(serial = 1L)
        val duplicate = invitation(serial = 2L)
        gate.offer(old)
        gate.discard(duplicate)
        gate.offer(duplicate)
        gate.offer(old)

        assertNull(consume(gate))
        gate.offer(invitation(serial = 3L))
        assertEquals(3L, consume(gate)?.serial)
    }

    @Test
    fun discardingCurrentPendingInvitationConsumesItsAuthorization() {
        val gate = CarPlayInvitationGate()
        val request = invitation()
        gate.offer(request)

        gate.discard(request)
        gate.offer(request)

        assertNull(consume(gate))
    }

    @Test
    fun clearingPendingStateDoesNotAllowOldSerialReplay() {
        val gate = CarPlayInvitationGate()
        val old = invitation(serial = 1L)
        gate.offer(old)
        gate.clear()
        gate.offer(old)

        assertNull(consume(gate))
        gate.offer(invitation(serial = 2L))
        assertEquals(2L, consume(gate)?.serial)
    }

    @Test
    fun consumedInvitationCannotBeRenewedByReofferingSameSerial() {
        val gate = CarPlayInvitationGate()
        val request = invitation()
        gate.offer(request)
        assertEquals(1L, consume(gate)?.serial)

        gate.offer(request.copy(receivedAtMillis = 2_000L))

        assertNull(consume(gate, now = 3_000L))
    }
}
