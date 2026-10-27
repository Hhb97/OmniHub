#!/usr/bin/env bash
# Runs backend/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner (all arguments are passed through).
# Example:
#   scripts/loadtest.sh --devices /tmp/devices-LT.json --broker mqtt://<host>:1883 --rate 1 --duration 300
set -euo pipefail
cd "$(dirname "$0")/../backend"
mvn -q -B test-compile
mvn -q -B dependency:build-classpath -Dmdep.outputFile=target/loadtest-cp.txt -Dmdep.includeScope=test
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
exec java -cp "target/test-classes${SEP}target/classes${SEP}$(cat target/loadtest-cp.txt)" com.omnihub.iot.loadtest.MqttLoadRunner "$@"
