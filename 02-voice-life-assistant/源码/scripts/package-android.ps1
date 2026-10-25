# Package the checked Android sources using an explicit allowlist. No keys, caches or old APKs.
param([string]$Version = '0.8.0')
$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot -Parent)).Path
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Expected numeric version x.y.z' }
$evidenceDirectory = Join-Path $projectDirectory "release/$Version/evidence"
$builtApk = Join-Path $projectDirectory 'app/build/outputs/apk/debug/app-debug.apk'
$apkDestination = Join-Path $projectDirectory "语音生活助手_${Version}_调试版.apk"
$zipDestination = Join-Path $projectDirectory "语音生活助手_${Version}_Android完整源码.zip"
$checksumsDestination = Join-Path $projectDirectory "语音生活助手_${Version}_文件校验.txt"
if (!(Test-Path -LiteralPath $builtApk)) { throw 'Build the APK first' }
if (Test-Path -LiteralPath $zipDestination) { throw 'Source archive already exists; preserve it and choose a new version.' }
$buildConfig = Get-Content -LiteralPath (Join-Path $projectDirectory 'app/build.gradle.kts') -Raw
$versionCodeMatch = [regex]::Match($buildConfig, 'versionCode\s*=\s*(\d+)')
if (!$versionCodeMatch.Success -or $buildConfig -notmatch ('versionName\s*=\s*"' + [regex]::Escape($Version) + '"')) { throw 'Requested version differs from the build configuration' }
$versionCode = $versionCodeMatch.Groups[1].Value
if (!(Get-Content -LiteralPath (Join-Path $evidenceDirectory 'build.txt') -Raw).Contains('BUILD SUCCESSFUL')) { throw 'Build evidence is missing' }
$testEvidence = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'unit-tests.txt') -Raw
$testMatch = [regex]::Match($testEvidence, 'OK \(([1-9][0-9]*) tests\)')
if (!$testMatch.Success -or $testEvidence.Contains('FAILURES!!!')) { throw 'Expected current JVM test evidence' }
$testCount = $testMatch.Groups[1].Value
$browserMatch = [regex]::Match((Get-Content -LiteralPath (Join-Path $evidenceDirectory 'dashboard-browser.txt') -Raw), 'PASS: ([1-9][0-9]*) ')
if (!$browserMatch.Success) { throw 'Expected current mock browser evidence' }
$browserCount = $browserMatch.Groups[1].Value
[xml]$lint = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'lint-results-debug.xml') -Raw
$lintErrors = @($lint.issues.issue | Where-Object { $_.severity -in @('Error', 'Fatal') }).Count
$lintWarnings = @($lint.issues.issue | Where-Object { $_.severity -eq 'Warning' }).Count
if ($lintErrors -ne 0) { throw 'Lint errors remain' }
if (!(Get-Content -LiteralPath (Join-Path $evidenceDirectory 'apk-signature.txt') -Raw).Contains('Verifies')) { throw 'APK signature evidence is missing' }
if (!(Get-Content -LiteralPath (Join-Path $evidenceDirectory 'apk-version.txt') -Raw).Contains("versionName='$Version'")) { throw 'APK version evidence is missing or stale' }

$relativeFiles = [System.Collections.Generic.List[string]]::new()
function Test-ExcludedPackagePath([string]$RelativePath) {
    if ($RelativePath -match '(^|/)(\.gradle|build|node_modules|keys|secrets)(/|$)|(^|/)local\.properties$|\.(jks|keystore|pem|key|p12|pfx|p8)$') { return $true }
    $fileName = [IO.Path]::GetFileName($RelativePath)
    if ($fileName -ne '.env.example' -and $fileName -match '(^|\.)env($|\.)') { return $true }
    return $fileName -match '^(id_rsa|id_ed25519|credentials|secrets?|key|private[-_]?key|signing[-_]?key|api[-_]?key|proxy[-_]?token)(\.|$)'
}
foreach ($relative in @('.gitignore', 'README.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'scripts/test-ble.ps1', 'scripts/test-dashboard.mjs',
    'scripts/package-android.ps1', 'b_agent_proxy/.env.example', 'b_agent_proxy/package.json',
    'b_agent_proxy/server.mjs', 'b_agent_proxy/command.mjs', 'b_agent_proxy/test.mjs',
    'b_agent_proxy/command.test.mjs', 'b_agent_proxy/README.md')) { $relativeFiles.Add($relative) }
foreach ($relativeDirectory in @('app/src', 'app/libs', 'gradle', 'docs', "release/$Version/evidence")) {
    $absoluteDirectory = Join-Path $projectDirectory $relativeDirectory
    foreach ($source in Get-ChildItem -LiteralPath $absoluteDirectory -Recurse -File -Force) {
        $relativePath = $source.FullName.Substring($projectDirectory.Length + 1).Replace('\', '/')
        if (!(Test-ExcludedPackagePath $relativePath)) { $relativeFiles.Add($relativePath) }
    }
}
$relativeFiles = @($relativeFiles | Sort-Object -Unique)
$sourceHashes = @{}
$textExtensions = @('.kt', '.kts', '.java', '.json', '.xml', '.html', '.md', '.txt', '.ps1', '.mjs', '.properties', '.bat')
foreach ($relative in $relativeFiles) {
    if (Test-ExcludedPackagePath $relative) { throw "Excluded file in allowlist: $relative" }
    $source = Join-Path $projectDirectory $relative
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing source: $relative" }
    if ([IO.Path]::GetExtension($source) -in $textExtensions) {
        $value = [IO.File]::ReadAllText($source)
        if ($value -match 'sk-[A-Za-z0-9_-]{24,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----') {
            throw "Potential credential in package file; inspect locally: $relative"
        }
    }
    $sourceHashes[$relative] = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archiveRoot = "SeniorVoiceDemo-$Version/"
$stream = [IO.File]::Open($zipDestination, [IO.FileMode]::CreateNew)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
try {
    foreach ($relative in $relativeFiles) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $projectDirectory $relative),
            "$archiveRoot$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    $manifest = $archive.CreateEntry("${archiveRoot}SOURCE_MANIFEST.sha256")
    $writer = [IO.StreamWriter]::new($manifest.Open(), [Text.UTF8Encoding]::new($false))
    try { foreach ($relative in $relativeFiles) { $writer.WriteLine("$($sourceHashes[$relative])  $relative") } }
    finally { $writer.Dispose() }
} finally { $archive.Dispose() }

# Verify every archived file against the original hash; no extraction or destructive cleanup.
$readArchive = [IO.Compression.ZipFile]::OpenRead($zipDestination)
try {
    if ($readArchive.Entries.Count -ne $relativeFiles.Count + 1) { throw 'Archive file count mismatch' }
    foreach ($relative in $relativeFiles) {
        $entry = $readArchive.GetEntry("$archiveRoot$relative")
        if ($null -eq $entry) { throw "Archive missing entry: $relative" }
        $entryStream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actual = ([BitConverter]::ToString($sha.ComputeHash($entryStream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose(); $entryStream.Dispose() }
        if ($actual -ne $sourceHashes[$relative]) { throw "Archived file hash mismatch: $relative" }
    }
} finally { $readArchive.Dispose() }

Copy-Item -LiteralPath $builtApk -Destination $apkDestination -Force
$apkHash = (Get-FileHash -LiteralPath $apkDestination -Algorithm SHA256).Hash.ToLowerInvariant()
if ($apkHash -ne (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash.ToLowerInvariant()) { throw 'Copied APK differs from the built APK' }
$zipHash = (Get-FileHash -LiteralPath $zipDestination -Algorithm SHA256).Hash.ToLowerInvariant()
$writer = [IO.StreamWriter]::new($checksumsDestination, $false, [Text.UTF8Encoding]::new($false))
try {
    $writer.WriteLine("语音生活助手 $Version / cn.seniorvoice.demo / versionCode $versionCode")
    $writer.WriteLine("生成时间：$([DateTimeOffset]::Now.ToString('o'))")
    $writer.WriteLine("$apkHash  $([IO.Path]::GetFileName($apkDestination))")
    $writer.WriteLine("$zipHash  $([IO.Path]::GetFileName($zipDestination))")
    $writer.WriteLine("源码包：$($relativeFiles.Count) 个文件及逐文件 SHA-256 清单，已逐项核验；不含私钥、SDK、缓存和本机配置。")
    $writer.WriteLine("电脑验证：$testCount 项 JVM、$browserCount 项 mock 浏览器检查通过；lint $lintErrors errors / $lintWarnings warnings；APK 签名有效。")
    $writer.WriteLine('本版未以真实手机、ESP32、Render 或真实模型完成验收；BLE/摄像头/TTS/华为后台等仍待实机。')
    $writer.WriteLine("本版更新与八项验收：docs/${Version}_更新与验收.md；A AI 部署：docs/${Version}_服务端AI指令部署.md；构建方法见 docs/0.7.8_交付与构建.md（历史构建说明，版本及验证以本版证据为准）。")
} finally { $writer.Dispose() }
Write-Output "Packaged and verified $($relativeFiles.Count) source/evidence files."
Get-Item -LiteralPath $apkDestination, $zipDestination, $checksumsDestination | Select-Object Name, Length
