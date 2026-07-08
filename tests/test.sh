#!/bin/bash
# Verifier entry point. Builds the Javalin service from the (possibly agent-edited)
# source, starts it on port 7070, then runs pytest, which drives the live service
# and inspects the emitted custody_manifest.json. Writes a binary reward to
# /logs/verifier/reward.txt from pytest's exit code.

if [ "$PWD" = "/" ]; then
    echo "Error: No working directory set. Please set a WORKDIR in your Dockerfile before running this script."
    exit 1
fi

mkdir -p /logs/verifier

APP_DIR=/app
SERVICE_LOG=/tmp/key-escrow-service.log

# Rebuild the fat jar offline from current source so the running service reflects
# the agent's edits. If this fails, the service will not start and the pytest
# checks fail -> reward 0 (no set -e, so the reward is still written below).
( cd "$APP_DIR" && mvn -o -B -DskipTests package ) > /tmp/mvn-package.log 2>&1

# Start the service from the project root so it resolves assets/ and docs/ and
# writes custody_manifest.json there. `exec` makes SERVICE_PID the java process.
( cd "$APP_DIR" && exec java -jar target/key-escrow-service.jar ) > "$SERVICE_LOG" 2>&1 &
SERVICE_PID=$!

cleanup() {
    kill -TERM "$SERVICE_PID" 2>/dev/null || true
    wait "$SERVICE_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Wait (bounded) for the service to accept connections on 7070.
for _ in $(seq 1 60); do
    if (echo > /dev/tcp/127.0.0.1/7070) 2>/dev/null; then
        break
    fi
    sleep 1
done

python -m pytest --ctrf /logs/verifier/ctrf.json /tests/test_outputs.py -rA
code=$?

echo "pytest exit code: ${code}"

if [ "$code" -eq 0 ]; then
    echo 1 > /logs/verifier/reward.txt
else
    echo 0 > /logs/verifier/reward.txt
fi
