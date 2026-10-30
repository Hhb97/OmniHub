#!/usr/bin/env bash
# Runs core/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner (all arguments are passed through).
# Example:
#   scripts/loadtest.sh --devices /tmp/devices-LT.json --broker mqtt://<host>:1883 --rate 1 --duration 300
#
# The whole backend is installed first because the load runner is in the core module while the code it drives
# lives in the platform-* modules.
set -euo pipefail
cd "$(dirname "$0")/../backend"
mvn -q -B -DskipTests install
mvn -q -B -pl core dependency:build-classpath -Dmdep.outputFile=target/loadtest-cp.txt -Dmdep.includeScope=test
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
exec java -cp "core/target/test-classes${SEP}core/target/classes${SEP}$(cat core/target/loadtest-cp.txt)" com.omnihub.iot.loadtest.MqttLoadRunner "$@"
