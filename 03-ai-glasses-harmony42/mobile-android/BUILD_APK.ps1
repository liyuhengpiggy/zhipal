param([string]$Gradle = 'gradle')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '未找到 JDK。请安装 JDK 17，并把 java 加入 PATH 后重试。'
}
if (-not (Get-Command $Gradle -ErrorAction SilentlyContinue)) {
    throw '未找到 Gradle。请安装 Gradle 8.9，或用 Android Studio 打开此目录构建。'
}
& $Gradle --version
if ($LASTEXITCODE -ne 0) { throw 'Gradle 无法启动' }
& $Gradle --no-daemon :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败，请检查 JDK 17、Gradle 8.9、Android SDK 35 和网络。' }
$apk = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw '未找到编译产物' }
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
Write-Host "调试 APK：$apk"
Write-Host '此 APK 为调试签名，仅用于联调；发布前必须正式签名和真机验收。'
