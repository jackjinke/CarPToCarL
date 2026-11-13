package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.iap2.Iap2SenderSession
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure modes: FIN leaves a reusable socket; local close is reported remotely;
 * stale reader closes replacement; empty endpoint is counted as ECONNREFUSED.
 * These use real local TCP, not a substitute implementation of the transport.
 */
class AirPlayTransportTest {
    @Test(timeout = 20_000)
    fun `remote FIN invalidates transport once and reconnect opens another connection`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val closed = CountDownLatch(1)
            val failures = AtomicInteger()
            val client = client(server.localPort, onClosed = { failures.incrementAndGet(); closed.countDown() })
            val receiver = Thread {
                server.accept().use { peer ->
                    val input = peer.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    peer.getOutputStream().write("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }.apply { isDaemon = true; start() }
            try {
                client.fetchInfo(1_000)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertFalse(client.controlSessionAlive)
                assertEquals(1, failures.get())
                assertTrue(client.reconnect(1_000))
                server.accept().use { assertTrue(client.controlSessionAlive) }
            } finally {
                client.close()
                receiver.join(2_000)
            }
            assertEquals(1, failures.get())
        }
    }

    @Test(timeout = 20_000)
    fun `local close of active reader is silent and cannot clear replacement`() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val failures = AtomicInteger()
            val client = client(server.localPort, onClosed = { failures.incrementAndGet() })
            val requested = CountDownLatch(1)
            val first = Thread { client.fetchInfo(5_000) }.apply { isDaemon = true; start() }
            server.accept().use { peer ->
                val receiver = Thread {
                    val input = peer.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    requested.countDown()
                }.apply { isDaemon = true; start() }
                try {
                    assertTrue(requested.await(2, TimeUnit.SECONDS))
                    client.close()
                    assertTrue(client.reconnect(1_000))
                    server.accept().use {
                        first.join(2_000)
                        receiver.join(2_000)
                        assertFalse(first.isAlive)
                        assertTrue(client.controlSessionAlive)
                        assertEquals(0, failures.get())
                    }
                } finally { client.close() }
            }
        }
    }

    @Test(timeout = 20_000)
    fun `local pairing interruption does not suppress the next genuine unsupported reply`() {
        // Failure modes: Android-clock dependency; equal-millisecond/stale close attribution;
        // and counting an interrupted exchange as a receiver rejection.
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val requested = CountDownLatch(1)
            val locallyClosed = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            val remoteEnds = AtomicInteger()
            val client = client(server.localPort) { remoteEnds.incrementAndGet() }
            fun head(peer: java.net.Socket): List<String> {
                val reader = peer.getInputStream().bufferedReader()
                val lines = ArrayList<String>()
                while (true) {
                    val line = reader.readLine() ?: throw java.io.EOFException("pairing request header")
                    if (line.isEmpty()) break
                    lines += line
                }
                assertTrue(lines.first().startsWith("POST /pair-setup "))
                return lines
            }
            val receiver = Thread {
                try {
                    server.accept().use { peer ->
                        head(peer)
                        requested.countDown()
                        assertTrue(locallyClosed.await(5, TimeUnit.SECONDS))
                    }
                    server.accept().use { peer ->
                        val cseq = head(peer).first { it.startsWith("CSeq:") }.substringAfter(':').trim()
                        peer.getOutputStream().write(
                            "RTSP/1.0 404 Not Found\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                        )
                        assertTrue(finished.await(5, TimeUnit.SECONDS))
                    }
                } catch (failure: Throwable) {
                    error.compareAndSet(null, failure)
                }
            }.apply { isDaemon = true; start() }
            val worker = Thread {
                try {
                    client.runPairing(5_000)
                } catch (failure: Throwable) {
                    error.compareAndSet(null, failure)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(requested.await(5, TimeUnit.SECONDS))
                client.close()
                locallyClosed.countDown()
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                receiver.join(2_000)
                assertEquals(null, error.get())
                assertTrue(client.pairingUnsupported)
                assertFalse(client.pairingForbidden)
                assertEquals(null, client.pairingTrouble)
                assertEquals(0, remoteEnds.get())
            } finally {
                client.cancelled = true
                client.close()
                locallyClosed.countDown()
                finished.countDown()
                server.close()
                worker.join(2_000)
                receiver.join(2_000)
            }
        }
    }

    @Test(timeout = 20_000)
    fun `missing endpoint never invokes refusal wake hook`() {
        var wakes = 0
        val client = AirPlayClient(endpoint(0, emptyList()), {},
            onPortRefused = { wakes++ })
        try {
            client.runPairing(4_100)
            assertEquals(0, wakes)
            assertFalse(client.attemptedPairing)
        } finally { client.cancelled = true; client.close() }
    }

    private fun client(port: Int, onClosed: () -> Unit) = AirPlayClient(
        endpoint(port, listOf(localHost())), {}, onTransportClosed = { _, _ -> onClosed() })

    // Production rejects loopback advertisements. Use a real local interface without
    // weakening endpoint validation; the test server still runs in this JVM.
    private fun localHost(): String =
        java.net.NetworkInterface.getNetworkInterfaces().toList().asSequence()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress ?: error("Local TCP integration requires a non-loopback IPv4 interface")

    private fun endpoint(port: Int, hosts: List<String>) = Iap2SenderSession.CarPlayEndpoint(
        ssid = "loopback", passphrase = "", channel = 0, securityType = 2,
        airPlayPort = port, deviceIdentifier = null, publicKey = null, sourceVersion = null,
        ipAddresses = hosts)
}
