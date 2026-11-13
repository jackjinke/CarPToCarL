package io.github.shaonialife.carptocar.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock

/** Snapshot evidence only: a BONDED → NONE → BONDED transition between samples is not observable. */
internal object BtConnectionDiagnostics {
    private data class AttemptBond(val address: String?, val bond: Int?, val at: Long)

    @Volatile
    private var lastAttempt: AttemptBond? = null

    fun error(error: Throwable): String =
        "${error.javaClass.name}: ${error.message?.replace('\n', ' ')?.replace('\r', ' ')?.take(240) ?: "<no message>"}"

    @SuppressLint("MissingPermission")
    fun state(context: Context, device: BluetoothDevice?, attempt: Boolean = false, incoming: Boolean = false): String {
        if (device == null) return "bond=unavailable(no-peer) adapter=unavailable(no-peer-snapshot) discovery=unavailable(no-peer-snapshot) bondHistory=not-observed"
        val bond = runCatching { device.bondState }
        val address = runCatching { device.address }.getOrNull()
        val previous = lastAttempt
        if (attempt) lastAttempt = AttemptBond(address, bond.getOrNull(), SystemClock.elapsedRealtime())
        val comparison =
            if (incoming) {
                if (previous == null || address == null || previous.address != address) {
                    " incomingBondChangedSinceLastAttempt=unavailable(no-matching-snapshot)"
                } else {
                    val changed =
                        if (bond.isFailure || previous.bond == null) "unknown" else (bond.getOrNull() != previous.bond).toString()
                    " incomingBondChangedSinceLastAttempt=$changed previousBond=${previous.bond?.let(::bondName) ?: "unavailable"}" +
                        " previousSampleAgeMs=${SystemClock.elapsedRealtime() - previous.at}"
                }
            } else {
                ""
            }
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }
        val adapterState =
            adapter.fold(
                onSuccess = { value ->
                    if (value == null) {
                        "adapter=unavailable(null) discovery=unavailable(no-adapter)"
                    } else {
                        "adapter=${field { adapterName(value.state) }} discovery=${field { value.isDiscovering.toString() }}"
                    }
                },
                onFailure = { "adapter=unavailable(${error(it)}) discovery=unavailable(no-adapter)" },
            )
        return "bond=${bond.fold(::bondName) { "unavailable(${error(it)})" }} $adapterState$comparison" +
            " bondHistory=not-observed(snapshot-only;intermediate-repair-unknown)"
    }

    private inline fun field(read: () -> String): String =
        try {
            read()
        } catch (failure: Exception) {
            "unavailable(${error(failure)})"
        }

    private fun bondName(state: Int): String =
        when (state) {
            BluetoothDevice.BOND_NONE -> "NONE($state)"
            BluetoothDevice.BOND_BONDING -> "BONDING($state)"
            BluetoothDevice.BOND_BONDED -> "BONDED($state)"
            else -> "UNKNOWN($state)"
        }

    private fun adapterName(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF($state)"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON($state)"
            BluetoothAdapter.STATE_ON -> "ON($state)"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF($state)"
            else -> "UNKNOWN($state)"
        }
}
