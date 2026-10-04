package com.fleet.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("fleet", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val broker = EditText(this).apply {
            hint = "broker url  (wss://host:8084/mqtt  or  tcp://host:1883)"
            setText(prefs.getString("broker", "") ?: "")
        }
        val key = EditText(this).apply {
            hint = "device key"
            setText(prefs.getString("key", "") ?: "")
        }
        val pkg = EditText(this).apply {
            hint = "target package"
            setText(prefs.getString("pkg", "com.roblox.client") ?: "com.roblox.client")
        }

        val deviceId = prefs.getString("deviceId", null)
            ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("deviceId", it).apply()
            }

        val info = TextView(this).apply {
            text = "device id: $deviceId"
        }

        val start = Button(this).apply {
            text = "Save & Start"
            setOnClickListener {
                prefs.edit()
                    .putString("broker", broker.text.toString().trim())
                    .putString("key", key.text.toString().trim())
                    .putString("pkg", pkg.text.toString().trim())
                    .apply()
                askBatteryExemption()
                startForegroundService(Intent(this@MainActivity, AgentService::class.java))
            }
        }

        root.addView(info)
        root.addView(broker)
        root.addView(key)
        root.addView(pkg)
        root.addView(start)
        setContentView(root)
    }

    // Cloud phones / Doze will kill the agent unless we're exempt. Ask once; if the
    // provider blocks the dialog the user can whitelist manually in device settings.
    private fun askBatteryExemption() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Exception) {
            // ignore — device may not support the intent
        }
    }
}
