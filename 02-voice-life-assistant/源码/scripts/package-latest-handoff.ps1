$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$zipPath = Join-Path $projectRoot '语音生活助手_0.8.0_精简合并交接包.zip'
if (Test-Path -LiteralPath $zipPath) { throw 'Archive already exists' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$files = [Collections.Generic.SortedDictionary[string,string]]::new()
$rootFiles = @('.gitignore','README.md','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','voice-life-demo.html','app/build.gradle.kts')
foreach ($relative in $rootFiles) { $files.Add("源码/$relative", (Join-Path $projectRoot $relative)) }
foreach ($relativeDirectory in @('app/src','app/libs','gradle','b_agent_proxy','agent_backend','deploy','server','docs','scripts','release/0.8.0/evidence')) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $projectRoot $relativeDirectory) -Recurse -File -Force) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\','/')
        if ($relative -match '(^|/)(build|node_modules|__pycache__|\.venv|\.git|\.gradle|\.pytest_cache|keys|secrets)(/|$)|\.(pyc|apk|zip|jks|keystore|pem|key|p12|pfx)$') { continue }
        if ($file.Name -match '^\.env' -and $file.Name -ne '.env.example') { continue }
        if ($relative -match '^scripts/(package-full-handoff|apk_handoff_inventory|create_apk_handoff_doc|qa_apk_handoff|render_handoff_word)\.') { continue }
        $files.Add("源码/$relative", $file.FullName)
    }
}
$files.Add('安装包/语音生活助手_0.8.0_调试版.apk', (Join-Path $projectRoot '语音生活助手_0.8.0_调试版.apk'))
$files.Add('安装包/原始文件校验.txt', (Join-Path $projectRoot '语音生活助手_0.8.0_文件校验.txt'))
$visionZip = 'C:\Users\Lenovo\xwechat_files\wxid_7f6a5zz42je019_1be6\msg\file\2026-10\medicine-vision-apk-integration.zip'
$vision = [IO.Compression.ZipFile]::OpenRead($visionZip)
try {
    if (@($vision.Entries | Where-Object { $_.FullName -match '\.apk$' }).Count -gt 0) { throw 'Vision source package contains an additional APK' }
} finally { $vision.Dispose() }
$files.Add('待合并视觉后端/medicine-vision-apk-integration.zip', $visionZip)
$intro = @'
# 0.8.0 精简合并交接包

先读 `源码/docs/项目技术交接与合并指南_0.8.0.md`。硬件语音与手机权限控制专项说明见 `源码/docs/硬件语音通信与手机权限控制链路_0.8.0.md`。

- `安装包/`：唯一手机 APK，0.8.0 / versionCode 21；原始校验文件中的旧源码 ZIP 未重复打包。
- `源码/`：当前 Android 工程、全部 app/src（含离线语音模型及许可证）、app/libs 本地 AAR/JAR、Gradle Wrapper/配置、相关测试脚本；A/B Node 代理、NextAction Python 后端、事件演示服务、视觉部署模板、接口与合并文档、0.8.0 已有构建和验证证据。
- `待合并视觉后端/`：单独收到的原始视觉服务源码/模型交付，尚未合入或对当前协议完成验证。技术指南中“视觉源码不在工作区”是指原主工作区，请结合这里的补充源码阅读。
- `SHA256SUMS.txt`：本包逐文件校验清单。

不含历史 APK/旧源码归档、聊天、演示幻灯片、SDK/JDK 安装目录、构建缓存、中间产物、本机 local.properties 或私有签名密钥。依赖保留本地库与声明文件；首次构建需要联网下载声明的 Gradle/Maven/Python 依赖，不是离线 SDK 镜像。

## 构建与运行

Android：JDK 17、Android SDK Platform 35、Build Tools 35.0.0；Gradle 8.9、AGP 8.7.3、Kotlin 2.0.21。进入 `源码/`，设置本机 ANDROID_HOME 或创建 local.properties，执行 `./gradlew.bat :app:assembleDebug`；输出 `app/build/outputs/apk/debug/app-debug.apk`。新电脑默认调试证书不同，重新编译的 APK 不一定能直接覆盖现有安装。

代理和后端按 `b_agent_proxy/README.md`、`agent_backend/README.md` 及对应 requirements/package.json 安装依赖，按 .env.example 自行配置。主要接口、合并边界和实机待验事项以技术交接指南为准。旧版文件名的协议/构建文档仍被现有指南引用，因此保留作为参考，不能据此覆盖 0.8.0 实现。

本次只打包当前文件并做归档完整性校验，未重新构建或新增实机测试。
'@
$hashes = [Collections.Generic.SortedDictionary[string,string]]::new()
$archive = [IO.Compression.ZipArchive]::new([IO.File]::Open($zipPath,[IO.FileMode]::CreateNew),[IO.Compression.ZipArchiveMode]::Create,$false)
function Add-TextEntry($Archive, [string]$Name, [string]$Value) {
    $entry = $Archive.CreateEntry($Name)
    $writer = [IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false))
    try { $writer.Write($Value) } finally { $writer.Dispose() }
}
try {
    foreach ($pair in $files.GetEnumerator()) {
        $hashes.Add($pair.Key, (Get-FileHash -LiteralPath $pair.Value -Algorithm SHA256).Hash.ToLowerInvariant())
        $level = [IO.Compression.CompressionLevel]::Fastest
        if ($pair.Value -match '\.(apk|zip|aar|jar|png|jpg|jpeg)$') { $level = [IO.Compression.CompressionLevel]::NoCompression }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$pair.Value,$pair.Key,$level) | Out-Null
    }
    Add-TextEntry $archive '00_先读我.md' $intro
    $manifest = ($hashes.GetEnumerator() | ForEach-Object { "$($_.Value)  $($_.Key)" }) -join "`n"
    Add-TextEntry $archive 'SHA256SUMS.txt' ($manifest + "`n")
} finally { $archive.Dispose() }
$readArchive = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    if ($readArchive.Entries.Count -ne $files.Count + 2) { throw 'Entry count mismatch' }
    foreach ($pair in $hashes.GetEnumerator()) {
        $stream = $readArchive.GetEntry($pair.Key).Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = [Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant() }
        finally { $sha.Dispose(); $stream.Dispose() }
        if ($actual -ne $pair.Value) { throw "Archive hash mismatch: $($pair.Key)" }
    }
} finally { $readArchive.Dispose() }
Write-Output "PASS: $($files.Count) files verified; exactly one APK."
Get-Item -LiteralPath $zipPath | Select-Object FullName,Length
