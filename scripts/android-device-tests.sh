#!/usr/bin/env bash
# CI: ./scripts/android-device-tests.sh 29 (or 34)
# Existing local device: ./scripts/android-device-tests.sh --connected SERIAL
# Requires JDK17, Android command-line tools, accepted SDK licenses, and a Linux x86_64
# host with KVM for managed emulators. --connected never creates or stops the user's device.
# SDK commands: https://developer.android.com/tools/avdmanager
# https://developer.android.com/tools/sdkmanager
# https://developer.android.com/studio/run/emulator-commandline
set -euo pipefail

cd "$(dirname "$0")/.."
task_root="$PWD"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]]; then
  echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK.' >&2
  exit 2
fi
adb_bin="$sdk_root/platform-tools/adb"

if [[ "${1:-}" == --connected ]]; then
  [[ $# == 2 && -n "$2" ]] || { echo 'Usage: android-device-tests.sh --connected SERIAL' >&2; exit 2; }
  export ANDROID_SERIAL="$2"
  "$adb_bin" -s "$ANDROID_SERIAL" get-state
  exec ./gradlew :app:connectedDebugAndroidTest
fi

api_level="${1:-29}"
[[ $# -le 1 && "$api_level" =~ ^[0-9]+$ ]] || { echo 'Usage: android-device-tests.sh [API_LEVEL]' >&2; exit 2; }
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
  echo 'Managed emulator tests require Linux x86_64; use --connected SERIAL on other hosts.' >&2
  exit 2
}
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo 'The current user needs read/write access to /dev/kvm.' >&2; exit 2; }

sdkmanager_bin="$(command -v sdkmanager || true)"
avdmanager_bin="$(command -v avdmanager || true)"
[[ -n "$sdkmanager_bin" ]] || sdkmanager_bin="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
[[ -n "$avdmanager_bin" ]] || avdmanager_bin="$sdk_root/cmdline-tools/latest/bin/avdmanager"
image_package="system-images;android-$api_level;google_apis;x86_64"
"$sdkmanager_bin" --sdk_root="$sdk_root" "platform-tools" "emulator" "$image_package"

logs="$task_root/build/device-test-logs/api-$api_level"
mkdir -p "$logs"
task_avd_dir="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/wentuyi-avd.XXXXXX")"
export ANDROID_AVD_HOME="$task_avd_dir"
avd_name="wentuyi-api-$api_level"
emulator_port="${WENTUYI_EMULATOR_PORT:-5580}"
[[ "$emulator_port" =~ ^[0-9]+$ && "$emulator_port" -ge 5554 && "$emulator_port" -le 5682 && $((emulator_port % 2)) == 0 ]] || {
  echo 'WENTUYI_EMULATOR_PORT must be an even port from 5554 through 5682.' >&2
  rmdir "$task_avd_dir"
  exit 2
}
export ANDROID_SERIAL="emulator-$emulator_port"
emulator_pid=""
cleanup() {
  result=$?
  trap - EXIT
  if [[ -n "$emulator_pid" ]]; then
    timeout 10 "$adb_bin" -s "$ANDROID_SERIAL" logcat -d > "$logs/logcat.txt" 2>&1 || true
    # Kill only the emulator process created here, never an unrelated device using a port.
    kill "$emulator_pid" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "$emulator_pid" 2>/dev/null || break
      sleep 0.2
    done
    kill -KILL "$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  rm -rf "$task_avd_dir"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'no\n' | "$avdmanager_bin" create avd -n "$avd_name" -k "$image_package" -p "$task_avd_dir/$avd_name.avd"
# Match the local KDF regressions on both APIs: a normal 256 MiB application heap,
# independent of the system image/device profile defaults; no largeHeap override.
avd_config="$task_avd_dir/$avd_name.avd/config.ini"
sed -i '/^vm\.heapSize[[:space:]]*=/d' "$avd_config"
printf '\nvm.heapSize=256\n' >> "$avd_config"
"$adb_bin" start-server
if "$adb_bin" devices | awk -v serial="$ANDROID_SERIAL" 'NR > 1 && $1 == serial { found = 1 } END { exit !found }'; then
  echo "Port $emulator_port already belongs to an emulator; choose WENTUYI_EMULATOR_PORT." >&2
  exit 2
fi
"$sdk_root/emulator/emulator" -avd "$avd_name" -port "$emulator_port" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader \
  -camera-back none -camera-front none -memory 2048 -cores 2 -accel on \
  > "$logs/emulator.log" 2>&1 &
emulator_pid=$!

deadline=$((SECONDS + 300))
until [[ "$(timeout 10 "$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
  if ! kill -0 "$emulator_pid" 2>/dev/null || (( SECONDS >= deadline )); then
    echo "Emulator failed to boot; see $logs/emulator.log" >&2
    tail -n 60 "$logs/emulator.log" >&2
    exit 1
  fi
  sleep 2
done
"$adb_bin" -s "$ANDROID_SERIAL" shell input keyevent 82
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$adb_bin" -s "$ANDROID_SERIAL" shell settings put global "$setting" 0
done
./gradlew :app:connectedDebugAndroidTest
