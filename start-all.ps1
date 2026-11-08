<#
    SkillLab 一键启动脚本

    用法:
        .\start-all.ps1              启动 3 个前端 + 后端 + AI Worker + 本地摄像机演示设备
        .\start-all.ps1 -Stop        停止上述服务占用的端口
        .\start-all.ps1 -TimeoutSeconds 180   自定义等待就绪的超时（默认 120 秒）

    说明:
        所有服务都在当前窗口后台运行，不再弹出独立终端；
        日志写入 .logs\<服务名>.log 与 .logs\<服务名>.err.log。
        后台进程附着于当前窗口的控制台，关闭窗口即停止全部服务。
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
$demoDeviceDir = Join-Path $root 'ai-demo-device'
$demoDevicePort = 19090
$demoDeviceUrl = "http://127.0.0.1:$demoDevicePort"
$demoDeviceHealthUrl = "$demoDeviceUrl/health"
$demoPackage = Join-Path $demoDeviceDir 'device-package.zip'
$aiEnvFile   = Join-Path $root '.env.ai'

$logDir      = Join-Path $root '.logs'
$pidFile     = Join-Path $logDir 'services.json'
$servicePids = @{}

$mavenFallback = 'D:\JAVA\apache-maven-3.9.16\bin\mvn.cmd'
$jdkFallback   = 'D:\JAVA\JDK8'

function Get-PortOwners {
    param([int]$Port)
    Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        Where-Object { $_ -gt 0 }
}

# ---------------------------------------------------------------- 停止模式
if ($Stop) {
    Write-Host '停止 SkillLab 服务 ...' -ForegroundColor Cyan

    # 先按记录结束整棵进程树（npm -> node、mvn -> java 都在树内），
    # 单窗口模式下后台进程不再拥有独立窗口，只靠端口清理容易留下壳进程。
    if (Test-Path -LiteralPath $pidFile) {
        try {
            $saved = Get-Content -LiteralPath $pidFile -Raw -ErrorAction Stop | ConvertFrom-Json
            foreach ($property in $saved.PSObject.Properties) {
                $processId = [int]$property.Value
                if ($processId -gt 0) {
                    taskkill /F /T /PID $processId 2>&1 | Out-Null
                }
            }
        }
        catch {
            Write-Host ("  读取进程记录失败: {0}" -f $_.Exception.Message) -ForegroundColor DarkGray
        }
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }

    $ports = @($frontends | ForEach-Object { $_.Port }) + $backendPort + $workerPort + $demoDevicePort
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
    $env:DEMO_DEVICE_HOST = '127.0.0.1'
    $env:DEMO_DEVICE_PORT = [string]$demoDevicePort
}

Import-AiEnvironment

# ---------------------------------------------------------------- 环境检查
foreach ($tool in 'node', 'npm') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "未找到命令 $tool，请先安装 Node.js"
    }
}

# 本项目 skilllab-admin 为 Spring Boot 2.7 + java.version=1.8，
# 后端统一使用 JDK8 启动，不继承系统级 JAVA_HOME（可能是 17/21），避免编译目标与运行时不匹配。
if (Test-Path (Join-Path $jdkFallback 'bin\java.exe')) {
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

if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

function Start-ServiceProcess {
    param([string]$Name, [string]$Dir, [string]$Script)

    $outLog = Join-Path $logDir ('{0}.log' -f $Name)
    $errLog = Join-Path $logDir ('{0}.err.log' -f $Name)
    $inner = 'cd /d "{0}" && {1}' -f $Dir, $Script

    # -NoNewWindow：子进程附着到当前控制台，不再弹出独立终端窗口；
    # stdout / stderr 各自重定向到文件，避免多个服务的日志在同一控制台互相穿插。
    # 用 cmd.exe 而不是 powershell.exe 做宿主：PowerShell 子进程的错误流被重定向后
    # 会写成 CLIXML 序列化文本，日志不可读；cmd 输出的是纯文本。
    $process = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList @('/d', '/c', $inner) `
        -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog

    $servicePids[$Name] = $process.Id
}

function Show-LogTail {
    param([string]$Name, [int]$Lines = 12)

    foreach ($suffix in @('.log', '.err.log')) {
        $file = Join-Path $logDir ('{0}{1}' -f $Name, $suffix)
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $tail = Get-Content -LiteralPath $file -Tail $Lines -ErrorAction SilentlyContinue
        if (-not $tail) { continue }
        Write-Host ("    {0}:" -f (Split-Path -Leaf $file)) -ForegroundColor DarkGray
        foreach ($line in $tail) { Write-Host ("      " + $line) -ForegroundColor DarkGray }
    }
}

# ---------------------------------------------------------------- 生成并启动本地演示设备
$demoPublicDir = Join-Path $demoDeviceDir 'public'
if (-not (Test-Path (Join-Path $demoPublicDir 'index.html'))) {
    throw "演示设备页面不存在: $demoPublicDir"
}
Write-Host '生成可上传的演示设备 ZIP ...' -ForegroundColor Cyan
# 直接覆盖已有 zip 会被杀软/索引器短暂锁定而报 Access denied，
# 改为先尝试删除旧包、压缩到临时文件后再替换，并带少量重试。
if (Test-Path -LiteralPath $demoPackage) {
    Remove-Item -LiteralPath $demoPackage -Force -ErrorAction SilentlyContinue
}
$demoPackageTemp = $demoPackage + '.tmp.zip'
Compress-Archive -Path (Join-Path $demoPublicDir '*') -DestinationPath $demoPackageTemp -Force
for ($attempt = 1; $attempt -le 5; $attempt++) {
    try {
        Move-Item -LiteralPath $demoPackageTemp -Destination $demoPackage -Force -ErrorAction Stop
        break
    }
    catch {
        if ($attempt -eq 5) { throw }
        Start-Sleep -Milliseconds 400
    }
}
Write-Host ("  √ 上传包: {0}" -f $demoPackage) -ForegroundColor Green
Start-ServiceProcess -Name 'ai-demo-device' -Dir $demoDeviceDir -Script 'npm start'
Write-Host ("  √ {0,-16} {1}" -f 'ai-demo-device', $demoDeviceUrl) -ForegroundColor Green

# ---------------------------------------------------------------- 启动前端
Write-Host '启动前端 dev server ...' -ForegroundColor Cyan
foreach ($frontend in $frontends) {
    if (-not (Test-Path (Join-Path $frontend.Dir 'node_modules'))) {
        Write-Host ("  {0} 缺少依赖，正在 npm install ..." -f $frontend.Name) -ForegroundColor DarkGray
        Push-Location $frontend.Dir
        try { & npm install --no-fund --no-audit } finally { Pop-Location }
    }
    Start-ServiceProcess -Name $frontend.Name -Dir $frontend.Dir -Script 'npm run dev'
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
    Write-Host '  检查当前 Playwright 版本对应的 Chromium ...' -ForegroundColor DarkGray
    $chromiumExecutable = & node -e "process.stdout.write(require('playwright').chromium.executablePath())"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($chromiumExecutable)) {
        throw '无法从 ai-worker 的 Playwright 包解析 Chromium 路径。请确认 ai-worker 依赖已安装。'
    }

    $chromiumRevisionDir = Split-Path -Parent (Split-Path -Parent $chromiumExecutable)
    $browserCacheDir = Split-Path -Parent $chromiumRevisionDir
    $chromiumRevision = Split-Path -Leaf $chromiumRevisionDir
    $headlessRevision = $chromiumRevision -replace '^chromium-', 'chromium_headless_shell-'
    $headlessExecutable = Join-Path $browserCacheDir (Join-Path $headlessRevision 'chrome-headless-shell-win64\chrome-headless-shell.exe')
    $browserInstalled = (Test-Path -LiteralPath $chromiumExecutable) -and (Test-Path -LiteralPath $headlessExecutable)

    if ($browserInstalled) {
        Write-Host '  √ 对应版本的 Chromium 与 Headless Shell 已安装，跳过下载。' -ForegroundColor Green
    }
    else {
        Write-Host '  未找到当前 Playwright 版本所需的浏览器，开始安装 ...' -ForegroundColor DarkGray
        & npx playwright install chromium
        $installExitCode = $LASTEXITCODE
        if ($installExitCode -ne 0) {
            throw "Playwright Chromium 安装失败（退出码 $installExitCode）。请查看上方安装器输出；若浏览器已手动安装，可检查路径: $chromiumExecutable"
        }
        if (-not (Test-Path -LiteralPath $chromiumExecutable) -or -not (Test-Path -LiteralPath $headlessExecutable)) {
            throw "Playwright 安装命令已结束，但当前版本浏览器文件仍缺失。预期路径: $chromiumExecutable"
        }
    }
}
finally { Pop-Location }

Start-ServiceProcess -Name 'ai-worker' -Dir $workerDir -Script 'npm start'
Write-Host ("  √ {0,-16} {1}" -f 'ai-worker', $workerUrl) -ForegroundColor Green

# ---------------------------------------------------------------- 启动后端
Write-Host '启动后端 ...' -ForegroundColor Cyan
if ($mvnPath) {
    # 子进程用 cmd 启动，故按 cmd 语法拼接；JAVA_HOME 已在本进程环境中设置，
    # cmd 子进程会直接继承，无需在命令里重复赋值。
    $backendScript = '"{0}" spring-boot:run' -f $mvnPath
    Start-ServiceProcess -Name 'skilllab-admin' -Dir $backendDir -Script $backendScript
    Write-Host ("  √ {0,-16} {1}" -f 'skilllab-admin', $backendUrl) -ForegroundColor Green
}
else {
    Write-Host '  × 未找到 mvn，已跳过后端启动' -ForegroundColor Red
    Write-Host ("    请安装 Maven，或将 {0} 加入 PATH" -f (Split-Path -Parent $mavenFallback)) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 等待就绪
$targets = @($frontends | ForEach-Object { @{ Name = $_.Name; Port = $_.Port; Url = $_.Url } })
$targets += @{ Name = 'ai-worker'; Port = $workerPort; Url = $workerUrl; ExpectedService = 'skilllab-ai-browser-worker' }
$targets += @{ Name = 'ai-demo-device'; Port = $demoDevicePort; Url = $demoDeviceHealthUrl; ExpectedService = 'skilllab-demo-device' }
if ($mvnPath) {
    $targets += @{ Name = 'skilllab-admin'; Port = $backendPort; Url = $backendUrl; ExpectedService = 'skilllab-admin' }
}

Write-Host ''
Write-Host ("等待服务就绪（最多 {0} 秒）..." -f $TimeoutSeconds) -ForegroundColor Cyan
$ready = @{}
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline -and $ready.Count -lt $targets.Count) {
    foreach ($target in $targets) {
        if (-not $ready.ContainsKey($target.Port)) {
            try {
                $probe = Invoke-RestMethod -Uri $target.Url -TimeoutSec 2
                if (-not $target.ExpectedService -or $probe.service -eq $target.ExpectedService) {
                    $ready[$target.Port] = $true
                }
            }
            catch {
                # Keep waiting until the service responds successfully.
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
        Write-Host ("  × {0,-16} 健康检查失败（端口 {1}）" -f $target.Name, $target.Port) -ForegroundColor Red
        Show-LogTail -Name $target.Name
    }
}

$servicePids | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8

Write-Host ''
Write-Host '全部服务已在当前窗口后台运行，无独立终端窗口。' -ForegroundColor DarkGray
Write-Host ("日志目录: {0}" -f $logDir) -ForegroundColor DarkGray
Write-Host '查看实时日志: Get-Content .\.logs\web-simulator.log -Wait -Tail 20' -ForegroundColor DarkGray
Write-Host '关闭当前窗口即停止全部服务，也可执行 .\start-all.ps1 -Stop 一键停止。' -ForegroundColor DarkGray
Write-Host 'AI 工作台入口: http://localhost:5174/ai-workbench' -ForegroundColor Cyan
Write-Host ("演示设备入口: {0}  |  演示账号: admin / demo123" -f $demoDeviceUrl) -ForegroundColor Cyan
