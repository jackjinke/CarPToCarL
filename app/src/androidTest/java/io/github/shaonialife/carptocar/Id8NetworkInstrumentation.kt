package io.github.shaonialife.carptocar

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.launcher.settings.AutoConnectPrefs
import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONObject

/**
 * Test-APK transport only: the host peer, not Android state, adjudicates success.
 * No source events, resource ownership, callbacks, or media state are injected here.
 */
class Id8NetworkInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var exitCode = Activity.RESULT_CANCELED
        var failure: Throwable? = null
        try {
            val host = requireNotNull(arguments.getString("receiverHost"))
            val receiverPort = arguments.getString("receiverPort")!!.toInt()
            val driverPort = arguments.getString("driverPort")!!.toInt()
            require(receiverPort in 1..65535 && driverPort in 1..65535)
            // These are normal user preferences. A fresh isolated emulator is required by the host runner.
            AutoConnectPrefs.setEnabled(targetContext, false)
            CarLifeHijack.setEnabled(targetContext, true)
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ServerSocket(driverPort, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 300_000
                server.accept().use { connection ->
                    connection.soTimeout = 300_000
                    val reader = connection.getInputStream().bufferedReader()
                    val writer = connection.getOutputStream().bufferedWriter()
                    var running = true
                    while (running) {
                        val line = reader.readLine() ?: break
                        require(line.length <= 4096) { "Driver command too long" }
                        val command = JSONObject(line).getString("command")
                        val response = JSONObject().put("command", command)
                        try {
                            when (command) {
                                "connect" -> runOnMainSync {
                                    SessionController.connectTestSink(targetContext, host, receiverPort)
                                }
                                "disconnect" -> runOnMainSync { SessionController.disconnect(targetContext) }
                                // Expose only the listening address, never an app readiness/success oracle.
                                "transport" -> response.put("ctrlPort", SessionController.testSinkCtrlPort())
                                "stop" -> running = false
                                else -> error("Unsupported driver command: $command")
                            }
                            response.put("accepted", true)
                        } catch (error: Exception) {
                            response.put("accepted", false).put("error", error.toString())
                            throw error
                        } finally {
                            writer.write(response.toString())
                            writer.newLine()
                            writer.flush()
                        }
                    }
                }
            }
            exitCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            failure = error
            result.putString("driverError", error.stackTraceToString())
        } finally {
            try {
                runOnMainSync { SessionController.disconnect(targetContext) }
            } catch (error: Throwable) {
                exitCode = Activity.RESULT_CANCELED
                result.putString("cleanupError", error.stackTraceToString())
            }
            result.putString("scope", "driver completion only; network verdict belongs to host peer")
            finish(exitCode, result)
        }
        failure?.let { android.util.Log.e("Id8NetworkDriver", "Driver failed", it) }
    }
}
