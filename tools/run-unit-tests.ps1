# Run after :app:compileDebugUnitTestKotlin. Direct classpath avoids the Gradle
# worker argument-file encoding issue when this repository has a Chinese path.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jars = Get-ChildItem "$repo/.build-env/gradle-home/caches/modules-2/files-2.1" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -match '^(junit-4|hamcrest-core|kotlin-stdlib-2|kotlin-stdlib-jdk|kotlinx-coroutines-core-jvm|json-2024|okhttp-|okio-jvm|mockwebserver-|annotations-)' }
$classes = @(
    "$repo/app/build/tmp/kotlin-classes/debug",
    "$repo/app/build/tmp/kotlin-classes/debugUnitTest",
    "$repo/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
)
$classpath = ($classes + $jars.FullName) -join ';'
$testRoot = "$repo/app/src/test/java/"
$tests = Get-ChildItem $testRoot -Recurse -Filter '*Test.kt' | ForEach-Object {
    $_.FullName.Substring($testRoot.Length).Replace('\', '.').Replace('.kt', '')
}
& "$repo/.build-env/jdk/jdk-17.0.20.1+1/bin/java.exe" '-Dfile.encoding=UTF-8' -cp $classpath org.junit.runner.JUnitCore $tests
if ($LASTEXITCODE -ne 0) { throw 'JUnit suite failed' }
