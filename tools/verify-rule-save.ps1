# Run against an unlocked, authorized Android device. Creates a temporary non-matching
# keyword rule, verifies Save with the keyboard open/closed, then removes that rule.
param(
    [string]$Probe = ('lvjian_repair_' + [DateTimeOffset]::Now.ToUnixTimeMilliseconds()),
    [string]$AdbPath = 'adb',
    [switch]$KeepKeyboardOpen,
    [switch]$OpenRules,
    [switch]$KeepProbe
)
$ErrorActionPreference = 'Stop'
$adb = $AdbPath
if ($AdbPath -eq 'adb' -and (Test-Path (Join-Path $PSScriptRoot '../.build-env/sdk/platform-tools/adb.exe'))) {
    $adb = Join-Path $PSScriptRoot '../.build-env/sdk/platform-tools/adb.exe'
}
if ($Probe -notmatch '^lvjian_repair_[a-zA-Z0-9_]+$') { throw 'Probe must be an ASCII lvjian_repair_ name' }
function Read-Ui {
    $dumpOutput = & $adb shell uiautomator dump /data/local/tmp/lvjian-repair.xml
    if ($LASTEXITCODE -or ($dumpOutput -join "`n") -notmatch 'dumped to:') {
        throw 'INCONCLUSIVE: UI dump failed; no stale dump will be used'
    }
    [xml]$doc = (& $adb shell cat /data/local/tmp/lvjian-repair.xml)
    if (!$doc.SelectSingleNode('//node[@package="com.lvjian.app"]')) {
        throw 'INCONCLUSIVE: LvJian lost foreground; do not operate the phone during the test'
    }
    return $doc
}
function Tap-Node($node) {
    if (!$node) { throw 'Required control missing' }
    $coords = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($coords[0]+$coords[2])/2)) ([int](($coords[1]+$coords[3])/2))
}
if ($OpenRules) {
    & $adb shell am start -n com.lvjian.app/.MainActivity | Out-Null
    Start-Sleep -Milliseconds 400
}
$doc = Read-Ui
if ($doc.SelectSingleNode("//node[@text='$Probe']")) { throw 'Probe name already exists; choose another name' }
if ($OpenRules -and !$doc.SelectSingleNode('//node[@text="＋ 新建规则" or @text="新建规则" or @text="命中规则的消息"]')) {
    Tap-Node $doc.SelectSingleNode('//node[starts-with(@text,"未整理 ")]')
    $doc = Read-Ui
    Tap-Node $doc.SelectSingleNode('//node[@text="整理规则"]')
    $doc = Read-Ui
}
if (!$doc.SelectSingleNode('//node[@text="新建规则"]')) {
    for ($attempt = 0; $attempt -lt 3 -and !$doc.SelectSingleNode('//node[@text="＋ 新建规则"]'); $attempt++) {
        $screen = [regex]::Matches($doc.hierarchy.node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        & $adb shell input swipe ([int]($screen[2]/2)) ([int]($screen[3]*0.78)) ([int]($screen[2]/2)) ([int]($screen[3]*0.35)) 350
        $doc = Read-Ui
    }
    Tap-Node $doc.SelectSingleNode('//node[@text="＋ 新建规则"]')
    $doc = Read-Ui
}
$fields = $doc.SelectNodes('//node[@package="com.lvjian.app" and @class="android.widget.EditText"]')
if ($fields.Count -ne 2) { throw 'Expected rule name and pattern fields' }
if ($fields[0].text -ne '' -or $fields[1].text -ne '') { throw 'Expected empty test form' }
Tap-Node $fields[0]
& $adb shell input text $Probe
& $adb shell input keyevent 4
$doc = Read-Ui
Tap-Node $doc.SelectNodes('//node[@package="com.lvjian.app" and @class="android.widget.EditText"]')[1]
& $adb shell input text __lvjian_probe_no_match__
if (!$KeepKeyboardOpen) { & $adb shell input keyevent 4 }
$doc = Read-Ui
$fields = $doc.SelectNodes('//node[@package="com.lvjian.app" and @class="android.widget.EditText"]')
if ($fields[0].text -ne $Probe -or $fields[1].text -ne '__lvjian_probe_no_match__') { throw 'Form input did not match probe' }
Tap-Node $doc.SelectSingleNode('//node[@text="保存规则"]')
$doc = Read-Ui
if ($doc.SelectSingleNode('//node[@text="新建规则"]')) { throw 'FAIL: tapping visible Save center leaves completed form open' }
if (!$doc.SelectSingleNode("//node[@class='android.widget.TextView' and @text='$Probe']")) { throw 'FAIL: saved rule is missing from list' }
Write-Output 'PASS: visible Save center saved the rule and returned to list'
if (!$KeepProbe) {
    $probeNode = $doc.SelectSingleNode("//node[@class='android.widget.TextView' and @text='$Probe']")
    $coords = [regex]::Matches($probeNode.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    $rowY = [int](($coords[1]+$coords[3])/2)
    $screen = [regex]::Matches($doc.hierarchy.node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input swipe ([int]($screen[2]*0.65)) $rowY ([int]($screen[2]*0.15)) $rowY 350
    $doc = Read-Ui
    if ($doc.SelectSingleNode("//node[@text='$Probe']")) { throw 'Probe cleanup failed; remove the test rule manually' }
    Write-Output 'PASS: temporary probe rule removed'
}
