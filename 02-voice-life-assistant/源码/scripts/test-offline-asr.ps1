# Optional Windows smoke test with synthetic Chinese speech, using downloaded desktop native Vosk.
# This does not install packages, make calls, connect BLE, or test an Android device.
param([string]$Voice = 'Microsoft Huihui Desktop')
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$runtimeDirectory = Join-Path $projectDirectory 'build-tools/asr-downloads'
$fixturesDirectory = Join-Path $projectDirectory 'build-tools/asr-smoke'
New-Item -ItemType Directory -Force -Path $fixturesDirectory | Out-Null
Add-Type -AssemblyName System.Speech
$synthesizer = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $synthesizer.SelectVoice($Voice)
    $audioFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
        16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
        [System.Speech.AudioFormat.AudioChannel]::Mono)
    $phrases = @('打开抖音', '给女儿打电话', '不要给女儿打电话', '不要打开抖音')
    for ($index = 0; $index -lt $phrases.Count; $index++) {
        $synthesizer.SetOutputToWaveFile((Join-Path $fixturesDirectory "fixture-$index.wav"), $audioFormat)
        $prompt = New-Object System.Speech.Synthesis.PromptBuilder([Globalization.CultureInfo]::GetCultureInfo('zh-CN'))
        $prompt.AppendBreak([TimeSpan]::FromMilliseconds(500))
        $prompt.AppendText($phrases[$index])
        $prompt.AppendBreak([TimeSpan]::FromMilliseconds(800))
        $synthesizer.Speak($prompt)
        $synthesizer.SetOutputToNull()
    }
} finally { $synthesizer.Dispose() }
$classpath = @(
    (Join-Path $projectDirectory 'app/build/tmp/kotlin-classes/debug'),
    (Join-Path $runtimeDirectory 'vosk-android-0.3.47.jar'),
    (Join-Path $runtimeDirectory 'jna-desktop-5.13.0.jar')
)
foreach ($dependency in @('org.json/json/20240303', 'org.jetbrains.kotlin/kotlin-stdlib/2.0.21')) {
    $jar = @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory "build-tools/gradle-home/caches/modules-2/files-2.1/$dependency") -Recurse -Filter '*.jar')
    if ($jar.Count -ne 1) { throw "Expected one cached $dependency JAR" }
    $classpath += $jar[0].FullName
}
$javaDirectory = Join-Path $projectDirectory 'build-tools/jdk/jdk-17.0.20.1+1/bin'
& (Join-Path $javaDirectory 'javac.exe') '-encoding' 'UTF-8' '-cp' ($classpath -join [IO.Path]::PathSeparator) '-d' $fixturesDirectory (Join-Path $PSScriptRoot 'OfflineAsrSmoke.java')
if ($LASTEXITCODE -ne 0) { throw 'Smoke fixture compilation failed' }
$nativeDirectory = Join-Path $runtimeDirectory 'windows-vosk/vosk'
$previousPath = $env:PATH
try {
    $env:PATH = "$nativeDirectory;$previousPath"
    $classpath += $fixturesDirectory
    Push-Location $projectDirectory
    try {
        # Native Windows Vosk uses ANSI file APIs: keep its model argument ASCII/relative.
        & (Join-Path $javaDirectory 'java.exe') '-Dfile.encoding=UTF-8' '-Djna.encoding=UTF-8' "-Djna.library.path=$nativeDirectory" '-cp' ($classpath -join [IO.Path]::PathSeparator) 'OfflineAsrSmoke' 'app/src/main/assets/asr/vosk-model-small-cn-0.22' $fixturesDirectory
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic ASR smoke test failed' }
    } finally { Pop-Location }
} finally { $env:PATH = $previousPath }
