#!/bin/zsh
# Runs every automatic check of the repository: the ones CI runs, plus the privacy scan. No phone is needed.
#   tools/test-all.sh            everything (Android unit tests, lint and release builds)
#   tools/test-all.sh --quick    Android unit tests only (about a minute)
# On-device checks (the Android phone's test line) are separate: tools/device-test.sh.
# Needs: JDK 17 and the Android SDK. Set JAVA_HOME if it is not the default; the path below is only used when it exists.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
quick=0
for a in "$@"; do
  case $a in
    --quick) quick=1 ;;
    -h|--help) sed -n 2,7p "$0"; exit 0 ;;
    *) print -u2 "unknown option $a"; exit 2 ;;
  esac
done
[[ -z ${JAVA_HOME:-} && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]] &&
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

failed=()
step() {   # step <name> <command...>: runs it, keeps going on failure, prints the tail of the output on failure
  local name=$1; shift
  print -n -- "• $name … "
  local log; log=$(mktemp)
  local t0=$SECONDS
  if "$@" >"$log" 2>&1; then print "ok ($((SECONDS - t0)) s)"
  else print "FAILED ($((SECONDS - t0)) s)"; tail -40 "$log" | sed 's/^/    /'; failed+=("$name"); fi
  rm -f "$log"
}

step "whitespace (git diff --check)" git diff --check HEAD
step "script syntax" sh -c 'sh -n tools/dbg.sh && sh -n tools/lib.sh && zsh -n tools/device-test.sh && zsh -n tools/check-private.sh && zsh -n tools/release.sh'
step "privacy scan (tracked changes)" tools/check-private.sh
step "docs links" python3 tools/check-docs-links.py
step "guardrails (theme, identity)" python3 tools/check-guardrails.py

gradle_tasks=(:core:testDebugUnitTest :app:testDebugUnitTest :helper:testDebugUnitTest :client:testDebugUnitTest)
(( quick )) || gradle_tasks+=(:app:lintRelease :client:lintRelease :app:assembleRelease :helper:assembleRelease :client:assembleRelease)
step "Android (${#gradle_tasks} Gradle tasks)" sh -c "cd android && ./gradlew --console=plain ${gradle_tasks[*]}"

print
if (( ${#failed} )); then print "FAILED: ${(j:, :)failed}"; exit 1; fi
print "All checks passed."
