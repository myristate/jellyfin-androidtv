#!/usr/bin/env bash
# Live TV start-up test harness for the emulator (or any adb device).
#
# Opens the Live TV guide in the Finly debug app, plays each of the first N channels by tapping its row,
# waits, and saves the PlaybackTiming and EventLogger lines for every attempt, then prints a summary table.
#
# Usage: scripts/livetv-harness.sh [rows=10] [seconds-per-channel=15] [out-dir]
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROWS="${1:-10}"
WAIT="${2:-15}"
OUT="${3:-livetv-results}"
ADB="${ADB:-adb}"
PKG="${PKG:-io.github.myristate.finly.debug}"
mkdir -p "$OUT"

# Guide geometry on a 1920x1080 screen: first channel row centre at y=300, rows 80px apart
row_y() { echo $((300 + ($1 - 1) * 80)); }

launch() {
	"$ADB" shell am force-stop "$PKG"
	"$ADB" shell am start -n "$PKG/org.jellyfin.androidtv.ui.startup.StartupActivity" >/dev/null
	sleep 15
	"$ADB" shell input tap 336 400   # Live TV card on the home screen
	sleep 8
}

launch
for ((row = 1; row <= ROWS; row++)); do
	"$ADB" logcat -c
	"$ADB" shell input tap 100 "$(row_y "$row")"
	sleep "$WAIT"
	"$ADB" logcat -d > "$OUT/row$row.log"
	"$ADB" shell input keyevent BACK
	sleep 4
	# If playback crashed or the guide was lost, start again from the guide
	if ! "$ADB" shell dumpsys window | grep -q "mCurrentFocus.*$PKG"; then launch; fi
done

printf '%-4s %-26s %-10s %-8s %-8s %-8s %s\n' row channel info prepare ready frame result
for ((row = 1; row <= ROWS; row++)); do
	f="$OUT/row$row.log"
	name=$(grep -m1 'PlaybackTiming: Live TV .*: start' "$f" | sed -E 's/.*Live TV (.*): start.*/\1/')
	ms() { grep -m1 "PlaybackTiming: .*: $1 " "$f" | sed -E 's/.*\+([0-9]+)ms.*/\1/'; }
	err=$(grep -m1 'PlaybackTiming: .*: error' "$f" | sed -E 's/.*: (error [A-Z_]+).*/\1/')
	frame=$(ms 'first frame')
	result=${err:-$([ -n "$frame" ] && echo ok || echo "no picture")}
	printf '%-4s %-26s %-10s %-8s %-8s %-8s %s\n' "$row" "${name:-?}" "$(ms 'stream info [A-Za-z]*')" "$(ms prepare)" "$(ms ready)" "$frame" "$result"
done
