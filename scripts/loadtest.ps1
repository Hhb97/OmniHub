# Runs backend/src/test/java/com/omnihub/iot/loadtest/MqttLoadRunner (all arguments are passed through).
# Example:
#   .\scripts\loadtest.ps1 --devices D:\tmp\devices-LT.json --broker mqtt://<host>:1883 --rate 1 --duration 300
$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..\backend')
try {
    mvn -q -B test-compile
    if ($LASTEXITCODE -ne 0) { throw 'test-compile failed' }
    mvn -q -B dependency:build-classpath '-Dmdep.outputFile=target/loadtest-cp.txt' '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'classpath resolution failed' }
    $cp = 'target/test-classes;target/classes;' + (Get-Content 'target/loadtest-cp.txt' -Raw).Trim()
    java -cp $cp com.omnihub.iot.loadtest.MqttLoadRunner @args
} finally {
    Pop-Location
}
