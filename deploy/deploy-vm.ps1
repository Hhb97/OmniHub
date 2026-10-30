# Deploys OmniHub to a Docker host over SSH (key-based login; no password is ever typed or stored by this script).
#
#   .\deploy\deploy-vm.ps1 -VmHost <ip> -User <ssh-user> [-IdentityFile <private key>]   # first deploy: asks for the admin password
#   .\deploy\deploy-vm.ps1 -VmHost <ip> -User <ssh-user>                 # later: re-uploads the code, rebuilds, restarts
#
# What it does: packs the working tree (without node_modules, build output, local-only files), copies it with scp,
# creates .env on the host if it does not exist (random secrets, admin password from you), runs
# `docker compose up -d --build`, then configures EMQX (deploy/emqx-bootstrap.sh).
# The host needs Docker Engine + the compose plugin and internet access (or a registry mirror) to pull images.
param(
    [Parameter(Mandatory = $true)] [string] $VmHost,
    [Parameter(Mandatory = $true)] [string] $User,
    [string] $RemoteDir = 'omnihub',
    [string] $IdentityFile,
    [switch] $Prebuilt,   # build the jar and the web bundle here and ship them, so the host needs no Maven / npm access
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$target = "$User@$VmHost"
$ssh = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
$scpKey = @()
if ($IdentityFile) {
    $ssh += @('-i', $IdentityFile, '-o', 'IdentitiesOnly=yes')
    $scpKey = @('-i', $IdentityFile, '-o', 'IdentitiesOnly=yes')
}

Write-Host "==> checking SSH access to $target"
& ssh @ssh $target 'echo connected; docker --version; docker compose version' | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw 'SSH login with a key failed, or Docker / the compose plugin is not installed on the host.' }

$prebuiltDir = Join-Path $root 'deploy/prebuilt'
if (Test-Path $prebuiltDir) { Remove-Item $prebuiltDir -Recurse -Force }
if ($Prebuilt) {
    Write-Host '==> building the backend jar locally (from a copy without local-only config files)'
    $tmp = Join-Path $env:TEMP 'omnihub-be-build'
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    $be = Join-Path $root 'backend'
    & robocopy $be $tmp /E /XD target data /XF application-localext.yml loadtest.properties *.log /NFL /NDL /NJH /NJS | Out-Null
    Push-Location $tmp
    try {
        & mvn -B -q -P '!embedded' '-Dmaven.test.skip=true' package
        if ($LASTEXITCODE -ne 0) { throw 'mvn package failed' }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Force $prebuiltDir | Out-Null
    $jar = Get-ChildItem (Join-Path $tmp 'core/target/iot-backend-*.jar') | Where-Object Name -notmatch 'original' | Select-Object -First 1
    Copy-Item $jar.FullName (Join-Path $prebuiltDir 'app.jar')
    Remove-Item $tmp -Recurse -Force

    Write-Host '==> building the web bundle locally'
    Push-Location (Join-Path $root 'frontend')
    try {
        & npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'npm run build failed' }
    } finally { Pop-Location }
    Copy-Item (Join-Path $root 'frontend/dist') (Join-Path $prebuiltDir 'web') -Recurse
}

Write-Host '==> packing the project'
$archive = Join-Path $env:TEMP 'omnihub-deploy.tar.gz'
if (Test-Path $archive) { Remove-Item $archive -Force }
$excludes = @('.git', '.idea', '.vscode', 'node_modules', 'dist', 'target', 'backend/data', '.env', '*.local.json',
    'loadtest.properties', 'backend/loadtest.properties', 'frontend/.env.local',
    'backend/core/src/main/resources/application-localext.yml', 'simulator/config.local.json')
$excludeArgs = $excludes | ForEach-Object { "--exclude=$_" }
& tar -czf $archive @excludeArgs -C $root .
if ($LASTEXITCODE -ne 0) { throw 'tar failed' }
Write-Host ("    {0:N1} MB" -f ((Get-Item $archive).Length / 1MB))

Write-Host '==> uploading'
& ssh @ssh $target "mkdir -p $RemoteDir"
& scp -o BatchMode=yes @scpKey $archive "${target}:$RemoteDir/omnihub-deploy.tar.gz"
if ($LASTEXITCODE -ne 0) { throw 'scp failed' }
& ssh @ssh $target "cd $RemoteDir && tar -xzf omnihub-deploy.tar.gz && rm omnihub-deploy.tar.gz && find . -type f \( -name '*.sh' -o -name '*.yml' -o -name '*.yaml' -o -name '*.conf' -o -name '*Dockerfile*' -o -name '.env.example' \) -not -path './frontend/node_modules/*' -exec sed -i 's/\r`$//' {} + && chmod +x deploy/*.sh"
if ($LASTEXITCODE -ne 0) { throw 'extracting on the host failed' }

$hasEnv = (& ssh @ssh $target "test -f $RemoteDir/.env && echo yes || echo no").Trim()
if ($hasEnv -ne 'yes') {
    Write-Host '==> creating .env on the host (random secrets). Choose the platform admin password (min 8 characters):'
    $sec = Read-Host -AsSecureString 'admin password'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    # the password travels over the encrypted SSH channel on stdin, not on the command line
    $plain | & ssh @ssh $target "cd $RemoteDir && read -r OMNIHUB_ADMIN_PASSWORD && export OMNIHUB_ADMIN_PASSWORD && sh deploy/vm-init-env.sh"
    if ($LASTEXITCODE -ne 0) { throw 'creating .env failed' }
}

if ($Prebuilt) {
    # every later `docker compose` call on the host (including emqx-bootstrap.sh) then uses the prebuilt overlay
    & ssh @ssh $target "cd $RemoteDir && (grep -q '^COMPOSE_FILE=' .env || echo 'COMPOSE_FILE=docker-compose.yml:deploy/docker-compose.prebuilt.yml' >> .env)"
    if ($LASTEXITCODE -ne 0) { throw 'could not select the prebuilt overlay in .env' }
}

Write-Host '==> docker compose up'
$build = if ($SkipBuild) { '' } else { '--build' }
& ssh @ssh $target "cd $RemoteDir && docker compose up -d $build"
if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }

Write-Host '==> configuring EMQX'
& ssh @ssh $target "cd $RemoteDir && sh deploy/emqx-bootstrap.sh"
if ($LASTEXITCODE -ne 0) { Write-Warning 'EMQX bootstrap reported a problem, see the output above' }

Write-Host '==> status'
& ssh @ssh $target "cd $RemoteDir && docker compose ps"
Write-Host "Frontend: http://${VmHost}:8088   MQTT: ${VmHost}:1883   EMQX dashboard: http://${VmHost}:18083   Grafana: http://${VmHost}:3000"
