param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$KeyStore,
    [Parameter(Mandatory=$true)][string]$PasswordFile,
    [int]$VersionCode = 11,
    [string]$VersionName = '0.8.0',
    [string]$Output = (Join-Path $PSScriptRoot 'build/CloudVoyage.apk')
)
$ErrorActionPreference = 'Stop'
$taskTemp = $env:CLOUD_VOYAGE_BUILD_TEMP
if ([string]::IsNullOrWhiteSpace($taskTemp)) { $taskTemp = Join-Path $PSScriptRoot 'build/temp' }
if (!(Test-Path -LiteralPath $taskTemp)) { New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null }
$env:TEMP = $taskTemp
$env:TMP = $taskTemp
$env:TMPDIR = $taskTemp
$env:JAVA_HOME = $JavaHome
$classes = Join-Path $PSScriptRoot 'build/classes'
$dex = Join-Path $PSScriptRoot 'build/dex'
$generated = Join-Path $PSScriptRoot 'build/generated'
$tools = Join-Path $SdkRoot 'build-tools/35.0.0'
if (!(Test-Path -LiteralPath $tools)) { $tools=Join-Path $SdkRoot 'android-15' }
$platform = Join-Path $SdkRoot 'platforms/android-35/android.jar'
if (!(Test-Path -LiteralPath $platform)) { $platform=Join-Path $SdkRoot 'android-35/android.jar' }
$source = Join-Path $PSScriptRoot 'src/main'
$build = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $classes,$dex,$generated,(Split-Path -Parent $Output) | Out-Null
Get-ChildItem -LiteralPath $classes -Recurse -File -Filter '*.class' | ForEach-Object {
    Remove-Item -LiteralPath $_.FullName -Force
}
Get-ChildItem -LiteralPath $dex -File -Filter 'classes*.dex' | ForEach-Object {
    Remove-Item -LiteralPath $_.FullName -Force
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable returned $LASTEXITCODE" }
}
$compiled = Join-Path $build 'resources.zip'
$resourceApk = Join-Path $build 'resources.apk'
$unaligned = Join-Path $build 'unsigned-unaligned.apk'
$unsigned = Join-Path $build 'unsigned.apk'
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('compile','--dir',(Join-Path $source 'res'),'-o',$compiled)
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('link','-o',$resourceApk,'-I',$platform,'--manifest',
    (Join-Path $source 'AndroidManifest.xml'),'--java',$generated,'--min-sdk-version','26','--target-sdk-version','35',
    '--version-code',[string]$VersionCode,'--version-name',$VersionName,'-A',(Join-Path $source 'assets'),$compiled)
$mapKey = [string]$env:CLOUD_VOYAGE_MAPTILER_KEY
if ($mapKey -and $mapKey -notmatch '^[a-zA-Z0-9_-]{10,100}$') { throw 'Invalid MapTiler key format' }
$mapConfig = Join-Path $generated 'app/cloudvoyage/MapConfig.java'
Set-Content -Encoding ascii -LiteralPath $mapConfig -Value (
    'package app.cloudvoyage; final class MapConfig { static final String MAPTILER_KEY = "' + $mapKey + '"; }')
try {
    $sources = @(Get-ChildItem -Recurse -File -Filter '*.java' -LiteralPath (Join-Path $source 'java'),$generated |
        ForEach-Object FullName)
    Invoke-Checked (Join-Path $JavaHome 'bin/javac.exe') (@('--release','8','-encoding','UTF-8','-classpath',$platform,'-d',$classes)+$sources)
} finally {
    Remove-Item -LiteralPath $mapConfig -ErrorAction SilentlyContinue
}
$files = @(Get-ChildItem -Recurse -File -Filter '*.class' -LiteralPath $classes | ForEach-Object FullName)
Invoke-Checked (Join-Path $tools 'd8.bat') (@('--min-api','26','--lib',$platform,'--output',$dex)+$files)
Copy-Item -LiteralPath $resourceApk -Destination $unaligned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = [IO.Compression.ZipFile]::Open($unaligned,[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($item in Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apk,$item.FullName,$item.Name) | Out-Null
    }
} finally { $apk.Dispose() }
Invoke-Checked (Join-Path $tools 'zipalign.exe') @('-f','4',$unaligned,$unsigned)
try {
    $env:CLOUD_VOYAGE_SIGN_PASSWORD = [IO.File]::ReadAllText($PasswordFile).Trim()
    Invoke-Checked (Join-Path $tools 'apksigner.bat') @('sign','--ks',$KeyStore,'--ks-key-alias','cloudvoyage',
        '--ks-pass','env:CLOUD_VOYAGE_SIGN_PASSWORD','--v4-signing-enabled','false','--out',$Output,$unsigned)
} finally { Remove-Item Env:CLOUD_VOYAGE_SIGN_PASSWORD -ErrorAction SilentlyContinue }
Invoke-Checked (Join-Path $tools 'apksigner.bat') @('verify','--verbose','--print-certs',$Output)
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('dump','badging',$Output)
