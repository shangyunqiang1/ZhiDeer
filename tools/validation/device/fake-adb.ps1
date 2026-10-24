param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$global:LASTEXITCODE = 0
if ($Arguments[0] -eq 'devices') {
  'List of devices attached'
  switch ($env:FAKE_ADB_SCENARIO) {
    'one' { "PRIVATE_SERIAL`tdevice" }
    'two' { "PRIVATE_SERIAL`tdevice"; "OTHER_SERIAL`tdevice" }
    'unauthorized' { "PRIVATE_SERIAL`tunauthorized" }
    'fail' { $global:LASTEXITCODE = 1; 'adb failed' }
  }
} elseif ($Arguments[0] -eq 'version') { 'Fake adb for host-script tests only' }
elseif ($Arguments -contains 'SCHEDULE_EXACT_ALARM') { $global:LASTEXITCODE = 1; 'Unknown operation' }
else { 'synthetic-value' }
