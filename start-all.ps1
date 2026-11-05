<#
    SkillLab 一键启动脚本

    用法:
        .\start-all.ps1              启动 3 个前端 dev server + skilllab-admin 后端 + AI Worker
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
$workerDir   = Join-Path $root 'ai-worker'
$workerPort  = 3001
$workerUrl   = 'http://localhost:3001/health'
$aiEnvFile   = Join-Path $root '.env.ai'

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
    $ports = @($frontends | ForEach-Object { $_.Port }) + $backendPort + $workerPort
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

# --------------------------------------------------------- AI 工作台环境配置
function Import-AiEnvironment {
    if (Test-Path -LiteralPath $aiEnvFile) {
        foreach ($line in Get-Content -LiteralPath $aiEnvFile) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            if ($trimmed -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { continue }

            $name = $Matches[1]
            $value = $Matches[2].Trim()
            if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            Set-Item -Path "Env:$name" -Value $value
        }
    }
    else {
        $requiredFromEnvironment = @($env:SKILLLAB_AI_ADMIN_KEY, $env:AI_WORKER_SHARED_SECRET, $env:AI_ALLOWED_TARGET_ORIGINS)
        if ($requiredFromEnvironment | Where-Object { [string]::IsNullOrWhiteSpace($_) }) {
            $exampleFile = Join-Path $root 'deploy\ai-workbench.env.example'
            if (Test-Path -LiteralPath $exampleFile) {
                Copy-Item -LiteralPath $exampleFile -Destination $aiEnvFile
            }
            throw "已在仓库根目录创建 .env.ai 示例配置。请填写 SKILLLAB_AI_ADMIN_KEY、AI_WORKER_SHARED_SECRET 和 AI_ALLOWED_TARGET_ORIGINS 后重新运行。"
        }
    }

    foreach ($name in 'SKILLLAB_AI_ADMIN_KEY', 'AI_WORKER_SHARED_SECRET', 'AI_ALLOWED_TARGET_ORIGINS') {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ([string]::IsNullOrWhiteSpace($value) -or $value -like 'replace-with-*') {
            throw "AI 工作台配置 $name 未填写，请编辑 $aiEnvFile。"
        }
    }

    if ($env:AI_ALLOWED_TARGET_ORIGINS -match 'device\.example\.internal') {
        throw "AI_ALLOWED_TARGET_ORIGINS 仍包含示例域名，请编辑 $aiEnvFile 并填写已授权设备的精确 origin。"
    }

    # 本地开发时 Java 后端通过 localhost 访问 Worker。
    $env:AI_WORKER_URL = 'http://localhost:3001'
    $env:PORT = '3001'
}

Import-AiEnvironment

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
Write-Host ("AI env    : {0}" -f $aiEnvFile) -ForegroundColor DarkGray
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

# ---------------------------------------------------------------- 启动 AI Worker
Write-Host '启动 AI 浏览器 Worker ...' -ForegroundColor Cyan
if (-not (Test-Path (Join-Path $workerDir 'node_modules'))) {
    Write-Host '  Worker 缺少依赖，正在 npm ci ...' -ForegroundColor DarkGray
    Push-Location $workerDir
    try {
        & npm ci --no-fund --no-audit
        if ($LASTEXITCODE -ne 0) { throw 'AI Worker npm ci 失败' }
    }
    finally { Pop-Location }
}

Push-Location $workerDir
try {
    Write-Host '  检查 Playwright Chromium ...' -ForegroundColor DarkGray
    & npx playwright install chromium
    if ($LASTEXITCODE -ne 0) { throw 'Playwright Chromium 安装失败' }
}
finally { Pop-Location }

Start-ServiceWindow -Title 'ai-worker' -Dir $workerDir -InnerScript 'npm start'
Write-Host ("  √ {0,-16} {1}" -f 'ai-worker', $workerUrl) -ForegroundColor Green

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
$targets += @{ Name = 'ai-worker'; Port = $workerPort; Url = $workerUrl }
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
Write-Host 'AI 工作台入口: http://localhost:5174/ai-workbench' -ForegroundColor Cyan
