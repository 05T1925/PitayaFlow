param(
  [ValidateSet('prepare','resume')][string]$Phase='prepare',
  [string]$StatePath='.local/s1-demo-state.json',
  [string]$ExistingIdentityStatePath=''
)
$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '..')
$stateFile=Join-Path $root $StatePath
$base='http://127.0.0.1:18080/api/v1'
function JsonPost([string]$path,[object]$body,[hashtable]$headers=@{}) {
  Invoke-RestMethod -Method Post -Uri "$base$path" -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Compress)
}
function Status([string]$method,[string]$path,[hashtable]$headers) {
  $r=Invoke-WebRequest -Method $method -Uri "$base$path" -Headers $headers -SkipHttpErrorCheck
  return [int]$r.StatusCode
}
function Require([bool]$ok,[string]$label) {if(-not $ok){throw "Failed: $label"}; Write-Output "PASS $label"}
function Auth([string]$token) {return @{Authorization="Bearer $token"}}
function FreshPassword {return ([Guid]::NewGuid().ToString('N')+'Aa1!')}
if($Phase -eq 'prepare') {
  if(Test-Path -LiteralPath $stateFile){throw 'Existing local demo state. Use -Phase resume or remove only this test state after resetting its isolated database.'}
  if($ExistingIdentityStatePath) {
    $existing=Get-Content (Join-Path $root $ExistingIdentityStatePath) -Raw -Encoding utf8|ConvertFrom-Json
    $admin=$existing.admin; $ap=$existing.admin_password
    $u1=$existing.user1; $p1=$existing.password1; $u2=$existing.user2; $p2=$existing.password2
    $one=JsonPost '/login' @{username=$u1;password=$p1}; $two=JsonPost '/login' @{username=$u2;password=$p2}
  } else {
    $vars=@{}; Get-Content (Join-Path $root '.local/s1.env') -Encoding utf8 | ForEach-Object {if($_ -match '^([A-Z0-9_]+)=(.*)$'){$vars[$Matches[1]]=$Matches[2]}}
    $suffix=[Guid]::NewGuid().ToString('N').Substring(0,8)
    $admin="admin_$suffix"; $ap=FreshPassword
    JsonPost '/bootstrap' @{token=$vars.PF_BOOTSTRAP_TOKEN;username=$admin;password=$ap}|Out-Null
    $a=JsonPost '/login' @{username=$admin;password=$ap}
    $u1="user1_$suffix"; $u2="user2_$suffix"; $p1=FreshPassword; $p2=FreshPassword
    JsonPost '/users' @{username=$u1;password=$p1} (Auth $a.token)|Out-Null
    JsonPost '/users' @{username=$u2;password=$p2} (Auth $a.token)|Out-Null
    $one=JsonPost '/login' @{username=$u1;password=$p1}; $two=JsonPost '/login' @{username=$u2;password=$p2}
  }
  Require ((Status 'POST' '/users' @{}) -eq 401) 'public registration closed'
  Require ((Status 'GET' '/media' (Auth $one.token)) -eq 200) 'account one session'
  Require ((Status 'GET' '/media' (Auth $two.token)) -eq 200) 'account two session'
  $run=[Guid]::NewGuid().ToString('N')
  $wav=Join-Path $root ".local/s1-tone-$run.wav"; $mp4=Join-Path $root ".local/s1-tone-$run.mp4"; $fake=Join-Path $root ".local/s1-fake-$run.wav"
  & ffmpeg -v error -f lavfi -i 'sine=frequency=440:duration=2' -y $wav
  if($LASTEXITCODE -ne 0){throw 'ffmpeg WAV fixture failed'}
  & ffmpeg -v error -f lavfi -i 'sine=frequency=440:duration=2' -c:a aac -y $mp4
  if($LASTEXITCODE -ne 0){throw 'ffmpeg MP4 fixture failed'}
  Set-Content -LiteralPath $fake -Value 'not audio' -Encoding ascii
  $media=@()
  foreach($file in @($wav,$mp4)) {
    $m=Invoke-RestMethod -Method Post -Uri "$base/media" -Headers (Auth $one.token) -Form @{file=Get-Item -LiteralPath $file}
    $media+=@{id=$m.media_id;file=$file;kind=$m.kind}
    Require ($m.content_version -eq 1) 'immutable media version'
    foreach($suffixPath in @('', '/download', '/probe')) {
      $method=if($suffixPath -eq '/probe'){'POST'}else{'GET'}
      $headers=Auth $two.token
      if($suffixPath -eq '/probe'){$headers['Idempotency-Key']=[Guid]::NewGuid().ToString('N')}
      Require ((Status $method "/media/$($m.media_id)$suffixPath" $headers) -eq 404) "account two cannot access $suffixPath"
    }
    Require ((Status 'DELETE' "/media/$($m.media_id)" (Auth $two.token)) -eq 404) 'account two cannot delete'
  }
  $bad=Invoke-WebRequest -Method Post -Uri "$base/media" -Headers (Auth $one.token) -Form @{file=Get-Item -LiteralPath $fake} -SkipHttpErrorCheck
  Require ($bad.StatusCode -eq 400) 'disguised audio rejected'
  foreach($m in $media) {
    $idem=[Guid]::NewGuid().ToString('N')
    $j=JsonPost "/media/$($m.id)/probe" @{} ((Auth $one.token)+@{'Idempotency-Key'=$idem})
    $again=JsonPost "/media/$($m.id)/probe" @{} ((Auth $one.token)+@{'Idempotency-Key'=$idem})
    Require ($j.task_id -eq $again.task_id) 'idempotent task acceptance'
    Require ((Status 'GET' "/jobs/$($j.task_id)" (Auth $two.token)) -eq 404) 'account two cannot query task'
    $m.job=$j.task_id
  }
  @{admin=$admin;adminPassword=$ap;user1=$u1;password1=$p1;token1=$one.token;user2=$u2;password2=$p2;token2=$two.token;media=$media}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $stateFile -Encoding utf8
  Write-Output 'Prepared two real private media and tasks. Restart API and Worker, then run -Phase resume.'
  exit 0
}
if(-not (Test-Path -LiteralPath $stateFile)){throw 'Run -Phase prepare first.'}
$s=Get-Content -LiteralPath $stateFile -Raw -Encoding utf8|ConvertFrom-Json
$one=JsonPost '/login' @{username=$s.user1;password=$s.password1}; $two=JsonPost '/login' @{username=$s.user2;password=$s.password2}
foreach($m in $s.media) {
  $result=$null
  for($i=0;$i -lt 40;$i++) {
    $result=Invoke-RestMethod -Method Get -Uri "$base/jobs/$($m.job)" -Headers (Auth $one.token)
    if($result.status -in @('SUCCEEDED','FAILED','CANCELLED')){break}
    Start-Sleep -Milliseconds 500
  }
  Require ($result.status -eq 'SUCCEEDED') "persisted $($m.kind) probe after restart"
  Require ($result.result.media_version -eq 1 -and $result.result.probe.kind -eq $m.kind) 'version-bound real probe result'
  $download=Join-Path $root ".local/s1-download-$($m.id).bin"
  Invoke-WebRequest -Method Get -Uri "$base/media/$($m.id)/download" -Headers (Auth $one.token) -OutFile $download
  Require ((Get-FileHash $download -Algorithm SHA256).Hash -eq (Get-FileHash $m.file -Algorithm SHA256).Hash) 'private download bytes'
  Require ((Status 'GET' "/media/$($m.id)/download" (Auth $two.token)) -eq 404) 'account two download denied'
  Require ((Status 'DELETE' "/media/$($m.id)" (Auth $one.token)) -eq 204) 'owner deletion'
  Require ((Status 'GET' "/media/$($m.id)" (Auth $one.token)) -eq 404) 'deleted media inaccessible'
}
Write-Output 'PASS S1-01 REST replay after restart'
