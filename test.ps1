param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$taskTemp = $env:CLOUD_VOYAGE_BUILD_TEMP
if ([string]::IsNullOrWhiteSpace($taskTemp)) { $taskTemp = Join-Path $PSScriptRoot 'build/temp' }
if (!(Test-Path -LiteralPath $taskTemp)) { New-Item -ItemType Directory -Force -Path $taskTemp | Out-Null }
$env:TEMP=$taskTemp
$env:TMP=$taskTemp
$env:TMPDIR=$taskTemp
$target=Join-Path $PSScriptRoot 'build/test'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$main=Join-Path $PSScriptRoot 'src/main/java/app/cloudvoyage'
$test=Join-Path $PSScriptRoot 'src/test/java/app/cloudvoyage/FlightRulesTest.java'
& (Join-Path $JavaHome 'bin/javac.exe') --release 8 -encoding UTF-8 -d $target (Join-Path $main 'Flight.java') (Join-Path $main 'Airports.java') (Join-Path $main 'History.java') (Join-Path $main 'GlobeCamera.java') (Join-Path $main 'FlightFollowCamera.java') (Join-Path $main 'SatelliteProjection.java') (Join-Path $main 'WindowLayout.java') $test
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp $target app.cloudvoyage.FlightRulesTest
if($LASTEXITCODE -ne 0){throw 'Flight rules tests failed'}
