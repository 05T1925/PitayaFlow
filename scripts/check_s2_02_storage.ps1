param([Parameter(Mandatory)][string]$RunDir)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$run=[IO.Path]::GetFullPath($RunDir)
if(-not $run.StartsWith((Join-Path $root '.local')+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){
  throw 'RunDir must be a dedicated repository .local child'
}
$config=Join-Path $run 'private.env'
if(-not (Test-Path -LiteralPath $config)){throw 'isolated configuration missing'}
Get-Content -LiteralPath $config -Encoding utf8|ForEach-Object{
  if($_ -match '^([A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:PF_S1_R2_INTEGRATION='1'
$compose=Join-Path $run 'source/deploy/s1-01/compose.yml'
$primary=$null;$cleanup=$null
try {
  & docker compose --env-file $config -f $compose up -d --wait mysql minio
  if($LASTEXITCODE -ne 0){throw "isolated MySQL/MinIO start failed exit=$LASTEXITCODE"}
  Push-Location (Join-Path $root 'services/backend')
  try{& .\mvnw.cmd -q -Dtest=S102StorageTest test
    if($LASTEXITCODE -ne 0){throw "real S102StorageTest failed exit=$LASTEXITCODE"}
  }finally{Pop-Location}
}catch{$primary=$_}
finally {
  try{& docker compose --env-file $config -f $compose down --remove-orphans
    if($LASTEXITCODE -ne 0){throw "isolated Compose down failed exit=$LASTEXITCODE"}
  }catch{$cleanup=$_}
}
if($primary -or $cleanup){
  if($primary){Write-Error -Message "primary: $($primary.Exception.Message)" -ErrorAction Continue}
  if($cleanup){Write-Error -Message "cleanup: $($cleanup.Exception.Message)" -ErrorAction Continue}
  exit 1
}
Write-Output 'PASS real MySQL/MinIO S102StorageTest; dedicated containers stopped, volumes retained'
