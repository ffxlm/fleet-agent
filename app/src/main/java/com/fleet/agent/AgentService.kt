package com.fleet.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

class AgentService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mqtt: MqttClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private var deviceId: String = ""
    private var relaunchBackoff = 30
    private var nextRelaunchAt = 0L
    private var rootOk = false

    // Last known state, written by monitorLoop and read by heartbeatLoop. Volatile
    // so the heartbeat never publishes a torn view while the monitor updates it.
    @Volatile private var lastPkg: String = "com.roblox.client"
    @Volatile private var lastPid: String = ""
    @Volatile private var lastRunning: Boolean = false

    companion object {
        private const val TAG = "FleetAgent"
        private const val CHANNEL = "fleet"
        private const val NOTIF_ID = 1
        private const val HB_FILE = "/sdcard/fleet/heartbeat.json"
        private const val HEARTBEAT_S = 30
        private const val PROBE_S = 15
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("fleet", MODE_PRIVATE)
        deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
        createChannel()
        startForeground(NOTIF_ID, notif("starting"))
        acquireWakeLock()
        connectMqtt()
        // SupervisorJob keeps the watchdog alive if loop() throws, but a dead loop
        // means no heartbeats while the connection still reconnects on Paho's own
        // thread — the device looks frozen. Wrap it so any crash restarts it.
        scope.launch {
            while (true) {
                try {
                    monitorLoop()
                } catch (e: Throwable) {
                    Log.e(TAG, "monitor loop crashed, restarting", e)
                }
                delay(5_000)
            }
        }
        scope.launch {
            while (true) {
                try {
                    heartbeatLoop()
                } catch (e: Throwable) {
                    Log.e(TAG, "heartbeat loop crashed, restarting", e)
                }
                delay(5_000)
            }
        }
        scope.launch { mqttWatchdog() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        // Graceful shutdown: tell the broker we're gone before disconnecting, so the
        // board shows offline immediately instead of waiting for the freshness window.
        try {
            mqtt?.publish("cf/$deviceId/up/status", "{\"online\":false}".toByteArray(), 1, true)
        } catch (_: Exception) {}
        try { mqtt?.disconnect() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fleet:agent").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "wakelock", e)
        }
    }

    // Cloud phones aggressively suspend background apps; Paho's auto-reconnect only
    // fires after a successful connect, so keep a slow watchdog that re-establishes.
    private suspend fun mqttWatchdog() {
        while (true) {
            delay(15_000)
            if (mqtt?.isConnected != true) connectMqtt()
        }
    }

    // ---- main loop: watchdog + heartbeat ----

    // Shell probing and liveness reporting run on separate coroutines on purpose.
    // A blocked `su`/`monkey` must never stop the heartbeat: a connected device with
    // no heartbeats is indistinguishable from a dead one on the board. So the
    // monitor may block as long as it likes; the heartbeat keeps ticking with the
    // last known state.
    private suspend fun monitorLoop() {
        var cycle = 0L
        while (true) {
            val pkg = prefs.getString("pkg", "com.roblox.client") ?: "com.roblox.client"
            lastPkg = pkg

            // Re-check root until granted: a fresh install (new deviceId) loses the old
            // su grant, and every shell action below silently no-ops without it.
            // Checked every few cycles, not every cycle — a blocked su prompt costs
            // the full exec timeout, and that latency shouldn't land on each probe.
            if (!rootOk && cycle % 5L == 0L) rootOk = exec("id -u").trim() == "0"

            val pid = exec("pidof $pkg").trim()
            lastPid = pid
            lastRunning = pid.isNotEmpty()

            // Backoff applies ONLY to relaunch attempts, never to the heartbeat.
            if (lastRunning) {
                relaunchBackoff = 30
                nextRelaunchAt = 0
            } else if (System.currentTimeMillis() >= nextRelaunchAt) {
                Log.i(TAG, "$pkg not running -> relaunch (root=$rootOk)")
                relaunch(pkg)
                relaunchBackoff = (relaunchBackoff * 2).coerceAtMost(300)
                nextRelaunchAt = System.currentTimeMillis() + relaunchBackoff * 1000L
            }

            cycle++
            delay(PROBE_S * 1000L)
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            publishHeartbeat(lastPkg, lastRunning, lastPid)
            delay(HEARTBEAT_S * 1000L)
        }
    }

    private fun relaunch(pkg: String) {
        exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    private fun forceStop(pkg: String) {
        exec("am force-stop $pkg")
    }

    // ---- heartbeat ----

    private fun publishHeartbeat(pkg: String, running: Boolean, pid: String) {
        val client = mqtt ?: return
        if (!client.isConnected) return

        val hb = File(HB_FILE)
        val extra = if (hb.exists()) hb.readText().trim() else ""

        val payload = buildString {
            append("{\"ts\":").append(System.currentTimeMillis())
            append(",\"pkg\":\"").append(pkg).append("\"")
            append(",\"running\":").append(running)
            append(",\"root\":").append(rootOk)
            if (pid.isNotEmpty()) append(",\"pid\":\"").append(pid).append("\"")
            if (extra.isNotEmpty()) append(",\"script\":").append(extra)
            append("}")
        }

        try {
            client.publish("cf/$deviceId/up/heartbeat", payload.toByteArray(), 1, false)
        } catch (e: Exception) {
            Log.e(TAG, "publish heartbeat", e)
        }
    }

    // ---- mqtt ----

    private fun connectMqtt() {
        val url = prefs.getString("broker", "") ?: ""
        if (url.isEmpty()) {
            Log.w(TAG, "no broker configured")
            return
        }
        // Reuse the existing client if we have one — reconnect() keeps the session.
        val existing = mqtt
        if (existing != null) {
            if (existing.isConnected) return
            try {
                existing.reconnect()
                Log.i(TAG, "mqtt reconnected")
                return
            } catch (e: Exception) {
                Log.w(TAG, "reconnect failed, rebuilding: ${e.message}")
                try { existing.close() } catch (_: Exception) {}
                mqtt = null
            }
        }
        try {
            val client = MqttClient(url, deviceId, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                // Persistent session (stable clientId = deviceId) keeps the down/cmd
                // subscription alive across drops and queues commands while offline.
                isCleanSession = false
                isAutomaticReconnect = true
                keepAliveInterval = 30
                connectionTimeout = 15
                val key = prefs.getString("key", "") ?: ""
                if (key.isNotEmpty()) userName = key
                setWill(
                    "cf/$deviceId/up/status",
                    "{\"online\":false}".toByteArray(),
                    1,
                    true
                )
            }
            // connectComplete fires on every (re)connect — this is what re-subscribes,
            // which a cleanSession=true client would silently lose.
            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "mqtt connected (reconnect=$reconnect)")
                    try {
                        client.subscribe("cf/$deviceId/down/cmd", 1) { _, msg ->
                            handleCommand(String(msg.payload))
                        }
                        client.publish(
                            "cf/$deviceId/up/status",
                            "{\"online\":true}".toByteArray(),
                            1,
                            true
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "post-connect setup", e)
                    }
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "mqtt lost: ${cause?.message}")
                }
            })
            client.connect(opts)
            mqtt = client
        } catch (e: Exception) {
            Log.e(TAG, "mqtt connect failed", e)
        }
    }

    private fun handleCommand(payload: String) {
        Log.i(TAG, "cmd: $payload")
        val pkg = prefs.getString("pkg", "com.roblox.client") ?: "com.roblox.client"
        when {
            payload.contains("restart") -> { forceStop(pkg); relaunch(pkg) }
            payload.contains("rejoin") -> relaunch(pkg)
            payload.contains("start") -> relaunch(pkg)
        }
    }

    // ---- root shell ----

    // Never let a shell call wedge the caller. `su` blocks forever if it decides to
    // prompt for a grant and stdin is attached to nothing, which would freeze the
    // heartbeat loop and make a live device look dead. Close stdin, read on a
    // helper thread, and hard-timeout the process.
    private fun exec(cmd: String, timeoutSec: Long = 10): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            try { p.outputStream.close() } catch (_: Exception) {}
            try { p.errorStream.close() } catch (_: Exception) {}

            val sb = StringBuilder()
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                        var line = r.readLine()
                        while (line != null) {
                            sb.append(line).append("\n")
                            line = r.readLine()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                Log.w(TAG, "exec timeout: $cmd")
                p.destroyForcibly()
                reader.interrupt()
                return ""
            }
            reader.join(1000)
            sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e)
            ""
        }
    }

    // ---- notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fleet", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return b.setContentTitle("Fleet Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
