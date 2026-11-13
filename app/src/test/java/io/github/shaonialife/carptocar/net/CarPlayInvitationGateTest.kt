package io.github.shaonialife.carptocar.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 车机 `_carplay-ctrl._tcp` 连接邀请门闸单测（使用纯测试合成 MAC 与地址）。 */
class CarPlayInvitationGateTest {
    private fun invitation(
        serial: Long = 1L,
        network: Long? = 138L,
        receivedAt: Long = 1_000L,
    ) = CarPlayInvitationGate.Invitation(
        // 0x021122334455 = 2272611484757（十进制设备标识与冒号十六进制归一化一致）
        serial = serial,
        receiverId = "2272611484757",
        peerHost = "198.51.100.10",
        localHost = "198.51.100.20",
        networkHandle = network,
        receivedAtMillis = receivedAt,
    )

    private fun consume(
        gate: CarPlayInvitationGate,
        receiver: String = "02:11:22:33:44:55",
        peers: List<String> = listOf("198.51.100.10"),
        locals: List<String> = listOf("198.51.100.20"),
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
    fun receiverOrPeerOrNetworkMismatchDropsInvitation() {
        val byReceiver = CarPlayInvitationGate()
        byReceiver.offer(invitation())
        assertNull(consume(byReceiver, receiver = "02:11:22:33:44:56"))
        assertNull(consume(byReceiver))

        val byPeer = CarPlayInvitationGate()
        byPeer.offer(invitation())
        assertNull(consume(byPeer, peers = listOf("198.51.100.11")))
        assertNull(consume(byPeer))

        val byNetwork = CarPlayInvitationGate()
        byNetwork.offer(invitation())
        assertNull(consume(byNetwork, network = 139L))
        assertNull(consume(byNetwork))

        val byLocal = CarPlayInvitationGate()
        byLocal.offer(invitation())
        assertNull(consume(byLocal, locals = listOf("198.51.100.21")))
        assertNull(consume(byLocal))
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
    fun discardAndClearFenceStaleSerialReplay() {
        val gate = CarPlayInvitationGate()
        val old = invitation(serial = 1L)
        val duplicate = invitation(serial = 2L)
        gate.offer(old)
        gate.discard(duplicate)
        gate.offer(duplicate)
        gate.offer(old)
        assertNull(consume(gate))

        val third = invitation(serial = 3L)
        gate.offer(third)
        gate.clear()
        gate.offer(third)
        assertNull(consume(gate))

        gate.offer(invitation(serial = 4L))
        assertEquals(4L, consume(gate)?.serial)
    }
}
