# ============================================================
# 本地：把 D:\IdeaProjects\zhuochong 的改动同步进 GitHub 仓库工作区，然后提示提交推送
#
# 背景：本地开发目录 D:\IdeaProjects\zhuochong 不是 git 仓库（没有 .git），
#       真正的仓库在 D:\IdeaProjects\zhuochong-github（remote = arona-desktop-pet）。
#       而且本地 static 里有近 2 GB 语音模型/live2d 资源（仓库根 .gitignore 已排除），
#       所以这里**只挑需要的目录**同步，绝不整目录复制。
#
# 用法（PowerShell）：
#   powershell -ExecutionPolicy Bypass -File deploy\sync-to-repo.ps1
#   powershell -ExecutionPolicy Bypass -File deploy\sync-to-repo.ps1 -Commit "feat: xxx"
#
# -Commit 传了就顺手 commit（不 push，push 你自己确认）
# ============================================================
param(
    [string]$RepoRoot = "D:\IdeaProjects\zhuochong-github",
    [string]$Source   = "D:\IdeaProjects\zhuochong",
    [string]$Commit   = ""
)

$ErrorActionPreference = "Stop"
function Say($m)  { Write-Host "[sync] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[sync] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[sync] $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "[sync] $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path "$RepoRoot\.git")) { Die "仓库目录不对：$RepoRoot（找不到 .git）" }
if (-not (Test-Path "$Source\pom.xml")) { Die "源码目录不对：$Source（找不到 pom.xml）" }

$Dest = Join-Path $RepoRoot "zhuochong"
if (-not (Test-Path $Dest)) { Die "仓库里没有 zhuochong/ 子目录：$Dest" }

# 本地这些重资源仓库不跟踪（仓库根 .gitignore 已排除），同步时直接跳过，省时间
$ExcludeDirs = @(
    "GPT_weights_v2", "SoVITS_weights_v2", "live2d", "background", "aluona",
    "bottom", "soundeffects", "参考音频", "麻将参考截屏", "麻将错题截屏",
    "标注图片", "motions", "effects", "music", "node_modules"
)

Say "同步源码与配置：$Source → $Dest"

function Mirror($rel) {
    $srcPath = Join-Path $Source $rel
    if (-not (Test-Path $srcPath)) { return }
    $dstPath = Join-Path $Dest $rel
    New-Item -ItemType Directory -Force -Path $dstPath | Out-Null
    $args = @($srcPath, $dstPath, "/MIR", "/NFL", "/NDL", "/NJH", "/NJS", "/NP")
    if ($ExcludeDirs.Count -gt 0) { $args += "/XD"; $args += $ExcludeDirs }
    & robocopy @args | Out-Null
    if ($LASTEXITCODE -ge 8) { Die "robocopy 失败（$rel）：exit=$LASTEXITCODE" }
    Say "  ✓ $rel"
}

# 代码与资源（resources 里的大目录由 /XD 跳过）
Mirror "src\main\java"
Mirror "src\main\resources"
Mirror "src\test"
Mirror "deploy"
Mirror "tools"
Mirror "docs"
Mirror "db"
Mirror "sql"
Mirror ".mvn"

# 单文件
foreach ($f in @("pom.xml", "mvnw", "mvnw.cmd", ".gitattributes", ".gitignore",
                 "一键启动.bat", "环境变量配置.bat", "启动QQ机器人.bat",
                 "HELP.md", "PROJECT_OVERVIEW.MD", "ocr_server.py", "pet_eye_ratio.txt")) {
    $p = Join-Path $Source $f
    if (Test-Path $p) { Copy-Item $p (Join-Path $Dest $f) -Force; Say "  ✓ $f" }
}

# 顺手检查：仓库里有没有混进密钥/大文件
Say "检查即将提交的内容..."
Push-Location $RepoRoot
try {
    $status = git status --porcelain
    if (-not $status) { Warn "仓库工作区没有任何变化（可能已经同步过了）" }
    else {
        Write-Host ""
        Write-Host "---- git status ----" -ForegroundColor DarkGray
        $status | Select-Object -First 40 | ForEach-Object { Write-Host "  $_" }

        $big = git status --porcelain | ForEach-Object { $_.Substring(3).Trim('"') } |
               Where-Object { $_ -and (Test-Path $_) -and ((Get-Item $_).Length -gt 5MB) }
        if ($big) { Warn "⚠ 有 >5MB 的文件要提交，确认是不是该进仓库："; $big | ForEach-Object { Warn "   $_" } }

        $secrets = git status --porcelain | ForEach-Object { $_.Substring(3).Trim('"') } |
                   Where-Object { $_ -match '(?i)(\.env|notes\.txt|token|password)' }
        if ($secrets) { Warn "⚠ 疑似密钥文件，别提交："; $secrets | ForEach-Object { Warn "   $_" } }

        if ($Commit) {
            git add -A
            git commit -m $Commit
            if ($LASTEXITCODE -eq 0) { Ok "已提交：$Commit" } else { Warn "commit 失败（可能没改动）" }
        }
    }
    Write-Host ""
    Ok "下一步（在 $RepoRoot 里手动执行）："
    Write-Host '   git add -A; git commit -m "feat: 你的说明"' -ForegroundColor Gray
    Write-Host '   git tag v0.6.0; git push origin main --tags' -ForegroundColor Gray
    Write-Host "然后在服务器上：bash /root/arona/zhuochong/deploy/update.sh" -ForegroundColor Gray
}
finally { Pop-Location }
