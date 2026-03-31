#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker/rosbridge/docker-compose.yml"
PACKAGE_NAME="jp.oist.abcvlib.rosbridge"
ACTIVITY_NAME=".MainActivity"
SMOKE_MESSAGE="${SMOKE_TEST_MESSAGE:-hello_from_android}"
TIMEOUT_SECS="${SMOKE_TEST_TIMEOUT_SECS:-45}"

pick_lan_ip() {
    hostname -I | tr ' ' '\n' | awk '
        /^192\.168\./ { print; exit }
        /^10\./ { print; exit }
        /^172\.(1[6-9]|2[0-9]|3[0-1])\./ { print; exit }
    '
}

resolve_adb() {
    if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then
        printf '%s\n' "${ADB}"
        return
    fi

    if [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]]; then
        printf '%s\n' "${ANDROID_SDK_ROOT}/platform-tools/adb"
        return
    fi

    if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
        printf '%s\n' "${ANDROID_HOME}/platform-tools/adb"
        return
    fi

    if [[ -x "${HOME}/Android/Sdk/platform-tools/adb" ]]; then
        printf '%s\n' "${HOME}/Android/Sdk/platform-tools/adb"
        return
    fi

    command -v adb 2>/dev/null || true
}

cleanup() {
    if [[ "${KEEP_ROS_SMOKE_SERVICES:-0}" == "1" ]]; then
        echo "Keeping ROS smoke-test services running (KEEP_ROS_SMOKE_SERVICES=1)."
        return
    fi
    echo "Cleaning up ROS smoke-test services..."
    docker compose -f "$COMPOSE_FILE" down --remove-orphans >/dev/null 2>&1 || true
}

trap cleanup EXIT

ROS_PC_IP="${ROS_PC_IP:-$(pick_lan_ip)}"
if [[ -z "${ROS_PC_IP:-}" ]]; then
    echo "Unable to determine ROS PC IP. Set ROS_PC_IP explicitly." >&2
    exit 1
fi

ADB_BIN="$(resolve_adb)"
if [[ -z "${ADB_BIN:-}" || ! -x "${ADB_BIN}" ]]; then
    echo "Unable to locate adb. Set ADB, ANDROID_SDK_ROOT, or ANDROID_HOME to a valid SDK path." >&2
    exit 1
fi

echo "Using ROS PC IP: $ROS_PC_IP"
echo "Using adb: $ADB_BIN"
docker compose -f "$COMPOSE_FILE" up -d --build rosbridge pub echo
sleep 3

"$ADB_BIN" logcat -c
"$ROOT_DIR/gradlew" :rosbridgeTest:installDebug
"$ADB_BIN" shell am force-stop "$PACKAGE_NAME" || true
"$ADB_BIN" shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" \
    --es ROS_PC_IP "$ROS_PC_IP" \
    --ez AUTO_RUN_SMOKE_TEST true \
    --es SMOKE_TEST_MESSAGE "$SMOKE_MESSAGE" >/dev/null

deadline=$((SECONDS + TIMEOUT_SECS))
summary=""
summary_payload=""
while (( SECONDS < deadline )); do
    summary="$("$ADB_BIN" logcat -d -v brief | grep 'SmokeTest' | grep 'connect=' | grep 'subscribe=' | grep 'publish=' | tail -n 1 || true)"
    if [[ -n "$summary" ]]; then
        summary_payload="${summary#*: }"
        break
    fi
    sleep 1
done

if [[ -z "$summary" ]]; then
    echo "Timed out waiting for SmokeTest summary" >&2
    exit 1
fi

echo "$summary"
if [[ "$summary_payload" != PASS\ * ]]; then
    exit 1
fi

if ! docker compose -f "$COMPOSE_FILE" logs --no-color echo | grep -q "data: $SMOKE_MESSAGE"; then
    echo "Smoke test did not observe Android publish on /test_from_android" >&2
    echo "--- echo logs ---" >&2
    docker compose -f "$COMPOSE_FILE" logs --no-color echo >&2 || true
    echo "--- rosbridge logs ---" >&2
    docker compose -f "$COMPOSE_FILE" logs --no-color rosbridge >&2 || true
    exit 1
fi

echo "ROS smoke test PASS"
