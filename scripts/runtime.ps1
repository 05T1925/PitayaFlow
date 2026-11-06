param(
  [ValidateSet('all','init','build','start','health','demo','restart','stop','worker-fault','recover-worker','retry-cleanup','status')][string]$Action,
  [Parameter(Mandatory)][string]$RunDir,
  [string]$DshMirror='',
  [ValidateSet('api','worker')][string]$CleanupRole='worker',
  [switch]$UseLocalImages,
  [switch]$UseAsrFixture
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=[IO.Path]::GetFullPath($RunDir)
if(-not $run.StartsWith((Join-Path $root '.local')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -and
   -not $root.Equals((Join-Path $run 'source'),[StringComparison]::OrdinalIgnoreCase)){
  throw 'RunDir must be a dedicated child of this repository .local directory'
}
$source=Join-Path $run 'source';$envFile=Join-Path $run 'private.env'
if($Action -eq 'all') {
  $initialized=$false;$primary=$null;$cleanup=$null
  try {
    foreach($phase in @('init','build','start','demo')){
      & pwsh -NoProfile -File $PSCommandPath -Action $phase -RunDir $run -DshMirror $DshMirror -UseLocalImages:$UseLocalImages -UseAsrFixture:$UseAsrFixture
      if($LASTEXITCODE -ne 0){throw "runtime phase failed: $phase exit=$LASTEXITCODE"}
      if($phase -eq 'init'){$initialized=$true}
    }
  } catch {$primary=$_}
  finally {
    if($initialized){
      try {
        & pwsh -NoProfile -File $PSCommandPath -Action stop -RunDir $run
        if($LASTEXITCODE -ne 0){throw "runtime phase failed: stop exit=$LASTEXITCODE"}
      } catch {$cleanup=$_}
    }
  }
  if($primary -or $cleanup){
    if($primary){Write-Error -Message "primary: $($primary.Exception.Message)" -ErrorAction Continue}
    if($cleanup){Write-Error -Message "cleanup: $($cleanup.Exception.Message)" -ErrorAction Continue}
    exit 1
  }
  exit 0
}
function Load-Config {
  if(-not (Test-Path -LiteralPath $envFile)){throw 'Run init first'}
  Get-Content -LiteralPath $envFile -Encoding utf8 | ForEach-Object {
    if($_ -match '^([A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
  }
}
function Port {
  if(-not $script:claimedPorts){$script:claimedPorts=@{}}
  do {
    $socket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try{$socket.Start();$value=([Net.IPEndPoint]$socket.LocalEndpoint).Port}finally{$socket.Stop()}
  } while($script:claimedPorts.ContainsKey($value))
  $script:claimedPorts[$value]=$true
  return $value
}
function Secret([int]$bytes){return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes($bytes)).ToLowerInvariant()}
function Compose([string[]]$Commands) {
  & docker compose --env-file $envFile -f (Join-Path $source 'deploy/s1-01/compose.yml') @Commands
  if($LASTEXITCODE -ne 0){throw "docker compose failed action=$($Commands -join ',') exit=$LASTEXITCODE"}
}
function Wait-Health {
  Load-Config
  foreach($port in @($env:PF_API_PORT_RUN,$env:PF_WORKER_PORT_RUN)) {
    $ok=$false
    for($i=0;$i -lt 80;$i++) {
      foreach($role in @('api','worker')){if(-not (Owned-Process (Join-Path $run "$role.host.pid"))){throw "runtime host exited during health check role=$role"}}
      try {$health=Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 2
        if($health.status -eq 'UP'){$ok=$true;break}} catch {}
      Start-Sleep -Milliseconds 500
    }
    if(-not $ok){throw "health failed port=$port; inspect isolated api.log/worker.log"}
  }
  Write-Output 'PASS API and Worker health UP'
}
function Wait-DatabasePort {
  for($i=0;$i -lt 60;$i++){
    $client=[Net.Sockets.TcpClient]::new()
    try{
      $client.ReceiveTimeout=1000;$client.Connect('127.0.0.1',[int]$env:PF_DB_PORT)
      $stream=$client.GetStream();$header=[byte[]]::new(4)
      if($stream.Read($header,0,4) -eq 4){return}
    }catch{}
    finally{$client.Dispose()}
    Start-Sleep -Milliseconds 500
  }
  throw 'MySQL host port handshake unavailable after Compose health'
}
function Start-Role([string]$role) {
  $script=Join-Path $source 'scripts/runtime_job_host.ps1'
  if(Owned-Process (Join-Path $run "$role.host.pid")){throw "role already supervised: $role"}
  if(Owned-Process (Join-Path $run "$role.pid")){throw "role root still alive: $role"}
  if(Test-Path -LiteralPath (Join-Path $run "$role.launch")){Confirm-Stop $role|Out-Null}
  Remove-Item -LiteralPath (Join-Path $run "stop-$role") -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath (Join-Path $run "$role.stop") -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath (Join-Path $run "$role.host.exit") -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath (Join-Path $run "$role.recovery") -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath (Join-Path $run "$role.pid") -ErrorAction SilentlyContinue
  $nonce=[guid]::NewGuid().ToString();$nonce|Set-Content -LiteralPath (Join-Path $run "$role.launch") -Encoding ascii
  $hostProcess=Start-Process pwsh -ArgumentList @('-NoProfile','-File',"`"$script`"",'-Role',$role,'-RunDir',"`"$run`"",'-LaunchNonce',$nonce) -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $run "$role.host.out") -RedirectStandardError (Join-Path $run "$role.host.err")
  "$($hostProcess.Id),$($hostProcess.StartTime.ToUniversalTime().Ticks)" | Set-Content -LiteralPath (Join-Path $run "$role.host.pid") -Encoding ascii
}
function Start-Fixture {
  if(-not $env:PF_ASR_FIXTURE_PORT){return}
  if(Owned-Process (Join-Path $run 'asr-fixture.pid')){throw 'ASR fixture already running'}
  $fixture=Join-Path $source 'scripts/asr_fixture.mjs'
  $process=Start-Process node -ArgumentList @("`"$fixture`"") -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $run 'asr-fixture.out') -RedirectStandardError (Join-Path $run 'asr-fixture.err')
  "$($process.Id),$($process.StartTime.ToUniversalTime().Ticks)"|Set-Content -LiteralPath (Join-Path $run 'asr-fixture.pid') -Encoding ascii
  for($i=0;$i -lt 30;$i++){
    if(-not (Owned-Process (Join-Path $run 'asr-fixture.pid'))){throw 'ASR fixture exited before health'}
    try{if((Invoke-WebRequest "http://127.0.0.1:$($env:PF_ASR_FIXTURE_PORT)/health" -TimeoutSec 1).StatusCode -eq 200){return}}catch{}
    Start-Sleep -Milliseconds 200
  }
  throw 'ASR fixture health failed'
}
function Owned-Process([string]$pidFile) {
  if(-not (Test-Path -LiteralPath $pidFile)){return $null}
  $parts=(Get-Content -LiteralPath $pidFile -Raw).Trim() -split ','
  try {$process=Get-Process -Id ([int]$parts[0]) -ErrorAction Stop
    if($process.StartTime.ToUniversalTime().Ticks -ne [long]$parts[1]){return $null};return $process
  }catch {return $null}
}
function Read-StopRecord([string]$path,[string]$role) {
  if(-not (Test-Path -LiteralPath $path)){throw "stop evidence missing role=$role"}
  $fields=@{}
  foreach($line in Get-Content -LiteralPath $path){
    if($line -eq ''){continue}
    if($line -notmatch '^([a-z_]+)=(.*)$'){throw "stop evidence malformed role=$role"}
    if($fields.ContainsKey($Matches[1])){throw "stop evidence duplicate role=$role"}
    $fields[$Matches[1]]=$Matches[2]
  }
  return $fields
}
function Stop-Identity([string]$role) {
  $pidPath=Join-Path $run "$role.pid";$launchPath=Join-Path $run "$role.launch"
  if(-not (Test-Path -LiteralPath $pidPath) -or -not (Test-Path -LiteralPath $launchPath)){throw "stop identity missing role=$role"}
  $pidParts=((Get-Content -LiteralPath $pidPath -Raw).Trim() -split ',')
  if($pidParts.Count -ne 2){throw "stop root identity invalid role=$role"}
  $nonce=(Get-Content -LiteralPath $launchPath -Raw).Trim()
  $stopPath=Join-Path $run "$role.stop";$exitPath=Join-Path $run "$role.host.exit"
  if(-not (Test-Path -LiteralPath $stopPath) -or -not (Test-Path -LiteralPath $exitPath)){throw "stop evidence missing role=$role"}
  $fields=Read-StopRecord $stopPath $role
  foreach($key in @('run','role','launch_nonce','root_pid','root_start_ticks','root_exited','owned_stopped','home_removed','probe_temp_removed')){
    if(-not $fields.ContainsKey($key)){throw "stop evidence missing field=$key role=$role"}
  }
  if($fields.run -ne $run -or $fields.role -ne $role -or $fields.launch_nonce -ne $nonce -or
     $fields.root_pid -ne $pidParts[0] -or $fields.root_start_ticks -ne $pidParts[1]){throw "stop evidence stale or foreign role=$role"}
  return $fields
}
function Confirm-Stop([string]$role) {
  $fields=Stop-Identity $role
  $exitCode=(Get-Content -LiteralPath (Join-Path $run "$role.host.exit") -Raw).Trim()
  if(Owned-Process (Join-Path $run "$role.host.pid")){throw "runtime host still live role=$role"}
  $recoveryPath=Join-Path $run "$role.recovery"
  if(Test-Path -LiteralPath $recoveryPath){
    $recovered=Read-StopRecord $recoveryPath $role
    foreach($key in @('run','role','launch_nonce','root_pid','root_start_ticks','root_exited','owned_stopped','home_removed','probe_temp_removed')){
      if(-not $recovered.ContainsKey($key)){throw "recovery evidence missing field=$key role=$role"}
    }
    foreach($key in @('run','role','launch_nonce','root_pid','root_start_ticks')){
      if($recovered[$key] -ne $fields[$key]){throw "recovery evidence stale role=$role"}
    }
    foreach($key in @('root_exited','owned_stopped','home_removed','probe_temp_removed')){
      if($recovered[$key] -ne 'True'){throw "recovery evidence failed field=$key role=$role"}
    }
    Write-Output "recovered stop role=$role original_host_exit=$exitCode owned_stopped=True home_removed=True probe_temp_removed=True"
    return
  }
  foreach($key in @('root_exited','owned_stopped','home_removed','probe_temp_removed')){
    if($fields[$key] -ne 'True'){throw "stop evidence failed field=$key role=$role"}
  }
  if($exitCode -ne '0'){throw "runtime host failed role=$role exit=$exitCode"}
  Write-Output "confirmed stop role=$role root_pid=$($fields.root_pid) root_exited=True owned_stopped=True home_removed=True probe_temp_removed=True host_exit=0"
}
function Retry-Cleanup([string]$role) {
  $fields=Stop-Identity $role
  if($fields.root_exited -ne 'True' -or $fields.termination_succeeded -ne 'True' -or -not $fields.ContainsKey('owned_instances')){
    throw "cleanup retry lacks confirmed termination identity role=$role"
  }
  if(Owned-Process (Join-Path $run "$role.host.pid") -or (Owned-Process (Join-Path $run "$role.pid"))){throw "cleanup retry process still live role=$role"}
  foreach($entry in ($fields.owned_instances -split ',')){
    if($entry -notmatch '^([0-9]+):([0-9]+)$'){throw "cleanup retry process identity invalid role=$role"}
    try{$process=Get-Process -Id ([int]$Matches[1]) -ErrorAction Stop
      if($process.StartTime.ToUniversalTime().Ticks -eq [long]$Matches[2]){throw "cleanup retry owned process still live role=$role"}}
    catch [Microsoft.PowerShell.Commands.ProcessCommandException]{}
  }
  foreach($suffix in @("temp-$role","probe-$role")){
    $target=[IO.Path]::GetFullPath((Join-Path $run $suffix))
    if(-not $target.StartsWith($run+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'cleanup target escaped run'}
    if(Test-Path -LiteralPath $target){
      $item=Get-Item -LiteralPath $target -Force
      if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'cleanup target is reparse point'}
      Remove-Item -LiteralPath $target -Recurse -Force
    }
    if(Test-Path -LiteralPath $target){throw "cleanup retry retained $suffix"}
  }
  @("run=$run","role=$role","launch_nonce=$($fields.launch_nonce)","root_pid=$($fields.root_pid)",
    "root_start_ticks=$($fields.root_start_ticks)",'root_exited=True','owned_stopped=True',
    'home_removed=True','probe_temp_removed=True')|Set-Content -LiteralPath (Join-Path $run "$role.recovery") -Encoding ascii
  Confirm-Stop $role
}
if($Action -eq 'init') {
  if(Test-Path -LiteralPath $run){throw 'Run directory already exists; never reuse prior credentials or state'}
  New-Item -ItemType Directory -Path $run,$source -Force|Out-Null
  $allow=@('package.json','package-lock.json','tsconfig.json','services/backend/pom.xml','services/backend/mvnw.cmd',
    'services/backend/mvnw','services/backend/.mvn','services/backend/src','services/agent-runtime','packages/contracts',
    'packages/dsh-tools','apps/web','deploy/s1-01','scripts/runtime.ps1','scripts/runtime_job_host.ps1','scripts/runtime_demo.ps1','scripts/asr_fixture.mjs',
    'scripts/mr3_eval.py','scripts/test_mr3_eval.py')
  foreach($item in $allow){$from=Join-Path $root $item;if(-not (Test-Path -LiteralPath $from)){throw "allowlist missing $item"}
    $to=Join-Path $source $item;New-Item -ItemType Directory -Force -Path (Split-Path $to)|Out-Null
    Copy-Item -LiteralPath $from -Destination $to -Recurse -Force}
  $suffix=(Secret 4);$broker=$null;$vip=$null
  for($candidate=0;$candidate -lt 24;$candidate++){
    $trial=Port;$paired=$trial-2
    if($paired -lt 1024 -or $script:claimedPorts.ContainsKey($paired)){continue}
    $vipSocket=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$paired)
    try{$vipSocket.Start();$broker=$trial;$vip=$paired;break}
    catch [Net.Sockets.SocketException] {continue}
    finally{$vipSocket.Stop()}
  }
  if($null -eq $broker){throw 'Broker/VIP port pair unavailable; choose a new run directory'}
  $script:claimedPorts[$vip]=$true
  $config=[ordered]@{
    PF_COMPOSE_PROJECT="pf-run-$suffix";PF_DB_ROOT_PASSWORD=(Secret 24);PF_DB_PASSWORD=(Secret 24)
    PF_REDIS_PASSWORD=(Secret 24);PF_MINIO_ACCESS_KEY=(Secret 12);PF_MINIO_SECRET_KEY=(Secret 24)
    PF_BOOTSTRAP_TOKEN=(Secret 24);PF_MINIO_BUCKET="pf-run-$suffix";PF_DB_PORT=(Port);PF_REDIS_PORT=(Port)
    PF_MINIO_PORT=(Port);PF_MINIO_CONSOLE_PORT=(Port);PF_MQ_NAMESRV_PORT=(Port)
    PF_MQ_BROKER_PORT=$broker;PF_MQ_BROKER_VIP_PORT=$vip;PF_API_PORT_RUN=(Port);PF_WORKER_PORT_RUN=(Port)
    PF_BROKER_CONFIG=(Join-Path $run 'broker.conf');PF_FFPROBE=(Get-Command ffprobe -ErrorAction Stop).Source
    PF_NODE=(Get-Command node -ErrorAction Stop).Source;PF_FFMPEG=(Get-Command ffmpeg -ErrorAction Stop).Source
    PF_AGENT_RESULT_HOLD_MS='12000'
  }
  if($UseLocalImages){
    $local=@{PF_MYSQL_IMAGE='mysql:8.0';PF_REDIS_IMAGE='redis:7.4-alpine';
      PF_MINIO_IMAGE='bitnamilegacy/minio:2025.7.23-debian-12-r5';PF_ROCKETMQ_IMAGE='apache/rocketmq:5.3.4'}
    foreach($entry in $local.GetEnumerator()){
      & docker image inspect $entry.Value --format '{{.Id}}' 1>$null 2>$null
      if($LASTEXITCODE -ne 0){throw "required local image missing: $($entry.Value)"}
      $config[$entry.Key]=$entry.Value
    }
  }
  if($UseAsrFixture){
    $config.PF_ASR_FIXTURE_PORT=Port;$config.PF_ASR_ENDPOINT="http://127.0.0.1:$($config.PF_ASR_FIXTURE_PORT)/v1/listen"
    $config.PF_ASR_API_KEY=Secret 24;$config.PF_ASR_MODEL='nova-3';$config.PF_ASR_ALLOW_LOCAL_FIXTURE='true'
    $config.PF_REVIEW_SCRIPTED_ENABLED='true'
    $config.PF_REVIEW_RESULT_HOLD_MS='2000'
  }
  $config.PF_DB_URL="jdbc:mysql://127.0.0.1:$($config.PF_DB_PORT)/pitayaflow?useSSL=false&allowPublicKeyRetrieval=true"
  $config.PF_MQ_NAMESRV="127.0.0.1:$($config.PF_MQ_NAMESRV_PORT)"
  $config.PF_MINIO_ENDPOINT="http://127.0.0.1:$($config.PF_MINIO_PORT)"
  $config.PF_AGENT_BRIDGE_URL="http://127.0.0.1:$($config.PF_API_PORT_RUN)"
  $config.GetEnumerator()|ForEach-Object{"$($_.Key)=$($_.Value)"}|Set-Content -LiteralPath $envFile -Encoding utf8
  @("brokerClusterName=DefaultCluster","brokerName=pf-run-$suffix","brokerId=0","brokerRole=ASYNC_MASTER",
    "flushDiskType=ASYNC_FLUSH","autoCreateTopicEnable=true","listenPort=$broker","brokerIP1=127.0.0.1")|Set-Content -LiteralPath $config.PF_BROKER_CONFIG -Encoding ascii
  Write-Output "Initialized isolated source/config project=$($config.PF_COMPOSE_PROJECT) image_mode=$(if($UseLocalImages){'local-cache'}else{'registry-digest'}) run=$run"
  exit 0
}
Load-Config
switch($Action) {
  'build' {
    Push-Location $source;try{& npm ci;if($LASTEXITCODE -ne 0){throw 'npm ci failed'}}finally{Pop-Location}
    Push-Location $source;try{& npm run web:build;if($LASTEXITCODE -ne 0){throw 'web build failed'}}finally{Pop-Location}
    $static=Join-Path $source 'services/backend/src/main/resources/static'
    New-Item -ItemType Directory -Force -Path $static|Out-Null
    Copy-Item -Path (Join-Path $source 'dist/web/*') -Destination $static -Recurse -Force
    Push-Location (Join-Path $source 'services/backend');try{& .\mvnw.cmd -q clean verify;if($LASTEXITCODE -ne 0){throw 'Maven verify failed'}}finally{Pop-Location}
    $dsh=Join-Path $run 'dsh';if(Test-Path -LiteralPath $dsh){throw 'DSH destination exists; inspect pin before reusing'}
    $origin=if($DshMirror){$DshMirror}else{'https://github.com/deepseek-ai/deepseek-harness.git'}
    & git clone --no-checkout $origin $dsh;if($LASTEXITCODE -ne 0){throw 'DSH clone failed'}
    & git -C $dsh config core.longpaths true;if($LASTEXITCODE -ne 0){throw 'DSH long-path config failed'}
    Push-Location $dsh;try{
      & git checkout --detach ce7073d51254c26cd63b22a60835b8f948deb447;if($LASTEXITCODE -ne 0){throw 'DSH pin checkout failed'}
      if((git rev-parse HEAD).Trim() -ne 'ce7073d51254c26cd63b22a60835b8f948deb447'){throw 'DSH pin mismatch'}
      for($attempt=1;$attempt -le 2;$attempt++){
        & corepack pnpm install --frozen-lockfile
        if($LASTEXITCODE -eq 0){break}
        if($attempt -eq 2){throw "DSH pnpm install failed after bounded retry exit=$LASTEXITCODE"}
        Write-Warning "DSH pnpm install attempt $attempt failed exit=$LASTEXITCODE; retrying isolated clone once"
        Start-Sleep -Seconds 2
      }
      for($attempt=1;$attempt -le 2;$attempt++){
        & corepack pnpm run build:lib
        if($LASTEXITCODE -eq 0){break}
        if($attempt -eq 2){throw "DSH build:lib failed after bounded retry exit=$LASTEXITCODE"}
        Write-Warning "DSH build:lib attempt $attempt failed exit=$LASTEXITCODE; retrying pinned source once"
        Start-Sleep -Seconds 2
      }
    }finally{Pop-Location}
    Write-Output 'PASS clean source Web, Java and pinned DSH build'
  }
  'start' {
    if(-not (Test-Path -LiteralPath (Join-Path $run 'dsh'))){throw 'Run build first; explicit pinned DSH required'}
    if((git -C (Join-Path $run 'dsh') rev-parse HEAD).Trim() -ne 'ce7073d51254c26cd63b22a60835b8f948deb447'){throw 'DSH pin mismatch'}
    try{Compose @('up','-d','--wait');Wait-DatabasePort;Start-Fixture;Start-Role 'api';Start-Role 'worker';Wait-Health}
    catch { $primary=$_;try{& pwsh -NoProfile -File $PSCommandPath -Action stop -RunDir $run|Out-Null
        if($LASTEXITCODE -ne 0){Write-Error -Message "startup cleanup failed exit=$LASTEXITCODE" -ErrorAction Continue}}
      catch{Write-Error -Message "startup cleanup failed: $($_.Exception.Message)" -ErrorAction Continue};throw $primary }
  }
  'health' {Wait-Health}
  'demo' {Wait-Health;$phase=if(Test-Path -LiteralPath (Join-Path $run 'private-state.json')){'resume'}else{'full'}
    & (Join-Path $source 'scripts/runtime_demo.ps1') -RunDir $run -Phase $phase;if($LASTEXITCODE -ne 0){throw 'isolated demo failed'}}
  'restart' {& $PSCommandPath -Action stop -RunDir $run;if($LASTEXITCODE -ne 0){throw 'stop before restart failed'}
    foreach($role in @('api','worker')){Remove-Item -LiteralPath (Join-Path $run "stop-$role") -ErrorAction SilentlyContinue}
    Start-Sleep -Seconds 2 # Give the host socket and Compose port bindings time to release on Windows.
    & $PSCommandPath -Action start -RunDir $run;if($LASTEXITCODE -ne 0){throw 'restart failed'}}
  'stop' {
    $errors=@()
    foreach($role in @('api','worker')){if(Owned-Process (Join-Path $run "$role.host.pid")){
      New-Item -ItemType File -Force -Path (Join-Path $run "stop-$role")|Out-Null}}
    foreach($role in @('api','worker')){for($i=0;$i -lt 70;$i++){
      if(-not (Owned-Process (Join-Path $run "$role.host.pid"))){break};Start-Sleep -Milliseconds 200}
      $owned=Owned-Process (Join-Path $run "$role.host.pid")
      if($owned){try{Stop-Process -Id $owned.Id -Force;Start-Sleep -Milliseconds 500}catch{$errors+="host force-stop failed role=$role error=$($_.Exception.GetType().Name)"}}
      if(Owned-Process (Join-Path $run "$role.host.pid")){$errors+="host stop unconfirmed role=$role"}
      if(Test-Path -LiteralPath (Join-Path $run "$role.launch")){
        try{Confirm-Stop $role}catch{$errors+=$_.Exception.Message}
      }
    }
    $fixture=Owned-Process (Join-Path $run 'asr-fixture.pid')
    if($fixture){try{Stop-Process -Id $fixture.Id -Force;Start-Sleep -Milliseconds 200}catch{$errors+="fixture stop failed error=$($_.Exception.GetType().Name)"}}
    if(Owned-Process (Join-Path $run 'asr-fixture.pid')){$errors+='fixture stop unconfirmed'}
    try{Compose @('down')}catch{$errors+=$_.Exception.Message}
    if($errors){throw ($errors -join '; ')}
    Write-Output 'Stopped isolated processes and containers; volumes retained'
  }
  'worker-fault' {
    $owned=Owned-Process (Join-Path $run 'worker.pid');if(-not $owned){throw 'owned Worker PID unavailable'}
    Stop-Process -Id $owned.Id -Force
    for($i=0;$i -lt 70;$i++){
      if(-not (Owned-Process (Join-Path $run 'worker.host.pid'))){break};Start-Sleep -Milliseconds 200
    }
    Confirm-Stop 'worker'
  }
  'recover-worker' {
    if(Owned-Process (Join-Path $run 'worker.host.pid')){throw 'old Worker supervisor still owns process'}
    Remove-Item -LiteralPath (Join-Path $run 'stop-worker') -ErrorAction SilentlyContinue
    Start-Role 'worker';Wait-Health
  }
  'retry-cleanup' {Retry-Cleanup $CleanupRole}
  'status' {foreach($role in @('api','worker')){Write-Output "$role host_owned=$([bool](Owned-Process (Join-Path $run "$role.host.pid"))) root_owned=$([bool](Owned-Process (Join-Path $run "$role.pid")))"}}
}
