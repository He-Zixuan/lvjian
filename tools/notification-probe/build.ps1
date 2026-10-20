# Build a local notification source using only the repository's portable Android tools.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$buildDir = Join-Path $repo '.build-env/notification-probe'
$jdk = Join-Path $repo '.build-env/jdk/jdk-17.0.20.1+1/bin'
$sdk = Join-Path $repo '.build-env/sdk'
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
New-Item -ItemType Directory -Force -Path $buildDir, "$buildDir/classes", "$buildDir/dex" | Out-Null
function Check-Exit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
& "$jdk/javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d "$buildDir/classes" "$PSScriptRoot/ProbeActivity.java"
Check-Exit
& "$jdk/jar.exe" cf "$buildDir/classes.jar" -C "$buildDir/classes" .
Check-Exit
& "$jdk/java.exe" -cp "$buildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output "$buildDir/dex" "$buildDir/classes.jar"
Check-Exit
& "$buildTools/aapt2.exe" link -I $androidJar --manifest "$PSScriptRoot/AndroidManifest.xml" -o "$buildDir/probe-unsigned.apk"
Check-Exit
& "$jdk/jar.exe" uf "$buildDir/probe-unsigned.apk" -C "$buildDir/dex" classes.dex
Check-Exit
& "$buildTools/zipalign.exe" -f 4 "$buildDir/probe-unsigned.apk" "$buildDir/probe-aligned.apk"
Check-Exit
if (!(Test-Path "$buildDir/test.keystore")) {
    & "$jdk/keytool.exe" -genkeypair -keystore "$buildDir/test.keystore" -alias probe -storepass android -keypass android -keyalg RSA -validity 3650 -dname 'CN=Local Notification Probe'
    Check-Exit
}
& "$jdk/java.exe" -jar "$buildTools/lib/apksigner.jar" sign --ks "$buildDir/test.keystore" --ks-key-alias probe --ks-pass pass:android --key-pass pass:android --out "$buildDir/probe.apk" "$buildDir/probe-aligned.apk"
Check-Exit
Write-Output "Built $buildDir/probe.apk"
