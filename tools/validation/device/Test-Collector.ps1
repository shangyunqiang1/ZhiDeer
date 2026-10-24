$ErrorActionPreference = 'Stop'
$collector = (Join-Path $PSScriptRoot 'Collect-DeviceEvidence.ps1')
$fake = Join-Path $PSScriptRoot 'fake-adb.ps1'
$output = Join-Path ([System.IO.Path]::GetTempPath()) ('zhideer-evidence-test-' + [guid]::NewGuid().ToString('N') + '.json')
try {
  & $collector -AdbPath 'definitely-missing-adb-zhideer' -OutputPath $output
  throw 'Missing adb was accepted'
} catch { if ($_.Exception.Message -eq 'Missing adb was accepted') { throw } }
foreach ($case in @('none','unauthorized','two','fail','one')) {
  $env:FAKE_ADB_SCENARIO = $case
  & $collector -AdbPath $fake -PackageName 'test.zhideer.probe' -OutputPath $output | Out-Null
  $raw = Get-Content -LiteralPath $output -Raw
  $report = $raw | ConvertFrom-Json
  if ($report.status -ne 'NOT_VERIFIED') { throw 'Unexpected verified claim' }
  if ($raw -match 'PRIVATE_SERIAL|OTHER_SERIAL') { throw 'Serial leaked' }
  if ($case -eq 'one') {
    if ($report.deviceCount -ne 1 -or $report.appOps.SCHEDULE_EXACT_ALARM.exitCode -ne 1) { throw 'Operation failure missing' }
  } elseif ($report.properties) { throw 'Collected from ambiguous/unavailable device' }
  Write-Output "PASS: $case"
}

Remove-Item -LiteralPath $output -ErrorAction SilentlyContinue
Remove-Item Env:FAKE_ADB_SCENARIO -ErrorAction SilentlyContinue

