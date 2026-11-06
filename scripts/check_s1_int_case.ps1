param(
  [ValidateSet('invalid','malformed','forbidden','crash','unknown','revision','cancel','deadline','delete')][string]$Kind,
  [string]$IdentityState='.local/s1-state.json',
  [string]$PositiveState='.local/s1-int-state.json'
)
$ErrorActionPreference='Stop'
$root=Resolve-Path (Join-Path $PSScriptRoot '..')
$identity=Get-Content (Join-Path $root $IdentityState) -Raw -Encoding utf8|ConvertFrom-Json
$positive=Get-Content (Join-Path $root $PositiveState) -Raw -Encoding utf8|ConvertFrom-Json
$login=Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/v1/login' -ContentType 'application/json' -Body (@{username=$identity.user1;password=$identity.password1}|ConvertTo-Json -Compress)
$base='http://127.0.0.1:18080/api/v1';$auth=@{Authorization="Bearer $($login.token)"}
$mediaId=$positive.media_id
if($Kind -eq 'delete') {
  $copy=Invoke-RestMethod -Method Post -Uri "$base/media" -Headers $auth -Form @{file=Get-Item -LiteralPath $positive.wav}
  $mediaId=$copy.media_id
}
$fixture=if($Kind -eq 'unknown'){'unknown'}else{'a'}
$source=Invoke-RestMethod -Method Post -Uri "$base/media/$mediaId/synthetic-sources" -Headers $auth -ContentType 'application/json' -Body (@{fixture_id=$fixture}|ConvertTo-Json -Compress)
$headers=$auth.Clone();$headers['Idempotency-Key']=[Guid]::NewGuid().ToString('N')
$job=Invoke-RestMethod -Method Post -Uri "$base/media/$mediaId/synthetic-analysis" -Headers $headers -ContentType 'application/json' -Body (@{source_id=$source.source_id}|ConvertTo-Json -Compress)
if($Kind -in @('revision','cancel','deadline','delete')) {
  $vars=@{};Get-Content (Join-Path $root '.local/s1.env')|ForEach-Object{if($_ -match '^([A-Z0-9_]+)=(.*)$'){$vars[$Matches[1]]=$Matches[2]}}
  $ready=$false
  for($i=0;$i -lt 80;$i++){
    $value=docker exec -e "MYSQL_PWD=$($vars.PF_DB_PASSWORD)" pf-s1-01-mysql-1 mysql -u pf_s1 -D pitayaflow -N -e "SELECT COUNT(*) FROM pf_agent_grant WHERE job_id='$($job.task_id)' AND state='READY'"
    if($value -eq '1'){$ready=$true;break};Start-Sleep -Milliseconds 500
  }
  if(-not $ready){throw "FAIL ${Kind}: real DSH READY barrier not reached"}
  Write-Output 'PASS real DSH returned and is held before Java publication'
  if($Kind -eq 'revision') {
    $updated=Invoke-RestMethod -Method Post -Uri "$base/synthetic-sources/$($source.source_id)/revisions" -Headers $auth -ContentType 'application/json' -Body '{"fixture_id":"b"}'
    if($updated.revision -ne 2){throw 'FAIL immutable revision append'}
    $original=Invoke-RestMethod -Uri "$base/synthetic-sources/$($source.source_id)/revisions/1" -Headers $auth
    if($original.content.case_id -ne 'PF-C001-synthetic' -or $original.source_sha256 -ne $source.source_sha256){throw 'FAIL original revision changed'}
    $repeat=Invoke-WebRequest -Method Post -Uri "$base/media/$mediaId/synthetic-analysis" -Headers $headers -ContentType 'application/json' -Body (@{source_id=$source.source_id}|ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if($repeat.StatusCode -ne 409){throw 'FAIL same idempotency key with changed source was accepted'}
  } elseif($Kind -eq 'cancel') {Invoke-RestMethod -Method Post -Uri "$base/jobs/$($job.task_id)/cancel" -Headers $auth|Out-Null}
  elseif($Kind -eq 'deadline') {docker exec -e "MYSQL_PWD=$($vars.PF_DB_PASSWORD)" pf-s1-01-mysql-1 mysql -u pf_s1 -D pitayaflow -N -e "UPDATE pf_job SET deadline_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id='$($job.task_id)'"|Out-Null}
  else {Invoke-RestMethod -Method Delete -Uri "$base/media/$mediaId" -Headers $auth|Out-Null}
}
$result=$null
for($i=0;$i -lt 100;$i++){
  $result=Invoke-RestMethod -Uri "$base/jobs/$($job.task_id)" -Headers $auth
  if($result.status -in @('SUCCEEDED','FAILED','CANCELLED')){break}
  Start-Sleep -Milliseconds 500
}
$expected=if($Kind -eq 'unknown'){'SUCCEEDED'}elseif($Kind -in @('cancel','delete')){'CANCELLED'}else{'FAILED'}
if($result.status -ne $expected){throw "FAIL $Kind expected $expected, got $($result.status) $($result.failure_code)"}
if($Kind -ne 'unknown' -and $null -ne $result.result){throw "FAIL $Kind published a rejected result"}
if($Kind -eq 'unknown' -and ($result.result.status -ne 'await_confirmation' -or $result.result.assessment)) {throw 'FAIL unknown role produced deterministic assessment'}
if($Kind -in @('invalid','malformed','forbidden') -and $result.failure_code -ne 'DRAFT_REJECTED'){throw "FAIL $Kind failure code: $($result.failure_code)"}
if($Kind -eq 'revision' -and $result.failure_code -ne 'SOURCE_STALE'){throw "FAIL revision failure code: $($result.failure_code)"}
if($Kind -eq 'crash' -and $result.failure_code -ne 'AGENT_FAILED'){throw "FAIL crash failure code: $($result.failure_code)"}
if($Kind -eq 'deadline' -and $result.failure_code -ne 'DEADLINE'){throw "FAIL deadline failure code: $($result.failure_code)"}
Write-Output "PASS $Kind status=$($result.status) failure=$($result.failure_code)"
