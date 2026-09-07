#Requires -Version 5.1
# Windows: download Youer or Arclight 1.21.1 and lay out ESLink test servers.
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-youer.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\setup-arclight.ps1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('youer', 'arclight')]
    [string]$Core,

    [string]$Dest = '',
    [switch]$One,
    [string]$Heap = '2G',
    [int]$Port = 25565,
    [string]$Code = 'ES2',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Dest)) {
    $Dest = Join-Path $Root ("servers-" + $Core)
}
if (-not (Test-Path -LiteralPath $Dest)) {
    New-Item -ItemType Directory -Path $Dest | Out-Null
}
$Dest = (Resolve-Path -LiteralPath $Dest).Path
$Cache = Join-Path $Dest '.cache'
New-Item -ItemType Directory -Force -Path $Cache | Out-Null

$UserAgent = 'ESLink-setup/1.0 (+https://github.com/EVGA2048/EtherSync-Link)'

function Write-Utf8([string]$Path, [string]$Text) {
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

function Get-JavaLine {
    try {
        $out = & java -version 2>&1 | Select-Object -First 1
        return [string]$out
    } catch {
        return ''
    }
}

function Test-Java21 {
    $line = Get-JavaLine
    if ([string]::IsNullOrWhiteSpace($line)) {
        throw '未找到 java。请先安装 Java 21（https://adoptium.net/）。'
    }
    if ($line -notmatch 'version "?21') {
        Write-Warning "当前 Java 不是 21（$line）。Youer / Arclight 1.21.1 与 ESLink 都需要 21。"
    }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Invoke-ApiJson([string]$Url) {
    $headers = @{ 'User-Agent' = $UserAgent; 'Accept' = 'application/json' }
    return Invoke-RestMethod -Uri $Url -Headers $headers
}

function Save-Url([string]$Url, [string]$Path) {
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & curl.exe -fL --retry 4 --retry-delay 2 -A $UserAgent -o $Path $Url
        if ($LASTEXITCODE -ne 0) { throw "下载失败: $Url" }
        return
    }
    $tmp = $Path + '.part'
    $wc = New-Object System.Net.WebClient
    $wc.Headers['User-Agent'] = $UserAgent
    try {
        $wc.DownloadFile($Url, $tmp)
        Move-Item -LiteralPath $tmp -Destination $Path -Force
    } finally {
        $wc.Dispose()
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
    }
}

function Get-YouerMeta {
    Write-Host '查询 Youer 1.21.1 最新构建…'
    $builds = Invoke-ApiJson 'https://api.mohistmc.com/project/youer/1.21.1/builds'
    if (-not $builds) { throw 'Youer API 没有返回构建列表。' }
    $latest = @($builds)[0]
    $short = $latest.commit.hash.Substring(0, 8)
    return [pscustomobject]@{
        Name = "youer-1.21.1-$short-server.jar"
        Url  = "https://api.mohistmc.com/project/youer/1.21.1/builds/$($latest.id)/download"
        Sha  = ([string]$latest.file_sha256).ToLowerInvariant()
        Note = "build $($latest.id) · NeoForge $($latest.loader.neoforge_version)"
    }
}

function Get-ArclightMeta {
    Write-Host '查询 Arclight 1.21.1 NeoForge 发行包…'
    $rel = Invoke-ApiJson 'https://api.github.com/repos/IzzelAliz/Arclight/releases/latest'
    $asset = @($rel.assets) | Where-Object { $_.name -like 'arclight-neoforge-1.21.1-*.jar' } | Select-Object -First 1
    if (-not $asset) { throw '最新 Arclight 发行版里没有 neoforge-1.21.1 jar。' }
    $sha = ''
    if ($asset.digest -match '^sha256:([0-9a-fA-F]+)$') {
        $sha = $Matches[1].ToLowerInvariant()
    }
    return [pscustomobject]@{
        Name = $asset.name
        Url  = $asset.browser_download_url
        Sha  = $sha
        Note = $rel.tag_name
    }
}

function Find-PluginJar {
    foreach ($dir in @((Join-Path $Root 'dist'), (Join-Path $Root 'target'))) {
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $hit = Get-ChildItem -LiteralPath $dir -Filter 'ESLink-*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'original' } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Set-EslinkConfig([string]$CfgDir, [string]$ServerCode, [string]$Name, [string]$Color, [string]$Blurb) {
    $cfg = Join-Path $CfgDir 'config.yml'
    if (Test-Path -LiteralPath $cfg) { return }
    $src = Join-Path $Root 'src\main\resources\config.yml'
    if (-not (Test-Path -LiteralPath $src)) { return }
    $text = [System.IO.File]::ReadAllText($src).Replace("`r`n", "`n")
    $map = [ordered]@{
        "  code: ES2`n"                         = "  code: $ServerCode`n"
        "  short: `"`"`n"                       = "  short: $ServerCode`n"
        "  name: 以太物语`n"                     = "  name: $Name`n"
        "  blurb: Create 机械动力 · 生存建筑`n" = "  blurb: $Blurb`n"
        "  color: LIGHT_BLUE`n"                 = "  color: $Color`n"
    }
    foreach ($k in $map.Keys) {
        if ($text.IndexOf($k) -lt 0) { throw "config.yml 未找到模板行: $k" }
        $idx = $text.IndexOf($k)
        $text = $text.Remove($idx, $k.Length).Insert($idx, $map[$k])
    }
    New-Item -ItemType Directory -Force -Path $CfgDir | Out-Null
    Write-Utf8 $cfg $text
}

function Write-ServerDir([string]$Dir, [int]$ServerPort, [string]$ServerCode, [string]$Name, [string]$Color, [string]$Blurb, [string]$JarPath, [string]$PluginJar) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Dir 'plugins\ESLink'), (Join-Path $Dir 'mods'), (Join-Path $Dir 'logs') | Out-Null
    Copy-Item -LiteralPath $JarPath -Destination (Join-Path $Dir 'server.jar') -Force
    Write-Utf8 (Join-Path $Dir 'eula.txt') "eula=true`n"
    $props = @"
motd=ESLink $ServerCode
server-port=$ServerPort
online-mode=false
enforce-secure-profile=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=8
difficulty=peaceful
gamemode=creative
enable-command-block=true
sync-chunk-writes=false
max-world-size=29999984
level-name=world
level-type=minecraft:flat
generate-structures=false
allow-nether=false
"@
    Write-Utf8 (Join-Path $Dir 'server.properties') ($props.Replace("`n", "`r`n") + "`r`n")
    $bat = @"
@echo off
cd /d "%~dp0"
java -Xms1G -Xmx$Heap -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar server.jar nogui
"@
    Write-Utf8 (Join-Path $Dir 'start.bat') ($bat.Replace("`n", "`r`n") + "`r`n")
    if ($PluginJar) {
        Copy-Item -LiteralPath $PluginJar -Destination (Join-Path $Dir ("plugins\" + [IO.Path]::GetFileName($PluginJar))) -Force
    }
    Set-EslinkConfig (Join-Path $Dir 'plugins\ESLink') $ServerCode $Name $Color $Blurb
}

Test-Java21

if ($Core -eq 'youer') { $meta = Get-YouerMeta } else { $meta = Get-ArclightMeta }
Write-Host ("核心 " + $meta.Name + " · " + $meta.Note)

$jar = Join-Path $Cache $meta.Name
$need = $true
if (-not $Force -and (Test-Path -LiteralPath $jar)) {
    if ($meta.Sha) {
        $got = Get-Sha256 $jar
        if ($got -eq $meta.Sha) {
            $need = $false
            Write-Host "已有 $($meta.Name)，跳过下载。"
        }
    } else {
        $need = $false
        Write-Host "已有 $($meta.Name)，发行版未提供校验和，跳过下载。"
    }
}
if ($need) {
    Write-Host ("下载 " + $meta.Name + " …")
    $part = $jar + '.part'
    if (Test-Path -LiteralPath $part) { Remove-Item -LiteralPath $part -Force }
    Save-Url $meta.Url $part
    if ($meta.Sha) {
        $got = Get-Sha256 $part
        if ($got -ne $meta.Sha) {
            Remove-Item -LiteralPath $part -Force
            throw "校验失败: 期望 $($meta.Sha)，实际 $got"
        }
    }
    Move-Item -LiteralPath $part -Destination $jar -Force
}

$plugin = Find-PluginJar
if ($plugin) {
    Write-Host ("将拷贝插件 " + [IO.Path]::GetFileName($plugin))
} else {
    Write-Host '未找到 ESLink jar。可先在仓库根目录执行: mvn -q package'
}

$blurb = "$Core 本机联调"
if ($One) {
    Write-ServerDir $Dest $Port $Code ("测试服 " + $Code) 'LIGHT_BLUE' $blurb $jar $plugin
    Write-Host ""
    Write-Host "已部署单服: $Dest"
    Write-Host "  核心 $Core · 端口 $Port · 代号 $Code · 堆内存 $Heap"
    Write-Host "启动:  $Dest\start.bat"
} else {
    Write-ServerDir (Join-Path $Dest 'es2') 25565 'ES2' '以太物语' 'LIGHT_BLUE' $blurb $jar $plugin
    Write-ServerDir (Join-Path $Dest 'snc') 25566 'SNC' '测试服' 'LIME' $blurb $jar $plugin
    Write-Utf8 (Join-Path $Dest 'start-es2.bat') "@echo off`r`ncall `"%~dp0es2\start.bat`"`r`n"
    Write-Utf8 (Join-Path $Dest 'start-snc.bat') "@echo off`r`ncall `"%~dp0snc\start.bat`"`r`n"
    Write-Host ""
    Write-Host "已部署双服: $Dest"
    Write-Host "  ES2  → localhost:25565   核心 $Core   堆内存 $Heap"
    Write-Host "  SNC  → localhost:25566"
    Write-Host "两个窗口分别运行:"
    Write-Host "  $Dest\start-es2.bat"
    Write-Host "  $Dest\start-snc.bat"
}

Write-Host ""
Write-Host "下一步:"
Write-Host "  1. 本机准备一套 MySQL，库名默认 eslink（两台服填同一套）。"
Write-Host "  2. 编辑各服 plugins\ESLink\config.yml 的 mysql.password。"
Write-Host "  3. Create 等模组放到各服 mods\，两端必须相同。"
Write-Host "  4. 进服后 op 自己，再 /link reload。"
if (-not $plugin) {
    Write-Host "  5. 先 mvn -q package，再重新执行本脚本以拷入 ESLink jar。"
}
Write-Host "正版验证已关。Youer/Arclight 首次启动还会再拉 NeoForge/原版库，请留出磁盘和时间。"
Write-Host "双开混合端建议 8G 以上内存；6G 机器请改用 scripts\setup-paper.sh。"
