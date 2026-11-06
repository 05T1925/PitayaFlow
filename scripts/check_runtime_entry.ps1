param()
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$scratch=Join-Path $root ('.local/runtime-entry-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch -Force|Out-Null
try {
  $original=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime.ps1') -Raw
  $scriptDir=Join-Path $scratch 'scripts';New-Item -ItemType Directory -Path $scriptDir|Out-Null
  $stub=@'
param([string]$Action,[string]$RunDir,[string]$DshMirror,[switch]$UseLocalImages,[switch]$UseAsrFixture)
Add-Content -LiteralPath $env:PF_TEST_TRACE -Value $Action
if($Action -eq $env:PF_TEST_FAIL){exit 7}
exit 0
'@
  $stubPath=Join-Path $scriptDir 'stub.ps1';Set-Content -LiteralPath $stubPath -Value $stub
  $copy=$original.Replace('& pwsh -NoProfile -File $PSCommandPath -Action $phase', '& pwsh -NoProfile -File (Join-Path $PSScriptRoot ''stub.ps1'') -Action $phase').Replace('& pwsh -NoProfile -File $PSCommandPath -Action stop', '& pwsh -NoProfile -File (Join-Path $PSScriptRoot ''stub.ps1'') -Action stop')
  $copyPath=Join-Path $scriptDir 'runtime.ps1';Set-Content -LiteralPath $copyPath -Value $copy
  foreach($case in @(@('normal','','init,build,start,demo,stop',0),@('demo','demo','init,build,start,demo,stop',1),@('stop','stop','init,build,start,demo,stop',1),@('init','init','init',1))) {
    $env:PF_TEST_TRACE=Join-Path $scratch ($case[0]+'.trace');$env:PF_TEST_FAIL=$case[1]
    & pwsh -NoProfile -File $copyPath -Action all -RunDir (Join-Path $scratch ('.local/'+$case[0])) *> (Join-Path $scratch ($case[0]+'.output'))
    $actual=(Get-Content -LiteralPath $env:PF_TEST_TRACE) -join ','
    if($actual -ne $case[2] -or $LASTEXITCODE -ne $case[3]){throw "all case=$($case[0]) actions=$actual exit=$LASTEXITCODE"}
    if($case[0] -eq 'demo' -and -not ((Get-Content (Join-Path $scratch 'demo.output') -Raw) -match 'primary:.*demo')){throw 'primary demo error lost'}
    if($case[0] -eq 'stop' -and -not ((Get-Content (Join-Path $scratch 'stop.output') -Raw) -match 'cleanup:.*stop')){throw 'cleanup stop error lost'}
  }
  $check=$original.Replace("if(`$Action -eq 'init') {", "Confirm-Stop 'worker'; exit 0`nif(`$Action -eq 'init') {")
  $checkPath=Join-Path $scriptDir 'check-stop.ps1';Set-Content -LiteralPath $checkPath -Value $check
  $run=Join-Path $scratch '.local/evidence';New-Item -ItemType Directory -Path $run -Force|Out-Null
  'PF_RUNTIME_TEST=1'|Set-Content (Join-Path $run 'private.env')
  '200,300'|Set-Content (Join-Path $run 'worker.pid');$nonce=[guid]::NewGuid().ToString();$nonce|Set-Content (Join-Path $run 'worker.launch')
  $valid="run=$run`nrole=worker`nlaunch_nonce=$nonce`nroot_pid=200`nroot_start_ticks=300`nroot_exited=True`nowned_stopped=True`nhome_removed=True`nprobe_temp_removed=True`n"
  foreach($case in @('valid','stale','false','missing','absent','nonzero')) {
    $record=switch($case){'stale'{$valid.Replace($nonce,[guid]::NewGuid().ToString())};'false'{$valid.Replace('home_removed=True','home_removed=False')};'missing'{$valid.Replace('root_exited=True','')};default{$valid}}
    Set-Content (Join-Path $run 'worker.stop') $record;Set-Content (Join-Path $run 'worker.host.exit') $(if($case -eq 'nonzero'){'2'}else{'0'})
    if($case -eq 'absent'){Remove-Item -LiteralPath (Join-Path $run 'worker.stop')}
    & pwsh -NoProfile -File $checkPath -Action status -RunDir $run *> (Join-Path $scratch ($case+'.output'))
    if(($LASTEXITCODE -eq 0) -ne ($case -eq 'valid')){throw "stop evidence case=$case exit=$LASTEXITCODE output=$((Get-Content (Join-Path $scratch ($case+'.output')) -Raw))"}
  }
  Set-Content (Join-Path $run 'worker.stop') $valid;Set-Content (Join-Path $run 'worker.host.exit') '0'
  $live=Start-Process pwsh -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 15') -WindowStyle Hidden -PassThru
  try {
    "$($live.Id),$($live.StartTime.ToUniversalTime().Ticks)"|Set-Content (Join-Path $run 'worker.host.pid')
    & pwsh -NoProfile -File $checkPath -Action status -RunDir $run *> (Join-Path $scratch 'host-live.output')
    if($LASTEXITCODE -eq 0){throw 'live host incorrectly confirmed'}
  } finally {if(-not $live.HasExited){Stop-Process -Id $live.Id -Force}}
  $old=$valid.Replace('owned_stopped=True','owned_stopped=False').Replace('home_removed=True','home_removed=False').Replace('probe_temp_removed=True','probe_temp_removed=False')+
    "termination_succeeded=False`nowned_instances=200:300`n"
  Set-Content (Join-Path $run 'worker.stop') $old;Set-Content (Join-Path $run 'worker.host.exit') '2'
  New-Item -ItemType Directory -Path (Join-Path $run 'temp-worker'),(Join-Path $run 'probe-worker') -Force|Out-Null
  & pwsh -NoProfile -File $copyPath -Action retry-cleanup -RunDir $run -CleanupRole worker *> (Join-Path $scratch 'tree-failed.output')
  if($LASTEXITCODE -eq 0 -or -not (Test-Path (Join-Path $run 'temp-worker'))){throw 'tree failure cleanup was not retained'}
  $live=Start-Process pwsh -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 15') -WindowStyle Hidden -PassThru
  try {
    $identity="$($live.Id):$($live.StartTime.ToUniversalTime().Ticks)"
    $confirmed=$old.Replace('termination_succeeded=False','termination_succeeded=True').Replace('200:300',$identity)
    Set-Content (Join-Path $run 'worker.stop') $confirmed
    & pwsh -NoProfile -File $copyPath -Action retry-cleanup -RunDir $run -CleanupRole worker *> (Join-Path $scratch 'descendant-live.output')
    if($LASTEXITCODE -eq 0 -or -not (Test-Path (Join-Path $run 'temp-worker'))){throw 'live descendant cleanup was not retained'}
  } finally {if(-not $live.HasExited){Stop-Process -Id $live.Id -Force}}
  $injected=$copy.Replace('Remove-Item -LiteralPath $target -Recurse -Force',"if(`$env:PF_TEST_DELETE_FAIL -eq '1'){throw 'injected delete failure'}; Remove-Item -LiteralPath `$target -Recurse -Force")
  $injectedPath=Join-Path $scriptDir 'retry-delete-fail.ps1';Set-Content -LiteralPath $injectedPath -Value $injected
  $env:PF_TEST_DELETE_FAIL='1'
  & pwsh -NoProfile -File $injectedPath -Action retry-cleanup -RunDir $run -CleanupRole worker *> (Join-Path $scratch 'delete-failed.output')
  Remove-Item Env:PF_TEST_DELETE_FAIL
  if($LASTEXITCODE -eq 0 -or -not (Test-Path (Join-Path $run 'temp-worker')) -or (Test-Path (Join-Path $run 'worker.recovery'))){throw 'delete failure was not retained'}
  & pwsh -NoProfile -File $copyPath -Action retry-cleanup -RunDir $run -CleanupRole worker *> (Join-Path $scratch 'retry.output')
  if($LASTEXITCODE -ne 0 -or (Test-Path (Join-Path $run 'temp-worker')) -or (Test-Path (Join-Path $run 'probe-worker')) -or
    -not (Test-Path (Join-Path $run 'worker.recovery'))){throw "confirmed retry cleanup failed output=$((Get-Content (Join-Path $scratch 'retry.output') -Raw))"}
  & pwsh -NoProfile -File $copyPath -Action retry-cleanup -RunDir $run -CleanupRole worker *> (Join-Path $scratch 'retry-again.output')
  if($LASTEXITCODE -ne 0){throw 'idempotent retry cleanup failed'}
  $startup=Join-Path $scratch '.local/startup-failure'
  $target=Join-Path $startup 'source/services/backend/target';New-Item -ItemType Directory -Path $target -Force|Out-Null
  'PF_RUNTIME_TEST=1'|Set-Content (Join-Path $startup 'private.env')
  'invalid jar'|Set-Content (Join-Path $target 'backend-0.1.0-SNAPSHOT.jar')
  $startupNonce=[guid]::NewGuid().ToString()
  & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'runtime_job_host.ps1') -Role worker -RunDir $startup -LaunchNonce $startupNonce *> (Join-Path $scratch 'host-startup.output')
  $startupStop=Get-Content (Join-Path $startup 'worker.stop') -Raw
  if($LASTEXITCODE -ne 0 -or $startupStop -notmatch 'root_exited=True' -or $startupStop -notmatch 'owned_stopped=True' -or
      $startupStop -notmatch 'home_removed=True' -or $startupStop -notmatch 'probe_temp_removed=True' -or
      (Test-Path (Join-Path $startup 'temp-worker'))){throw "real Job Object startup failure cleanup unconfirmed: $((Get-Content (Join-Path $scratch 'host-startup.output') -Raw))"}
  $childRun=Join-Path $scratch '.local/delayed-descendant';$childTarget=Join-Path $childRun 'source/services/backend/target'
  New-Item -ItemType Directory -Path $childTarget -Force|Out-Null
  'PF_RUNTIME_TEST=1'|Set-Content (Join-Path $childRun 'private.env')
  $javaSource=Join-Path $childRun 'SpawnChild.java'
  'public class SpawnChild { public static void main(String[] args) throws Exception { new ProcessBuilder("pwsh","-NoProfile","-Command","Start-Sleep -Seconds 15").start(); Thread.sleep(800); } }'|
    Set-Content -LiteralPath $javaSource -Encoding ascii
  & javac $javaSource;if($LASTEXITCODE -ne 0){throw 'delayed child fixture javac failed'}
  Push-Location $childRun;try{& jar --create --file (Join-Path $childTarget 'backend-0.1.0-SNAPSHOT.jar') --main-class SpawnChild SpawnChild.class
    if($LASTEXITCODE -ne 0){throw 'delayed child fixture jar failed'}}finally{Pop-Location}
  & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'runtime_job_host.ps1') -Role worker -RunDir $childRun -LaunchNonce ([guid]::NewGuid().ToString()) *> (Join-Path $scratch 'host-child.output')
  $childStop=Get-Content (Join-Path $childRun 'worker.stop') -Raw
  $ownedLine=($childStop -split "`n"|Where-Object{$_ -match '^owned_instances='})
  if($LASTEXITCODE -ne 0 -or $childStop -notmatch 'owned_stopped=True' -or
      (@(($ownedLine -replace '^owned_instances=','') -split ',').Count -lt 2)){
    throw "real delayed descendant stop unconfirmed: $((Get-Content (Join-Path $scratch 'host-child.output') -Raw))"
  }
  Write-Output 'PASS all normal/demo/stop/init; stop evidence; tree/live/delete failures retained; recovery; real failed Java startup and delayed descendant'
} finally {
  Remove-Item Env:PF_TEST_TRACE,Env:PF_TEST_FAIL -ErrorAction SilentlyContinue
  $resolved=[IO.Path]::GetFullPath($scratch)
  if(-not $resolved.StartsWith((Join-Path $root '.local')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
    throw 'runtime test scratch escaped repository .local'
  }
  Remove-Item -LiteralPath $scratch -Recurse -Force
}
