#!/usr/bin/env bash
# DIAGNOSTIC ONLY: bisect branch for #1036, never merged.
#
# Records which window holds focus on the emulator while connectedDebugAndroidTest runs,
# to explain the RootViewWithoutFocusException that AGP 9.5.x causes on shard 0.
#
#   ci-focus-probe.sh start   start logcat + a focus poller in the background
#   ci-focus-probe.sh stop    stop them and take final window/activity dumps
#
# Output goes to $GITHUB_WORKSPACE/focus-diag (uploaded by the workflow).
set -uo pipefail

out="${GITHUB_WORKSPACE:?}/focus-diag"
pkg="com.yapt.planttracker"

case "${1:-}" in
  start)
    rm -rf "$out"
    mkdir -p "$out"
    adb logcat -c || true
    nohup adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
    echo "$!" > "$out/logcat.pid"
    nohup bash -c '
      out="$1"; pkg="$2"; shots=0; perms_done=0
      while true; do
        ts=$(date -u +%H:%M:%S)
        focus=$(adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | tr -s " " | tr "\n" " ")
        echo "$ts $focus" >> "$out/focus.txt"
        # The APKs exist only while the task runs (AGP uninstalls them after), so grab the
        # runtime-permission state of the app and its test APK as soon as they are installed.
        if [ "$perms_done" = 0 ] && adb shell pm path "$pkg.test" > /dev/null 2>&1; then
          sleep 2
          adb shell dumpsys package "$pkg" > "$out/package-app.txt" 2>&1
          adb shell dumpsys package "$pkg.test" > "$out/package-test.txt" 2>&1
          perms_done=1
        fi
        # Screenshot (at most 6) whenever something other than the app or the launcher has focus.
        if [ "$shots" -lt 6 ] && echo "$focus" | grep -q "mCurrentFocus=Window" \
            && ! echo "$focus" | grep -q "mCurrentFocus=Window{[^}]*$pkg"; then
          adb exec-out screencap -p > "$out/shot-$ts.png" 2>/dev/null
          adb shell dumpsys window windows > "$out/windows-$ts.txt" 2>&1
          shots=$((shots + 1))
        fi
        sleep 2
      done
    ' _ "$out" "$pkg" > /dev/null 2>&1 &
    echo "$!" > "$out/poller.pid"
    echo "Focus probe started"
    ;;
  stop)
    for f in poller logcat; do
      [ -f "$out/$f.pid" ] && kill "$(cat "$out/$f.pid")" 2> /dev/null
    done
    adb shell dumpsys window windows > "$out/windows-final.txt" 2>&1
    adb shell dumpsys activity activities > "$out/activities-final.txt" 2>&1
    adb exec-out screencap -p > "$out/shot-final.png" 2>/dev/null
    grep -E "ActivityTaskManager: START|GrantPermissions|PermissionController|ANR in|not responding" \
      "$out/logcat.txt" > "$out/logcat-highlights.txt" 2>/dev/null
    echo "Focus probe stopped"
    ;;
  *)
    echo "usage: $0 start|stop" >&2
    exit 2
    ;;
esac
