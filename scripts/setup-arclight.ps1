#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Dest = '',
    [switch]$One,
    [string]$Heap = '2G',
    [int]$Port = 25565,
    [string]$Code = 'ES2',
    [switch]$Force
)
& (Join-Path $PSScriptRoot 'setup-win.ps1') -Core arclight -Dest $Dest -One:$One -Heap $Heap -Port $Port -Code $Code -Force:$Force
