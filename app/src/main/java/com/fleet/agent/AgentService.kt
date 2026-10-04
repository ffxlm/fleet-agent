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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

class AgentService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var mqtt: MqttClient? = null
    private lateinit var prefs: SharedPreferences
    private var deviceId: String = ""
    private var delaySec = 30

    companion object {
        private const val TAG = "FleetAgent"
        private const val CHANNEL = "fleet"
        private const val NOTIF_ID = 1
        private const val HB_FILE = "/sdcard/fleet/heartbeat.json"
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
        connectMqtt()
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { mqtt?.disconnect() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    // ---- main loop: watchdog + heartbeat ----

    private suspend fun loop() {
        while (true) {
            val pkg = prefs.getString("pkg", "com.roblox.client") ?: "com.roblox.client"
            val running = isRunning(pkg)

            if (!running) {
                Log.i(TAG, "$pkg not running -> relaunch")
                relaunch(pkg)
                delaySec = (delaySec * 2).coerceAtMost(300)
            } else {
                delaySec = 30
            }

            publishHeartbeat(pkg, running)
            delay(delaySec * 1000L)
        }
    }

    private fun isRunning(pkg: String): Boolean {
        return exec("pidof $pkg").trim().isNotEmpty()
    }

    private fun relaunch(pkg: String) {
        exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }

    private fun forceStop(pkg: String) {
        exec("am force-stop $pkg")
    }

    // ---- heartbeat ----

    private fun publishHeartbeat(pkg: String, running: Boolean) {
        val client = mqtt ?: return
        if (!client.isConnected) return

        val hb = File(HB_FILE)
        val extra = if (hb.exists()) hb.readText().trim() else ""

        val payload = buildString {
            append("{\"ts\":").append(System.currentTimeMillis())
            append(",\"pkg\":\"").append(pkg).append("\"")
            append(",\"running\":").append(running)
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
        try {
            val client = MqttClient(url, deviceId, MemoryPersistence())
            val opts = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                val key = prefs.getString("key", "") ?: ""
                if (key.isNotEmpty()) userName = key
                setWill(
                    "cf/$deviceId/up/status",
                    "{\"online\":false}".toByteArray(),
                    1,
                    true
                )
            }
            client.connect(opts)
            client.subscribe("cf/$deviceId/down/cmd", 1) { _, msg ->
                handleCommand(String(msg.payload))
            }
            client.publish(
                "cf/$deviceId/up/status",
                "{\"online\":true}".toByteArray(),
                1,
                true
            )
            mqtt = client
            Log.i(TAG, "mqtt connected")
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

    private fun exec(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = r.readLine()
                }
            }
            p.waitFor()
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
