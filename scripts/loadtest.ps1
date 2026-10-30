# Runs core/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner (all arguments are passed through).
# Example:
#   .\scripts\loadtest.ps1 --devices D:\tmp\devices-LT.json --broker mqtt://<host>:1883 --rate 1 --duration 300
#
# The whole backend is installed first because the load runner is in the core module while the code it drives
# lives in the platform-* modules.
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..\backend')
try {
    mvn -q -B '-DskipTests' install
    if ($LASTEXITCODE -ne 0) { throw 'mvn install failed' }
    mvn -q -B -pl core dependency:build-classpath '-Dmdep.outputFile=target/loadtest-cp.txt' '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'classpath resolution failed' }
    $cp = 'core\target\test-classes;core\target\classes;' + (Get-Content 'core\target\loadtest-cp.txt' -Raw).Trim()
    java -cp $cp com.omnihub.iot.loadtest.MqttLoadRunner @args
} finally {
    Pop-Location
}
