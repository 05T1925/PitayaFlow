param(
  [ValidateSet('prepare','resume')][string]$Phase='prepare',
  [string]$IdentityState='.local/s1-state.json',
  [string]$StatePath='.local/s1-int-state.json'
)
$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '..')
$base='http://127.0.0.1:18080'
$identity=Get-Content (Join-Path $root $IdentityState) -Raw -Encoding utf8|ConvertFrom-Json
$one=Invoke-RestMethod -Method Post -Uri "$base/api/v1/login" -ContentType 'application/json' -Body (@{username=$identity.user1;password=$identity.password1}|ConvertTo-Json -Compress)
$two=Invoke-RestMethod -Method Post -Uri "$base/api/v1/login" -ContentType 'application/json' -Body (@{username=$identity.user2;password=$identity.password2}|ConvertTo-Json -Compress)
$auth1=@{Authorization="Bearer $($one.token)"};$auth2=@{Authorization="Bearer $($two.token)"}
function Status([string]$path,[hashtable]$headers) {return [int](Invoke-WebRequest -Uri "$base$path" -Headers $headers -SkipHttpErrorCheck).StatusCode}
function Need([bool]$value,[string]$label){if(-not $value){throw "FAIL $label"};Write-Output "PASS $label"}
$stateFile=Join-Path $root $StatePath
if($Phase -eq 'prepare') {
  if(Test-Path $stateFile){throw 'Existing INT test state. Use resume or a new StatePath; never overwrite another run.'}
  $wav=Join-Path $root ('.local/s1-int-tone-'+[Guid]::NewGuid().ToString('N')+'.wav')
  & ffmpeg -v error -f lavfi -i 'sine=frequency=440:duration=2' -y $wav
  if($LASTEXITCODE -ne 0){throw 'ffmpeg synthetic tone failed'}
  $media=Invoke-RestMethod -Method Post -Uri "$base/api/v1/media" -Headers $auth1 -Form @{file=Get-Item -LiteralPath $wav}
  $source=Invoke-RestMethod -Method Post -Uri "$base/api/v1/media/$($media.media_id)/synthetic-sources" -Headers $auth1 -ContentType 'application/json' -Body '{"fixture_id":"a"}'
  $original=Invoke-RestMethod -Uri "$base/api/v1/synthetic-sources/$($source.source_id)/revisions/1" -Headers $auth1
  Need ($original.source_sha256 -eq $source.source_sha256 -and $original.content.case_id -eq 'PF-C001-synthetic') 'original source revision retrievable'
  Need ((Status "/api/v1/synthetic-sources/$($source.source_id)" $auth2) -eq 404) 'cross-account source denied'
  Need ((Status "/api/v1/synthetic-sources/$($source.source_id)/revisions/1" $auth2) -eq 404) 'cross-account historical revision denied'
  $otherMedia=Invoke-RestMethod -Method Post -Uri "$base/api/v1/media" -Headers $auth2 -Form @{file=Get-Item -LiteralPath $wav}
  $cross=$auth2.Clone();$cross['Idempotency-Key']=[Guid]::NewGuid().ToString('N')
  $crossResult=Invoke-WebRequest -Method Post -Uri "$base/api/v1/media/$($otherMedia.media_id)/synthetic-analysis" -Headers $cross -ContentType 'application/json' -Body (@{source_id=$source.source_id;owner_id=$one.user_id}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
  Need ($crossResult.StatusCode -eq 404) 'forged owner and cross-resource source denied'
  Need ((Status "/internal/agent/grants/00000000-0000-0000-0000-000000000000/source?case_id=PF-C001-synthetic&owner_id=$($one.user_id)" @{}) -in @(401,404)) 'direct internal read denied'
  $headers=$auth1.Clone();$headers['Idempotency-Key']=[Guid]::NewGuid().ToString('N')
  $job=Invoke-RestMethod -Method Post -Uri "$base/api/v1/media/$($media.media_id)/synthetic-analysis" -Headers $headers -ContentType 'application/json' -Body (@{source_id=$source.source_id}|ConvertTo-Json -Compress)
  $again=Invoke-RestMethod -Method Post -Uri "$base/api/v1/media/$($media.media_id)/synthetic-analysis" -Headers $headers -ContentType 'application/json' -Body (@{source_id=$source.source_id}|ConvertTo-Json -Compress)
  Need ($again.task_id -eq $job.task_id) 'idempotent agent acceptance'
  Need ((Status "/api/v1/jobs/$($job.task_id)" $auth2) -eq 404) 'cross-account result denied'
  @{media_id=$media.media_id;source_id=$source.source_id;source_sha256=$source.source_sha256;task_id=$job.task_id;wav=$wav}|ConvertTo-Json|Set-Content -LiteralPath $stateFile -Encoding utf8
  Write-Output 'Prepared synthetic WAV and human-written fixture. Wait for worker then restart API/Worker and run -Phase resume.'
  exit 0
}
if(-not (Test-Path $stateFile)){throw 'Missing INT state; run prepare first.'}
$saved=Get-Content $stateFile -Raw -Encoding utf8|ConvertFrom-Json
$result=$null
for($i=0;$i -lt 60;$i++){
  $result=Invoke-RestMethod -Uri "$base/api/v1/jobs/$($saved.task_id)" -Headers $auth1
  if($result.status -in @('SUCCEEDED','FAILED','CANCELLED')){break}
  Start-Sleep -Milliseconds 500
}
Need ($result.status -eq 'SUCCEEDED') 'DSH job persisted after restart'
Need ($result.result.status -eq 'validated' -and $result.result.source_sha256 -eq $saved.source_sha256) 'version-bound synthetic result'
Need ((Status "/api/v1/jobs/$($saved.task_id)" $auth2) -eq 404) 'cross-account result denied after restart'
Need ((Status "/api/v1/media/$($saved.media_id)/download" $auth2) -eq 404) 'cross-account media denied'
Write-Output 'PASS INT-01 synthetic private end-to-end'
