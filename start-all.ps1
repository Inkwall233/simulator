<#
    SkillLab 一键启动脚本

    用法:
        .\start-all.ps1              启动 3 个前端 dev server + skilllab-admin 后端
        .\start-all.ps1 -Stop        停止上述服务占用的端口
        .\start-all.ps1 -TimeoutSeconds 180   自定义等待就绪的超时（默认 120 秒）

    说明:
        每个服务运行在独立的 PowerShell 窗口中，窗口里能看到实时日志；
        关闭窗口即停止该服务。
#>
[CmdletBinding()]
param(
    [switch]$Stop,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

$root = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

$frontends = @(
    @{ Name = 'web-simulator'; Dir = Join-Path $root 'web-simulator'; Port = 5173; Url = 'http://localhost:5173/' },
    @{ Name = 'web-admin';     Dir = Join-Path $root 'web-admin';     Port = 5174; Url = 'http://localhost:5174/' },
    @{ Name = 'web-front';     Dir = Join-Path $root 'web-front';     Port = 5175; Url = 'http://localhost:5175/' }
)

$backendDir  = Join-Path $root 'skilllab-admin'
$backendPort = 8080
$backendUrl  = 'http://localhost:8080/api/health'

$mavenFallback = 'D:\JAVA\apache-maven-3.9.16\bin\mvn.cmd'
$jdkFallback   = 'C:\Program Files\Eclipse Adoptium\jdk-8.0.504.1-hotspot'

function Get-PortOwners {
    param([int]$Port)
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        Where-Object { $_ -gt 0 }
}

# ---------------------------------------------------------------- 停止模式
if ($Stop) {
    Write-Host '停止 SkillLab 服务 ...' -ForegroundColor Cyan
    $ports = @($frontends | ForEach-Object { $_.Port }) + $backendPort
    foreach ($port in $ports) {
        $owners = Get-PortOwners -Port $port
        if (-not $owners) {
            Write-Host ("  端口 {0,-5} 无监听进程" -f $port) -ForegroundColor DarkGray
            continue
        }
        foreach ($procId in $owners) {
            try {
                Stop-Process -Id $procId -Force -ErrorAction Stop
                Write-Host ("  端口 {0,-5} 已停止 PID {1}" -f $port, $procId) -ForegroundColor Yellow
            }
            catch {
                Write-Host ("  端口 {0,-5} 停止 PID {1} 失败: {2}" -f $port, $procId, $_.Exception.Message) -ForegroundColor Red
            }
        }
    }
    return
}

# ---------------------------------------------------------------- 环境检查
foreach ($tool in 'node', 'npm') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "未找到命令 $tool，请先安装 Node.js"
    }
}

if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $jdkFallback 'bin\java.exe'))) {
    $env:JAVA_HOME = $jdkFallback
}

$mvnPath = $null
$mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvnCmd) {
    $mvnPath = $mvnCmd.Source
}
elseif (Test-Path $mavenFallback) {
    $mvnPath = $mavenFallback
}

Write-Host ("根目录    : {0}" -f $root) -ForegroundColor DarkGray
Write-Host ("JAVA_HOME : {0}" -f $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '(未设置)' })) -ForegroundColor DarkGray
Write-Host ("Maven     : {0}" -f $(if ($mvnPath) { $mvnPath } else { '(未找到)' })) -ForegroundColor DarkGray
Write-Host ''

function Start-ServiceWindow {
    param([string]$Title, [string]$Dir, [string]$InnerScript)
    $inner = "`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location -LiteralPath '$Dir'; $InnerScript"
    $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($inner))
    Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoExit', '-NoProfile', '-EncodedCommand', $encoded) | Out-Null
}

# ---------------------------------------------------------------- 启动前端
Write-Host '启动前端 dev server ...' -ForegroundColor Cyan
foreach ($frontend in $frontends) {
    if (-not (Test-Path (Join-Path $frontend.Dir 'node_modules'))) {
        Write-Host ("  {0} 缺少依赖，正在 npm install ..." -f $frontend.Name) -ForegroundColor DarkGray
        Push-Location $frontend.Dir
        try { & npm install --no-fund --no-audit } finally { Pop-Location }
    }
    Start-ServiceWindow -Title $frontend.Name -Dir $frontend.Dir -InnerScript 'npm run dev'
    Write-Host ("  √ {0,-16} {1}" -f $frontend.Name, $frontend.Url) -ForegroundColor Green
}

# ---------------------------------------------------------------- 启动后端
Write-Host '启动后端 ...' -ForegroundColor Cyan
if ($mvnPath) {
    $backendScript = "& '$mvnPath' spring-boot:run"
    if ($env:JAVA_HOME) {
        $backendScript = "`$env:JAVA_HOME = '$env:JAVA_HOME'; $backendScript"
    }
    Start-ServiceWindow -Title 'skilllab-admin' -Dir $backendDir -InnerScript $backendScript
    Write-Host ("  √ {0,-16} {1}" -f 'skilllab-admin', $backendUrl) -ForegroundColor Green
}
else {
    Write-Host '  × 未找到 mvn，已跳过后端启动' -ForegroundColor Red
    Write-Host ("    请安装 Maven，或将 {0} 加入 PATH" -f (Split-Path -Parent $mavenFallback)) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 等待就绪
$targets = @($frontends | ForEach-Object { @{ Name = $_.Name; Port = $_.Port; Url = $_.Url } })
if ($mvnPath) {
    $targets += @{ Name = 'skilllab-admin'; Port = $backendPort; Url = $backendUrl }
}

Write-Host ''
Write-Host ("等待服务就绪（最多 {0} 秒）..." -f $TimeoutSeconds) -ForegroundColor Cyan
$ready = @{}
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline -and $ready.Count -lt $targets.Count) {
    foreach ($target in $targets) {
        if (-not $ready.ContainsKey($target.Port)) {
            if (Get-PortOwners -Port $target.Port) {
                $ready[$target.Port] = $true
            }
        }
    }
    if ($ready.Count -lt $targets.Count) { Start-Sleep -Milliseconds 800 }
}

Write-Host ''
Write-Host '服务状态:' -ForegroundColor Cyan
foreach ($target in $targets) {
    if ($ready.ContainsKey($target.Port)) {
        Write-Host ("  √ {0,-16} {1}" -f $target.Name, $target.Url) -ForegroundColor Green
    }
    else {
        Write-Host ("  × {0,-16} 端口 {1} 未就绪，请查看对应窗口的日志" -f $target.Name, $target.Port) -ForegroundColor Red
    }
}

Write-Host ''
Write-Host '各服务运行在独立窗口中，关闭窗口即停止该服务。' -ForegroundColor DarkGray
Write-Host '也可执行 .\start-all.ps1 -Stop 一键停止全部服务。' -ForegroundColor DarkGray
