#!/usr/bin/env bash
# Real process-death check: write data and settings in one `am instrument` process, force-stop the app
# package, then verify them from a brand-new process. Run against ONE explicitly named device; this
# script never touches any other emulator and never wipes or uninstalls anything.
#
#   SERIAL=emulator-5592 bash scripts/e2e-process-restart.sh
set -euo pipefail

: "${SERIAL:?set SERIAL to the adb serial of a dedicated test emulator, e.g. SERIAL=emulator-5592}"
case "$SERIAL" in
  emulator-5554) echo "refusing to use emulator-5554 (the captain's emulator)" >&2; exit 2 ;;
esac
export ANDROID_SERIAL="$SERIAL"

root="$(cd "$(dirname "$0")/.." && pwd)"
pkg=com.finsight.ai
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
cls=com.example.finsightai.e2e.ProcessRestartE2ETest

( cd "$root" && bash gradlew installDebug installDebugAndroidTest )

run_phase() {
  out="$(adb -s "$SERIAL" shell am instrument -w -e class "$cls" -e restartPhase "$1" "$runner")"
  echo "$out"
  grep -q "^OK (1 test)" <<<"$out" || { echo "phase '$1' failed" >&2; exit 1; }
}

run_phase write
adb -s "$SERIAL" shell am force-stop "$pkg"
adb -s "$SERIAL" shell pidof "$pkg" && { echo "app process still alive after force-stop" >&2; exit 1; } || true
run_phase verify
echo "process-restart check passed on $SERIAL"
