# ============================================================
# 一键发布：拉代码 → 本地打包 → 上传服务器 → 切换 → 重启 → 体检
#
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1                 # 全流程（推荐）
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -NoRestart      # 只上传，不重启（线上仍旧版本）
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -SkipPull       # 不拉 git，直接用仓库现有代码
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -SetupKey       # 首次：生成并安装 SSH 公钥（之后免密）
#
# 为什么从 zhuochong-github 构建：那个目录才是真正的 git 仓库，
# git pull 之后「构建的代码 == GitHub 上的代码」，不会误把本地工作区的半成品打进去。
#
# 服务器端做的事（都在 /root/zc/app）：
#   上传成 zhuochong.jar.new → 旧 jar 备份成 .prev → 覆盖 zhuochong.jar → restart → 轮询 /qq/status
# ============================================================
param(
    [string]$RepoRoot   = "D:\IdeaProjects\zhuochong-github",
    [string]$ServerHost = "47.93.180.198",
    [string]$User       = "root",
    [string]$RemoteDir  = "/root/zc/app",
    [string]$Service    = "zhuochong-qq",
    [switch]$NoRestart,
    [switch]$SkipPull,
    [switch]$SetupKey
)

$ErrorActionPreference = "Stop"
$Target  = "${User}@${ServerHost}"
$ProjDir = Join-Path $RepoRoot "zhuochong"
$Jar     = Join-Path $ProjDir "target\zhuochong-0.0.1-SNAPSHOT.jar"

function Step($n, $m) { Write-Host "`n[$n] $m" -ForegroundColor Cyan }
function Ok($m)       { Write-Host "    OK  $m" -ForegroundColor Green }
function Warn($m)     { Write-Host "    !   $m" -ForegroundColor Yellow }
function Die($m)      { Write-Host "    X   $m" -ForegroundColor Red; exit 1 }

# ---------- 可选：安装 SSH 公钥（之后全流程免密） ----------
if ($SetupKey) {
    Step "0" "生成并安装 SSH 公钥到 $Target（只需做一次）"
    $keyDir  = "$env:USERPROFILE\.ssh"
    $keyFile = Join-Path $keyDir "id_ed25519"
    if (-not (Test-Path $keyDir)) { New-Item -ItemType Directory -Force -Path $keyDir | Out-Null }
    if (-not (Test-Path $keyFile)) {
        & ssh-keygen -t ed25519 -N '""' -f $keyFile
        if ($LASTEXITCODE -ne 0) { Die "ssh-keygen 失败" }
        Ok "已生成 $keyFile"
    } else {
        Ok "已存在 $keyFile"
    }
    $pub = (Get-Content "$keyFile.pub" -Raw).Trim()
    & ssh $Target "mkdir -p ~/.ssh && chmod 700 ~/.ssh && (grep -qF '$pub' ~/.ssh/authorized_keys 2>/dev/null || echo '$pub' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys && echo OK"
    if ($LASTEXITCODE -ne 0) { Die "安装公钥失败" }
    Ok "公钥已安装，以后 push-jar.ps1 不再问密码"
    exit 0
}

# ---------- 1) 拉代码 ----------
Step "1/5" "拉取最新代码"
if (-not (Test-Path (Join-Path $RepoRoot ".git"))) { Die "不是 git 仓库：$RepoRoot" }
if ($SkipPull) {
    Warn "指定 -SkipPull：跳过 git pull，用仓库现有代码"
} else {
    Push-Location $RepoRoot
    try {
        & git pull --ff-only
        if ($LASTEXITCODE -ne 0) { Die "git pull 失败（加速器/代理没开？也可加 -SkipPull 用本地代码）" }
    } finally { Pop-Location }
}
Push-Location $RepoRoot
try {
    $rev    = (& git rev-parse --short HEAD).Trim()
    $branch = (& git rev-parse --abbrev-ref HEAD).Trim()
} finally { Pop-Location }
Ok "当前提交 $rev（分支 $branch）"

# ---------- 2) 构建 ----------
Step "2/5" "本地打包（-Pcloud 排除 2GB 语音模型/live2d 等重资源）"
if (-not (Test-Path (Join-Path $ProjDir "pom.xml"))) { Die "找不到 $ProjDir\pom.xml" }
if (Test-Path $Jar) { Remove-Item $Jar -Force }
Push-Location $ProjDir
try {
    & .\mvnw.cmd -B -Pcloud -DskipTests package
    if ($LASTEXITCODE -ne 0) { Die "构建失败（看上面 [ERROR] 那几行）" }
} finally { Pop-Location }
if (-not (Test-Path $Jar)) { Die "构建完成但没找到 $Jar" }
$mb = [math]::Round((Get-Item $Jar).Length / 1MB, 1)
Ok "jar 就绪：$mb MB"

# ---------- 3) 上传 ----------
Step "3/5" "上传到 ${Target}:$RemoteDir/zhuochong.jar.new"
if (-not (Test-NetConnection $ServerHost -Port 22 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    Die "连不上 ${Target}:22（网络或安全组）"
}
& ssh $Target "mkdir -p $RemoteDir"
if ($LASTEXITCODE -ne 0) { Die "ssh 连接失败" }
& scp $Jar "${Target}:$RemoteDir/zhuochong.jar.new"
if ($LASTEXITCODE -ne 0) { Die "scp 上传失败" }
Ok "上传完成"

# ---------- 4) 切换 + 重启 ----------
Step "4/5" "切换 jar 并重启 $Service"
if ($NoRestart) {
    Warn "指定 -NoRestart：jar 已上传为 .new，线上仍是旧版本"
    Write-Host "    生效：ssh $Target 'cd $RemoteDir && cp -f zhuochong.jar zhuochong.jar.prev; mv -f zhuochong.jar.new zhuochong.jar; systemctl restart $Service'" -ForegroundColor Gray
    exit 0
}
$remote = "cd $RemoteDir && cp -f zhuochong.jar zhuochong.jar.prev 2>/dev/null; mv -f zhuochong.jar.new zhuochong.jar; systemctl restart $Service; sleep 10; curl -s --max-time 6 http://127.0.0.1:8080/qq/status"
$out = & ssh $Target $remote 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host $out
    Die "切换/重启失败。回滚：ssh $Target 'cd $RemoteDir && cp -f zhuochong.jar.prev zhuochong.jar && systemctl restart $Service'"
}
Ok "已重启"

# ---------- 5) 体检 ----------
Step "5/5" "体检 /qq/status"
$body = ($out | Out-String).Trim()
if ($body -match '"connected"\s*:\s*true') {
    Ok "NapCat 已连上"
    $self = if ($body -match '"selfId"\s*:\s*(-?\d+)')     { $matches[1] } else { "?" }
    $tok  = if ($body -match '"todayTokens"\s*:\s*(\d+)')  { $matches[1] } else { "?" }
    $emt  = if ($body -match '"emojiCount"\s*:\s*(\d+)')   { $matches[1] } else { "?" }
    Ok "机器人 QQ=$self，今日 token=$tok，表情包 $emt 张"
    Write-Host "`n===== 发布完成：$rev =====" -ForegroundColor Green
    Write-Host "  群里 @ 它说句话（3 秒后回）、发图试视觉、管理员私聊 /status" -ForegroundColor Gray
} elseif ($body -match '"code"\s*:\s*1') {
    Warn "服务已起，但 NapCat WS 未连通（connected:false）"
    Write-Host "    查原因：ssh $Target 'journalctl -u $Service -n 40 --no-pager | grep ""\[QQ\]""'" -ForegroundColor Gray
    Write-Host "`n===== 已发布但未连通：$rev =====" -ForegroundColor Yellow
} else {
    Warn "没拿到预期的 /qq/status，原始输出："
    Write-Host $body
    Write-Host "    日志：ssh $Target 'journalctl -u $Service -n 60 --no-pager'" -ForegroundColor Gray
    Write-Host "    回滚：ssh $Target 'cd $RemoteDir && cp -f zhuochong.jar.prev zhuochong.jar && systemctl restart $Service'" -ForegroundColor Gray
    exit 1
}
