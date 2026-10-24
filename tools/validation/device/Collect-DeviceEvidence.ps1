[CmdletBinding()]
param(
  [string]$AdbPath = 'adb',
  [string]$PackageName,
  [string]$OutputPath = (Join-Path $PSScriptRoot 'device-evidence.json')
)
$ErrorActionPreference = 'Stop'
if ($PackageName -and $PackageName -notmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$') { throw 'Invalid package name.' }
$adbCommand = Get-Command $AdbPath -ErrorAction Stop
$adbExe = $adbCommand.Source
function Invoke-AdbRead([string[]]$Arguments) {
  $lines = & $adbExe @Arguments 2>&1
  $code = $LASTEXITCODE
  [pscustomobject]@{ exitCode = $code; output = ($lines -join "`n") }
}
$devices = Invoke-AdbRead @('devices')
$readyDevices = @($devices.output -split "`n" | Where-Object { $_ -match '^\S+\s+device\s*$' })
$report = [ordered]@{
  collectedAt = [DateTimeOffset]::Now.ToString('o')
  status = 'NOT_VERIFIED'
  adbVersion = (Invoke-AdbRead @('version')).output
  deviceCount = $readyDevices.Count
  devicesQueryExitCode = $devices.exitCode
  note = 'Only device metadata is collected. Delivery, speech, clock saving and app behavior require manual verification. Serial numbers are not saved.'
}
if ($devices.exitCode -ne 0 -or $readyDevices.Count -ne 1) {
  $report.reason = 'Exactly one authorized device is required; disconnect other devices or authorize USB debugging.'
} else {
  $properties = [ordered]@{}
  foreach ($property in @('ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.version.security_patch','ro.build.version.opporom','ro.build.version.oplusrom','persist.sys.timezone')) {
    $properties[$property] = Invoke-AdbRead @('shell','getprop',$property)
  }
  $report.properties = $properties
  $report.deviceTime = Invoke-AdbRead @('shell','date','+%Y-%m-%dT%H:%M:%S%z')
  $report.powerSave = Invoke-AdbRead @('shell','settings','get','global','low_power')
  $report.recognitionService = Invoke-AdbRead @('shell','settings','get','secure','voice_recognition_service')
  $report.ttsEngine = Invoke-AdbRead @('shell','settings','get','secure','tts_default_synth')
  if ($PackageName) {
    $report.packageName = $PackageName
    $report.packageInstalled = Invoke-AdbRead @('shell','pm','path',$PackageName)
    $ops = [ordered]@{}
    foreach ($operation in @('POST_NOTIFICATION','SCHEDULE_EXACT_ALARM','RECORD_AUDIO')) {
      $ops[$operation] = Invoke-AdbRead @('shell','cmd','appops','get',$PackageName,$operation)
    }
    $report.appOps = $ops
  }
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Output $OutputPath

