#!/bin/zsh
# On-device checks with the Android phone attached (adb). Only Tether's own test line and test numbers are used:
# no real call, no SMS, nothing written to the phone's call log, contacts or messages.
#   tools/device-test.sh                 call matrix on the Android phone (in-process test client): answer, decline,
#                                        remote hang-up, outgoing; a dry sync; the phone-tools state
# Install the build you want to test first (docs/BUILD.md). Exit status 1 when a check fails.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

while (( $# )); do
  case $1 in
    -h|--help) sed -n 2,10p "$0"; exit 0 ;;
    *) print -u2 "unknown option $1"; exit 2 ;;
  esac
  shift
done
dbg() { tools/dbg.sh "$@"; }
failed=()
check() {   # check <name> <ok:0|1> <detail>
  if [[ $2 == 1 ]]; then print "• $1 … ok  $3"; else print "• $1 … FAILED  $3"; failed+=("$1"); fi
}

state=$(dbg state) || { print -u2 "The Android phone did not answer (adb, app installed?)."; exit 1; }
print -- "$state" | grep -q '"ble_running":true' || print "  note: Bluetooth service not running on the Android phone"

for mode in answer decline remote outgoing; do
  out=$(dbg loopback_call $mode)
  case $mode in
    decline) ok=$(print -- "$out" | grep -qE 'states=\[ringing, ended\]' && echo 1 || echo 0) ;;
    *) down=$(print -- "$out" | grep -oE 'downFrames=[0-9]+' | cut -d= -f2); up=$(print -- "$out" | grep -oE 'upFrames=[0-9]+' | cut -d= -f2)
       ok=$([[ -n $down && -n $up && $down -ge 240 && $up -ge 240 ]] && echo 1 || echo 0) ;;
  esac
  check "test-line call: $mode" $ok "$(print -- "$out" | grep -oE 'states=\[[^]]*\]|downFrames=[0-9]+ upFrames=[0-9]+|ring→iPhone=[0-9.]+ ms|end→ended=[0-9.]+ ms' | tr '\n' ' ')"
done

sync=$(dbg loopback)
check "dry sync (handshake, texts, calls, contacts)" $(print -- "$sync" | grep -q 'handshake ok' && print -- "$sync" | grep -q 'sms.batch' && echo 1 || echo 0) \
  "$(print -- "$sync" | grep -oE 'items=\{[^}]*\}')"
check "carrier-code check never sends" $(dbg ussd_test '*100#' | grep -q 'sent=false' && dbg ussd_test 'hello' | grep -q 'valid=false' && echo 1 || echo 0) ""
check "phone tools in status" $(dbg state | grep -q '"tools":{' && echo 1 || echo 0) ""

dbg only off >/dev/null
print
if (( ${#failed} )); then print "FAILED: ${(j:, :)failed}"; exit 1; fi
print "All device checks passed."
