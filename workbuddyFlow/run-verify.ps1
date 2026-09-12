<#
.SYNOPSIS
    ChartFlow 一键验证脚本 (run-verify)
.DESCRIPTION
    依次执行：后端编译+单测 -> 前端检查（安装 + 构建 + lint）-> checkstyle。
    全部通过则末行打印 "VERIFY PASS" 并以退出码 0 结束；
    任一步失败则打印 "VERIFY FAIL: <原因>" 并以退出码 1 结束。
    用法：  powershell -File run-verify.ps1 [-SkipFrontend] [-FrontendInstallMode ci|install]
            -FrontendInstallMode 默认 install：增量安装、不清空 node_modules，适合本地日常跑；
            CI 请传 ci：严格按 package-lock.json、先清空再装（注意 Windows 上遇文件锁会 ENOTEMPTY）。
    前置：  JDK 17 在 PATH；Maven 安装（脚本自动定位 MAVEN_HOME，或 set $env:MAVEN_HOME）。
            前端需 Node 18+（用 -SkipFrontend 可跳过）。
    说明：  Maven 采用 java 直启（见 architecture.md 七），避免 PATH 中 mvn 包装脚本
           在 Git Bash 下损坏、以及 PowerShell 调 .cmd 拿不到退出码的问题。
#>
param(
    [switch]$SkipFrontend,
    [ValidateSet('ci', 'install')]
    [string]$FrontendInstallMode = 'install'
)

$root = if ($PSScriptRoot) { $PSScriptRoot } else { Get-Location }
$root = $root.ToString().TrimEnd('\')

# 解析代码根：知识层脚本可能位于子目录（如 workbuddyFlow/），而 pom.xml 在项目根；
# 若当前目录无 pom.xml，则上溯到父目录作为 $codeRoot。
$codeRoot = $root
if (-not (Test-Path (Join-Path $codeRoot "pom.xml"))) {
    $parent = Split-Path $codeRoot
    if (Test-Path (Join-Path $parent "pom.xml")) { $codeRoot = $parent }
}

function Write-Step($n, $text) {
    Write-Host "`n==> [$n] $text" -ForegroundColor Cyan
}
function Fail($reason) {
    Write-Host "VERIFY FAIL: $reason" -ForegroundColor Red
    exit 1
}

# ---- 定位 Maven（java 直启，见 architecture.md 七）----
function Get-MavenHome {
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\m2.conf"))) { return $env:MAVEN_HOME }
    $cands = @(
        "D:\maven-home\apache-maven-3.9.5-bin\apache-maven-3.9.5",
        (Join-Path $env:ProgramFiles "apache-maven\apache-maven-3.9.5"),
        "$env:USERPROFILE\.m2\apache-maven-3.9.5"
    )
    foreach ($c in $cands) { if ($c -and (Test-Path (Join-Path $c "bin\m2.conf"))) { return $c } }
    return $null
}
$mvnHome = Get-MavenHome
if (-not $mvnHome) { Fail "未找到 Maven 安装（请设置 `$env:MAVEN_HOME 或安装 Maven 3.9.x）" }
$bootJar = Get-ChildItem (Join-Path $mvnHome "boot") -Filter "plexus-classworlds-*.jar" | Select-Object -First 1
if (-not $bootJar) { Fail "未找到 Maven boot jar: $mvnHome\boot" }

function Invoke-Maven($mvnArgs) {
    $allArgs = @(
        "-cp", $bootJar.FullName,
        "-Dclassworlds.conf=$((Join-Path $mvnHome 'bin\m2.conf'))",
        "-Dmaven.home=$mvnHome",
        "-Dmaven.multiModuleProjectDirectory=$codeRoot",
        "org.codehaus.plexus.classworlds.launcher.Launcher"
    ) + $mvnArgs
    # 注意：必须用 Out-Host 消费 java 的输出。PowerShell 函数会把所有 pipeline 输出
    # 都收集为返回值，若直接 return，退出码会和 stdout 混成一个数组，
    # 调用方 $ec -ne 0 恒为真（明明成功却判失败）。
    & java @allArgs 2>&1 | Out-Host
    return $LASTEXITCODE
}

$pom = Join-Path $codeRoot "pom.xml"

<# ---------- [1/3] 后端编译 + 单测 ---------- #>
Write-Step "1/3" "Maven compile + test"
if (-not (Test-Path $pom)) { Fail "未找到 pom.xml: $pom" }
$ec = Invoke-Maven @("-B", "-f", $pom, "clean", "test")
if ($ec -ne 0) { Fail "Maven compile/test 失败 (exit=$ec)" }

<# ---------- [2/3] 前端检查 ---------- #>
if ($SkipFrontend) {
    Write-Host "`n==> [2/3] 前端检查 已跳过 (-SkipFrontend)" -ForegroundColor Yellow
} else {
    Write-Step "2/3" "前端检查（安装模式：$FrontendInstallMode）"
    $fe = Join-Path $codeRoot "frontend"
    if (Test-Path $fe) {
        Push-Location $fe
        try {
            $installCmd = if ($FrontendInstallMode -eq 'ci') { 'ci' } else { 'install' }
            Write-Host "  (前端安装模式：$installCmd)" -ForegroundColor DarkGray
            & npm $installCmd; if ($LASTEXITCODE -ne 0) { Fail "npm $installCmd 失败 (exit=$LASTEXITCODE)" }
            & npm run build; if ($LASTEXITCODE -ne 0) { Fail "npm run build 失败 (exit=$LASTEXITCODE)" }
            & npm run lint; if ($LASTEXITCODE -ne 0) { Fail "npm run lint 失败 (exit=$LASTEXITCODE)" }
        } finally {
            Pop-Location
        }
    } else {
        Write-Host "  (跳过：未找到 frontend/ 目录)" -ForegroundColor Yellow
    }
}

<# ---------- [3/3] checkstyle ---------- #>
Write-Step "3/3" "Checkstyle"
$csCfg = Join-Path $codeRoot "checkstyle.xml"
if (Test-Path $csCfg) {
    $ec = Invoke-Maven @("-B", "-f", $pom, "checkstyle:check")
    if ($ec -ne 0) { Fail "Checkstyle 违规，见上方报告" }
} else {
    Write-Host "  (跳过：未找到 checkstyle.xml)" -ForegroundColor Yellow
}

Write-Host "`nVERIFY PASS" -ForegroundColor Green
exit 0
