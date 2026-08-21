param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }),
    [string]$FfmpegPath = $env:MIRROR_TO_TV_FFMPEG,
    [string]$KeystorePath = $env:MIRROR_TO_TV_KEYSTORE,
    [string]$KeystoreAlias = $(if ($env:MIRROR_TO_TV_KEYSTORE_ALIAS) { $env:MIRROR_TO_TV_KEYSTORE_ALIAS } else { 'mirror-to-tv' })
)

$ErrorActionPreference = 'Stop'
$Token = $env:MIRROR_TO_TV_TOKEN
$KeystorePassword = $env:MIRROR_TO_TV_KEYSTORE_PASSWORD

function New-SecureHex([int]$ByteCount) {
    $bytes = New-Object byte[] $ByteCount
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

$repoRoot = $PSScriptRoot
$project = Join-Path $repoRoot 'tv'
$clientTemplate = Join-Path $repoRoot 'desktop\Mirror-To-TV.ps1.template'
$output = Join-Path $repoRoot 'dist'
$build = Join-Path $repoRoot 'build'
$privateDirectory = Join-Path $repoRoot '.private'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$utf8Bom = New-Object System.Text.UTF8Encoding($true)

$tokenWasGenerated = [string]::IsNullOrWhiteSpace($Token)
if ($tokenWasGenerated) { $Token = New-SecureHex 32 }
if ($Token -notmatch '^[0-9a-fA-F]{64}$') {
    throw 'MIRROR_TO_TV_TOKEN must contain exactly 64 hexadecimal characters when set.'
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($null -ne $javacCommand) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent $javacCommand.Source)
    }
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or
    -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK 17 was not found. Set JAVA_HOME or pass -JavaHome.'
}

function Get-Sha256Hex([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return -join ($algorithm.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') })
    } finally {
        $algorithm.Dispose()
        $stream.Dispose()
    }
}

if ([string]::IsNullOrWhiteSpace($FfmpegPath)) {
    $ffmpegCommand = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
    if ($null -ne $ffmpegCommand) { $FfmpegPath = $ffmpegCommand.Source }
}
if ([string]::IsNullOrWhiteSpace($FfmpegPath) -or
    -not (Test-Path -LiteralPath $FfmpegPath -PathType Leaf)) {
    throw 'ffmpeg.exe was not found. Pass -FfmpegPath or set MIRROR_TO_TV_FFMPEG.'
}
$ffmpegDirectory = Split-Path -Parent $FfmpegPath
$ffmpegLicense = @(
    Join-Path $ffmpegDirectory 'LICENSE'
    Join-Path (Split-Path -Parent $ffmpegDirectory) 'LICENSE'
    Join-Path $ffmpegDirectory 'COPYING.GPLv3'
    Join-Path (Split-Path -Parent $ffmpegDirectory) 'COPYING.GPLv3'
) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($null -eq $ffmpegLicense) {
    throw 'The FFmpeg license file was not found next to ffmpeg.exe.'
}

if (-not (Test-Path -LiteralPath $AndroidSdk -PathType Container)) {
    throw 'Android SDK was not found. Set ANDROID_HOME or pass -AndroidSdk.'
}
$buildToolsDirectory = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'aapt2.exe') } |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
    Select-Object -First 1
$platformDirectory = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk 'platforms') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar') } |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending |
    Select-Object -First 1
if ($null -eq $buildToolsDirectory -or $null -eq $platformDirectory) {
    throw 'Android SDK platform and build-tools were not found.'
}

$resolvedProject = [IO.Path]::GetFullPath($repoRoot)
$resolvedBuild = [IO.Path]::GetFullPath($build)
if (-not $resolvedBuild.StartsWith($resolvedProject + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe build directory.'
}
if (Test-Path -LiteralPath $resolvedBuild) {
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}

$generatedSource = Join-Path $build 'generated-source\local\lanoverlay\tv'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Force -Path $generatedSource, $classes, $dex, $output | Out-Null

$configTemplate = Join-Path $project 'src\local\lanoverlay\tv\Config.java.template'
$generatedConfig = Join-Path $generatedSource 'Config.java'
$configText = ([IO.File]::ReadAllText($configTemplate, [Text.Encoding]::UTF8)).Replace('__MIRROR_TO_TV_TOKEN__', $Token)
if ($configText -match '__MIRROR_TO_TV_TOKEN__') { throw 'Could not inject the receiver token.' }
[IO.File]::WriteAllText($generatedConfig, $configText, $utf8NoBom)

$generatedClient = Join-Path $build 'generated-client\Mirror-To-TV.ps1'
New-Item -ItemType Directory -Force -Path (Split-Path $generatedClient -Parent) | Out-Null
$clientText = ([IO.File]::ReadAllText($clientTemplate, [Text.Encoding]::UTF8)).Replace('__MIRROR_TO_TV_TOKEN__', $Token)
if ($clientText -match '__MIRROR_TO_TV_TOKEN__') { throw 'Could not inject the desktop token.' }
[IO.File]::WriteAllText($generatedClient, $clientText, $utf8Bom)

$javaBin = Join-Path $JavaHome 'bin'
$env:JAVA_HOME = $JavaHome
$env:Path = $javaBin + [IO.Path]::PathSeparator + $env:Path
$aapt2 = Join-Path $buildToolsDirectory.FullName 'aapt2.exe'
$d8 = Join-Path $buildToolsDirectory.FullName 'd8.bat'
$zipalign = Join-Path $buildToolsDirectory.FullName 'zipalign.exe'
$apksigner = Join-Path $buildToolsDirectory.FullName 'apksigner.bat'
$javac = Join-Path $javaBin 'javac.exe'
$jar = Join-Path $javaBin 'jar.exe'
$keytool = Join-Path $javaBin 'keytool.exe'
$androidJar = Join-Path $platformDirectory.FullName 'android.jar'

$compiledResources = Join-Path $build 'resources.zip'
$unsigned = Join-Path $build 'base-unsigned.apk'
$classesJar = Join-Path $build 'classes.jar'
$withDex = Join-Path $build 'base-with-dex.apk'
$aligned = Join-Path $build 'base-aligned.apk'
$finalApk = Join-Path $build 'mirror-to-tv-tv.apk'

& $aapt2 compile --dir (Join-Path $project 'res') -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'aapt2 resource compilation failed.' }

& $aapt2 link `
    -o $unsigned `
    -I $androidJar `
    --manifest (Join-Path $project 'AndroidManifest.xml') `
    --min-sdk-version 23 `
    --target-sdk-version 28 `
    --version-code 10000 `
    --version-name '1.0' `
    $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed.' }

$sources = @(
    Get-ChildItem -LiteralPath (Join-Path $project 'src') -Filter '*.java' -Recurse
    Get-Item -LiteralPath $generatedConfig
) | ForEach-Object { $_.FullName }
$savedErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javacOutput = & $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -d $classes $sources 2>&1
    $javacExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
$javacText = ($javacOutput | Out-String).Trim()
if ($javacExitCode -ne 0) { throw "javac failed.`n$javacText" }
if ($javacText -and ($javacText -notmatch 'java\.nio\.file\.AccessDeniedException: .*android\.jar')) {
    Write-Warning $javacText
}

& $jar --create --file $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed.' }
& $d8 --min-api 23 --lib $androidJar --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed.' }
Copy-Item -LiteralPath $unsigned -Destination $withDex -Force
& $jar --update --file $withDex -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Could not add classes.dex.' }
& $zipalign -f -p 4 $withDex $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

$usingExternalKeystore = -not [string]::IsNullOrWhiteSpace($KeystorePath)
if (-not $usingExternalKeystore) {
    New-Item -ItemType Directory -Force -Path $privateDirectory | Out-Null
    $KeystorePath = Join-Path $privateDirectory 'mirror-to-tv-dev.p12'
    $passwordFile = Join-Path $privateDirectory 'keystore-password.txt'
    if (Test-Path -LiteralPath $KeystorePath) {
        if (-not (Test-Path -LiteralPath $passwordFile)) {
            throw 'The development keystore exists, but its ignored password file is missing.'
        }
        $KeystorePassword = (Get-Content -Raw -LiteralPath $passwordFile).Trim()
    } else {
        $KeystorePassword = New-SecureHex 24
        Set-Content -LiteralPath $passwordFile -Value $KeystorePassword -Encoding ascii -NoNewline
        $env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD = $KeystorePassword
        try {
            & $keytool -genkeypair -noprompt `
                -keystore $KeystorePath `
                -storetype PKCS12 `
                -storepass:env MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD `
                -keypass:env MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD `
                -alias $KeystoreAlias `
                -keyalg RSA `
                -keysize 3072 `
                -validity 10000 `
                -dname 'CN=mirror-to-tv development, OU=Local Build, O=Local, C=XX'
            if ($LASTEXITCODE -ne 0) { throw 'Could not create the development keystore.' }
        } finally {
            Remove-Item Env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
        }
    }
} elseif (-not (Test-Path -LiteralPath $KeystorePath)) {
    throw "Keystore not found: $KeystorePath"
} elseif ([string]::IsNullOrWhiteSpace($KeystorePassword)) {
    throw 'Set MIRROR_TO_TV_KEYSTORE_PASSWORD before using an external keystore.'
}

$env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD = $KeystorePassword
try {
    & $apksigner sign `
        --ks $KeystorePath `
        --ks-type PKCS12 `
        --ks-key-alias $KeystoreAlias `
        --ks-pass env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD `
        --key-pass env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD `
        --out $finalApk `
        $aligned
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed.' }
} finally {
    Remove-Item Env:MIRROR_TO_TV_BUILD_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
& $apksigner verify --verbose --print-certs $finalApk
if ($LASTEXITCODE -ne 0) { throw 'APK verification failed.' }

$releaseDirectory = Join-Path $output 'mirror-to-tv'
$releaseArchive = Join-Path $output 'mirror-to-tv-1.0.zip'
$resolvedOutput = [IO.Path]::GetFullPath($output)
$resolvedRelease = [IO.Path]::GetFullPath($releaseDirectory)
if (-not $resolvedRelease.StartsWith($resolvedOutput + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe release directory.'
}
if (Test-Path -LiteralPath $resolvedRelease) {
    Remove-Item -LiteralPath $resolvedRelease -Recurse -Force
}
if (Test-Path -LiteralPath $releaseArchive) {
    Remove-Item -LiteralPath $releaseArchive -Force
}
$releaseTools = Join-Path $releaseDirectory 'tools'
New-Item -ItemType Directory -Force -Path $releaseTools | Out-Null
Copy-Item -LiteralPath $generatedClient -Destination (Join-Path $releaseDirectory 'Mirror-To-TV.ps1')
Copy-Item -LiteralPath $finalApk -Destination (Join-Path $releaseDirectory 'Mirror-To-TV.apk')
Copy-Item -LiteralPath (Join-Path $repoRoot 'desktop\Start-Mirror-To-TV.cmd') -Destination $releaseDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'desktop\Install-Mirror-To-TV.cmd') -Destination $releaseDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'mirror-to-tv-icon.png') -Destination $releaseDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'README.md') -Destination $releaseDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'README.ru.md') -Destination $releaseDirectory
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $releaseDirectory

$platformTools = Join-Path $AndroidSdk 'platform-tools'
foreach ($toolName in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt') {
    $toolPath = Join-Path $platformTools $toolName
    if (-not (Test-Path -LiteralPath $toolPath -PathType Leaf)) {
        throw "Android platform tool not found: $toolPath"
    }
    Copy-Item -LiteralPath $toolPath -Destination $releaseTools
}
Copy-Item -LiteralPath $FfmpegPath -Destination (Join-Path $releaseTools 'ffmpeg.exe')
Copy-Item -LiteralPath $ffmpegLicense -Destination (Join-Path $releaseTools 'FFMPEG-LICENSE.txt')

$checksumLines = Get-ChildItem -LiteralPath $releaseDirectory -Recurse -File |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($releaseDirectory.Length + 1).Replace('\', '/')
        $hash = Get-Sha256Hex $_.FullName
        "$hash  $relative"
    }
Set-Content -LiteralPath (Join-Path $releaseDirectory 'SHA256SUMS.txt') -Value $checksumLines -Encoding utf8
Compress-Archive -LiteralPath $releaseDirectory -DestinationPath $releaseArchive -CompressionLevel Optimal

[pscustomobject]@{
    Version = '1.0'
    Apk = Join-Path $releaseDirectory 'Mirror-To-TV.apk'
    Desktop = Join-Path $releaseDirectory 'Mirror-To-TV.ps1'
    Archive = $releaseArchive
    ApkSha256 = Get-Sha256Hex $finalApk
    DesktopSha256 = Get-Sha256Hex $generatedClient
    TokenGenerated = $tokenWasGenerated
    SigningMode = $(if ($usingExternalKeystore) { 'external' } else { 'local-development' })
}
