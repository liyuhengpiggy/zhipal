# Isolated build tools, never added to the source delivery ZIP.
param([string]$ToolsRoot = (Join-Path $PSScriptRoot '../artifacts/android-build-tools'))
$ErrorActionPreference = 'Stop'
$ToolsRoot = [IO.Path]::GetFullPath($ToolsRoot)
New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
function Fetch-Checked($Url, $Destination, $Algorithm, $Expected) {
    if (-not (Test-Path -LiteralPath $Destination)) {
        Write-Host ('Downloading ' + [IO.Path]::GetFileName($Destination))
        & curl.exe --fail --location --retry 2 --connect-timeout 20 --output $Destination $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    }
    $actual = (Get-FileHash -Algorithm $Algorithm -LiteralPath $Destination).Hash
    if ($actual -ine $Expected.Trim()) { throw "Checksum mismatch: $Destination" }
}
$jdkJson = & curl.exe --fail --silent --show-error --location 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
if ($LASTEXITCODE -ne 0) { throw 'JDK metadata download failed' }
$jdkInfo = $jdkJson | ConvertFrom-Json
$package = $jdkInfo[0].binary.package
$jdkZip = Join-Path $ToolsRoot 'jdk17.zip'
Fetch-Checked $package.link $jdkZip 'SHA256' $package.checksum
$jdkDir = Join-Path $ToolsRoot 'jdk'
if (-not (Test-Path -LiteralPath $jdkDir)) { Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkDir }
$jdkHome = (Get-ChildItem -LiteralPath $jdkDir -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdkHome
$env:PATH = "$jdkHome/bin;$env:PATH"
$env:GRADLE_USER_HOME = Join-Path $ToolsRoot 'gradle-cache'
$gradleZip = Join-Path $ToolsRoot 'gradle-8.9-bin.zip'
$gradleHash = & curl.exe --fail --silent --show-error --location 'https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256'
if ($gradleHash -is [byte[]]) { $gradleHash = [Text.Encoding]::UTF8.GetString($gradleHash) }
Fetch-Checked 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' $gradleZip 'SHA256' $gradleHash
$gradleDir = Join-Path $ToolsRoot 'gradle-8.9'
if (-not (Test-Path -LiteralPath $gradleDir)) { Expand-Archive -LiteralPath $gradleZip -DestinationPath $ToolsRoot }
$repositoryXml = & curl.exe --fail --silent --show-error --location 'https://dl.google.com/android/repository/repository2-1.xml'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK metadata download failed' }
[xml]$repository = $repositoryXml -join "`n"
$archives = $repository.SelectNodes("//*[local-name()='remotePackage' and @path='cmdline-tools;latest']/*[local-name()='archives']/*[local-name()='archive']")
$archive = $archives | Where-Object { $_.'host-os' -eq 'windows' } | Select-Object -First 1
if (-not $archive) { throw 'Official Windows Android command line tools not found' }
$sdkZip = Join-Path $ToolsRoot 'android-tools.zip'
$checksum = $archive.complete.checksum.InnerText
if (-not $checksum) { $checksum = [string]$archive.complete.checksum }
Fetch-Checked ('https://dl.google.com/android/repository/' + $archive.complete.url) $sdkZip 'SHA1' $checksum
$sdkUnpack = Join-Path $ToolsRoot 'android-commandline'
if (-not (Test-Path -LiteralPath $sdkUnpack)) { Expand-Archive -LiteralPath $sdkZip -DestinationPath $sdkUnpack }
$sdkRoot = Join-Path $ToolsRoot 'sdk'
New-Item -ItemType Directory -Force -Path $sdkRoot | Out-Null
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$sdkmanager = Join-Path $sdkUnpack 'cmdline-tools/bin/sdkmanager.bat'
Write-Host 'Accepting Android SDK licenses for this requested build.'
1..100 | ForEach-Object { 'y' } | & $sdkmanager "--sdk_root=$sdkRoot" --licenses
if ($LASTEXITCODE -ne 0) { throw 'SDK license step failed' }
& $sdkmanager "--sdk_root=$sdkRoot" 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw 'SDK component installation failed' }
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../mobile-android'))
& (Join-Path $gradleDir 'bin/gradle.bat') -p $project --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android compilation failed' }
$apk = Join-Path $project 'app/build/outputs/apk/debug/app-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
