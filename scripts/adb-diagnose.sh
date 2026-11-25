#!/usr/bin/env bash
set -euo pipefail

PACKAGE="io.github.terrydaktal.whisperkeyboard"
SERIAL="${ANDROID_SERIAL:-}"
EVENT_LINES=80
CLEAR_LOGS=false

usage() {
	cat <<'EOF'
NAME
    adb-diagnose.sh - Pull Voice Keyboard remote diagnostics over ADB

SYNOPSIS
    adb-diagnose.sh [-s SERIAL] [--lines N] [--clear] [-h|--help]

DESCRIPTION
    Collects persisted and live diagnostic state from the Android Voice Keyboard
    app (io.github.terrydaktal.whisperkeyboard) in a single non-destructive ADB
    inspection pass. Reads disk-persisted JSON/JSONL artifacts via run-as first
    so diagnostics remain instant even when Android's App Freezer has frozen the
    idle process or the app has crashed.

OPTIONS
    -s, --serial SERIAL
        Target a specific ADB device serial (defaults to ANDROID_SERIAL or the
        sole connected device).

    -n, --lines N
        Number of recent JSONL events to display (default: 80).

    --clear
        Delete persisted diagnostic logs (events*.jsonl, last_snapshot.json,
        last_crash.json) on the device after printing nothing.

    -h, --help
        Display this help text and exit.

OPERATION
    1. Queries installed package versionCode/versionName and live PID.
    2. Reads files/diagnostics/last_snapshot.json and last_crash.json via run-as.
    3. Streams the last N lines across events.1.jsonl and events.jsonl.
    4. Lists files/pending_dictations/ and prints non-secret shared_prefs XML.
    5. Attempts bounded (2s timeout) live dumpsys on WhisperRecordingService and
       SwiftKeyBridgeService (gracefully notes when idle or cgroup-frozen).

EXAMPLES
    ./scripts/adb-diagnose.sh -s 100.70.36.28:5555
    ./scripts/adb-diagnose.sh -s 100.70.36.28:5555 --lines 150
    ./scripts/adb-diagnose.sh -s 100.70.36.28:5555 --clear

FILES
    files/diagnostics/last_snapshot.json
    files/diagnostics/last_crash.json
    files/diagnostics/events.jsonl
    files/diagnostics/events.1.jsonl
    files/pending_dictations/*.wav

PATHS
    App internal storage: /data/user/0/io.github.terrydaktal.whisperkeyboard/

SECURITY NOTES
    DictationDiagnostics redacts raw transcripts, Google API keys, bearer
    tokens, and URL credentials before writing to disk or dumpsys. Output
    contains operational metadata (durations, byte counts, error classes, editor
    package names).

EXIT STATUS
    0   Diagnostics collected (or cleared) successfully.
    1   ADB connection error or invalid arguments.

AUTHORS
    Terrydaktal
EOF
}

while (($# > 0)); do
	case "$1" in
	-s | --serial)
		[[ $# -ge 2 ]] || {
			echo "ERROR: $1 requires a device serial" >&2
			exit 1
		}
		SERIAL="$2"
		shift 2
		;;
	-n | --lines)
		[[ $# -ge 2 ]] || {
			echo "ERROR: $1 requires a positive integer" >&2
			exit 1
		}
		EVENT_LINES="$2"
		shift 2
		;;
	--clear)
		CLEAR_LOGS=true
		shift
		;;
	-h | --help)
		usage
		exit 0
		;;
	*)
		echo "ERROR: unknown argument: $1" >&2
		usage >&2
		exit 1
		;;
	esac
done

adb_cmd=(adb)
if [[ -n "$SERIAL" ]]; then
	adb_cmd+=(-s "$SERIAL")
fi

if [[ "$CLEAR_LOGS" == true ]]; then
	"${adb_cmd[@]}" shell "run-as $PACKAGE rm -f files/diagnostics/events.jsonl files/diagnostics/events.1.jsonl files/diagnostics/last_snapshot.json files/diagnostics/last_crash.json"
	echo "Cleared Voice Keyboard diagnostics on device."
	exit 0
fi

echo "=== PACKAGE & PROCESS ==="
"${adb_cmd[@]}" shell "dumpsys package $PACKAGE | grep -E 'versionCode|versionName' | head -n 4" || true
PID="$("${adb_cmd[@]}" shell "pidof $PACKAGE" 2>/dev/null | tr -d '\r' || true)"
echo "pid=${PID:-not-running}"

echo
echo "=== LAST PERSISTED SNAPSHOT (files/diagnostics/last_snapshot.json) ==="
"${adb_cmd[@]}" shell "run-as $PACKAGE sh -c 'if [ -f files/diagnostics/last_snapshot.json ]; then cat files/diagnostics/last_snapshot.json; else echo \"(none)\"; fi'" || true

echo
echo "=== LAST CRASH (files/diagnostics/last_crash.json) ==="
"${adb_cmd[@]}" shell "run-as $PACKAGE sh -c 'if [ -f files/diagnostics/last_crash.json ]; then cat files/diagnostics/last_crash.json; else echo \"(none)\"; fi'" || true

echo
echo "=== RECENT PERSISTED EVENTS (last $EVENT_LINES) ==="
"${adb_cmd[@]}" shell "run-as $PACKAGE sh -c 'if [ -f files/diagnostics/events.1.jsonl ] || [ -f files/diagnostics/events.jsonl ]; then cat files/diagnostics/events.1.jsonl files/diagnostics/events.jsonl 2>/dev/null | tail -n $EVENT_LINES; else echo \"(none)\"; fi'" || true

echo
echo "=== PENDING DICTATIONS ==="
"${adb_cmd[@]}" shell "run-as $PACKAGE ls -la files/pending_dictations 2>&1" || true

echo
echo "=== SHARED PREFS ==="
"${adb_cmd[@]}" shell "run-as $PACKAGE sh -c 'for f in shared_prefs/*.xml; do [ -f \"\$f\" ] && echo \"--- \$f ---\" && cat \"\$f\"; done'" || true

echo
echo "=== LIVE DUMPSYS (2s timeout; skipped if idle/frozen) ==="
for svc in ".WhisperRecordingService" ".SwiftKeyBridgeService"; do
	echo "--- $svc ---"
	if out="$(timeout 2s "${adb_cmd[@]}" shell "dumpsys activity service $PACKAGE/$svc" 2>&1)"; then
		printf '%s\n' "$out" | head -n 40
	else
		echo "(timed out or service frozen/not running)"
	fi
done
