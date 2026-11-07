$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot '..\app\src\androidTest\assets\gp8-compatibility.gp'
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/CoderLine/alphaTab/develop/packages/alphatab/test-data/guitarpro8/canon-audio-track.gp' -OutFile $destination
Write-Host 'Downloaded optional GP8/OGG compatibility fixture for local testing.'
