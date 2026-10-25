# Run after :app:compileDebugUnitTestKotlin. Passing the classpath directly avoids
# Gradle 8.9 / JDK 17 worker argfile encoding problems on Chinese Windows paths.
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
$javaExecutable = Join-Path $projectDirectory 'build-tools/jdk/jdk-17.0.20.1+1/bin/java.exe'
if (!(Test-Path -LiteralPath $javaExecutable)) {
    if ($env:JAVA_HOME) { $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $javaExecutable = (Get-Command java -ErrorAction Stop).Source }
}
$dependencyCache = Join-Path $projectDirectory 'build-tools/gradle-home/caches/modules-2/files-2.1'
if (!(Test-Path -LiteralPath $dependencyCache)) {
    $gradleCacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $dependencyCache = Join-Path $gradleCacheRoot 'caches/modules-2/files-2.1'
}
$classes = @(
    (Join-Path $projectDirectory 'app/build/tmp/kotlin-classes/debug'),
    (Join-Path $projectDirectory 'app/build/tmp/kotlin-classes/debugUnitTest')
)
foreach ($directory in $classes) {
    if (!(Test-Path -LiteralPath $directory)) { throw 'Compile debug and debugUnitTest Kotlin classes first.' }
}
$dependencies = @(
    'junit/junit/4.13.2',
    'org.hamcrest/hamcrest-core/1.3',
    'org.json/json/20240303',
    'org.jetbrains.kotlin/kotlin-stdlib/2.0.21'
)
$classes += @(Get-ChildItem -LiteralPath (Join-Path $projectDirectory 'app/libs/b-agent') -Filter '*.jar' | ForEach-Object { $_.FullName })
foreach ($dependency in $dependencies) {
    $directory = Join-Path $dependencyCache $dependency
    $jar = @(Get-ChildItem -LiteralPath $directory -Recurse -Filter '*.jar')
    if ($jar.Count -ne 1) { throw "Expected exactly one cached jar for $dependency" }
    $classes += $jar[0].FullName
}
$testRoot = Join-Path $projectDirectory 'app/src/test/java'
$testNames = @(Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*Test.kt' | ForEach-Object {
    $_.FullName.Substring($testRoot.Length + 1).Replace('\', '.').Replace('/', '.').Replace('.kt', '')
})
& $javaExecutable '-Dfile.encoding=UTF-8' '-cp' ($classes -join [IO.Path]::PathSeparator) 'org.junit.runner.JUnitCore' @testNames
exit $LASTEXITCODE
