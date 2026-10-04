# Fleet Agent

Android agent for cloud-phone fleets. Minimal (< 1MB logic): foreground service
that watches a target app, relaunches it via root, and talks to a backend over MQTT.

## What it does

- Foreground service + `START_STICKY` (survives normal OOM pressure)
- Watchdog loop: `pidof <pkg>` → if gone, `monkey -p <pkg> -c LAUNCHER 1` to relaunch
- Backoff: 30s when healthy, doubles up to 300s after relaunch
- MQTT heartbeat to `cf/{deviceId}/up/heartbeat`
- LWT to `cf/{deviceId}/up/status` so the broker knows instantly if the agent dies
- Subscribes to `cf/{deviceId}/down/cmd` for remote `rejoin` / `restart` / `start`
- Optional: reads `/sdcard/fleet/heartbeat.json` (written by the in-game script)
  and forwards it, so character name / place / jobId ride along with status

## Build

Push to `main` — GitHub Actions builds the debug APK and uploads it as an artifact.

```bash
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```

Then: repo → Actions → Build APK → download `fleet-agent-debug` artifact.

## Configure on device

Open the app, fill in:

- `broker url` — e.g. `tcp://your-broker:1883`
- `device key` — per-device key (used as MQTT username)
- `target package` — default `com.roblox.client`

Press **Save & Start**. The service then runs until reboot, and restarts on boot.

## Root hardening (optional, cloud phone)

To make the agent effectively unkillable:

```bash
adb push app-debug.apk /data/local/tmp/
adb shell su -c "cp /data/local/tmp/app-debug.apk /system/priv-app/FleetAgent/FleetAgent.apk"
adb shell su -c "chmod 644 /system/priv-app/FleetAgent/FleetAgent.apk"
adb shell su -c "am force-stop com.fleet.agent"
adb reboot
```

Then set `oom_score_adj = -1000` for the process and enable battery
optimization exemption in the cloud phone panel.

## MQTT topics

```
cf/{deviceId}/up/heartbeat   agent -> backend   status + script data
cf/{deviceId}/up/status      agent -> backend   retained online/offline (LWT)
cf/{deviceId}/down/cmd       backend -> agent   rejoin | restart | start
```

## Notes

- Cloud phones usually have no GMS, so FCM is not an option — MQTT is required.
- Set the cloud phone panel to idle timeout = never, RAM >= 4GB, root on.
- `monkey -p <pkg> -c LAUNCHER 1` launches the default launcher activity, so you
  don't need to know the activity name for the target app.
