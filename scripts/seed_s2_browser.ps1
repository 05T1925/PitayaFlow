param([Parameter(Mandatory)][string]$RunDir)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=[IO.Path]::GetFullPath($RunDir)
if(-not $run.StartsWith((Join-Path $root '.local')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
  throw 'browser seed must use a dedicated repository .local run'
}
$file=Join-Path $run 'private.env'
if(-not (Test-Path -LiteralPath $file)){throw 'isolated configuration missing'}
Get-Content -LiteralPath $file -Encoding utf8|ForEach-Object{if($_ -match '^([A-Z0-9_]+)=(.*)$'){
  [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$suffix=[guid]::NewGuid().ToString('N').Substring(0,8)
$users=@("s2a_$suffix","s2b_$suffix")
$password='S2BrowserFixture2026!'
function Hash-Password([string]$raw){
  $salt=[byte[]]::new(16);[Security.Cryptography.RandomNumberGenerator]::Fill($salt)
  $derived=[Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($raw,$salt,210000,[Security.Cryptography.HashAlgorithmName]::SHA256,32)
  return 'pbkdf2$210000$'+[Convert]::ToBase64String($salt).TrimEnd('=')+'$'+[Convert]::ToBase64String($derived).TrimEnd('=')
}
$container="$($env:PF_COMPOSE_PROJECT)-mysql-1"
foreach($name in $users){
  $hash=Hash-Password $password
  "INSERT INTO pf_user(username,password_hash,role) VALUES('$name','$hash','USER');"|
    docker exec -i -e "MYSQL_PWD=$($env:PF_DB_ROOT_PASSWORD)" $container mysql -uroot pitayaflow
  if($LASTEXITCODE -ne 0){throw 'test account insert failed'}
}
$base="http://127.0.0.1:$($env:PF_API_PORT_RUN)/api/v1"
function Auth([string]$name){
  $body=@{username=$name;password=$password}|ConvertTo-Json -Compress
  $login=Invoke-RestMethod -Method Post -Uri "$base/login" -ContentType 'application/json' -Body $body
  return @{Authorization="Bearer $($login.token)"}
}
$fixtures=Join-Path $run 'fixtures'
$wav=Join-Path $fixtures 'synthetic-tone.wav';$mp4=Join-Path $fixtures 'synthetic-tone.mp4'
if(-not (Test-Path -LiteralPath $wav) -or -not (Test-Path -LiteralPath $mp4)){throw 'synthetic audio fixtures missing'}
$a=Auth $users[0];$b=Auth $users[1]
$mediaA=Invoke-RestMethod -Method Post -Uri "$base/media" -Headers $a -Form @{file=Get-Item -LiteralPath $wav}
$mediaB=Invoke-RestMethod -Method Post -Uri "$base/media" -Headers $b -Form @{file=Get-Item -LiteralPath $mp4}
$denied=Invoke-WebRequest -Uri "$base/media/$($mediaB.media_id)/play" -Headers $a -SkipHttpErrorCheck
if($denied.StatusCode -ne 404){throw 'cross-account browser fixture is not private'}
Write-Output "BROWSER_USER_A=$($users[0])"
Write-Output "BROWSER_USER_B=$($users[1])"
Write-Output "MEDIA_A_WAV=$($mediaA.media_id)"
Write-Output "MEDIA_B_MP4=$($mediaB.media_id)"
Write-Output "API_PORT=$($env:PF_API_PORT_RUN)"
