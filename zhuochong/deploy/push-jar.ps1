# ============================================================
# 本地：打包成云端 jar 并直接上传到服务器（不想在服务器上构建时用）
#
# 前提：
#   1. 服务器已经能 ssh（ssh root@47.93.180.198）
#   2. 本地能跑 mvnw
# 用法：
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1
#   powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -Host 47.93.180.198 -Restart
#
# 它做的事：mvn -Pcloud package（排除 2GB 重资源）→ scp 到 /root/zc/app/zhuochong.jar
#           → 可选 systemctl restart → 体检 /qq/status
# ============================================================
param(
    [string]$ServerHost = "47.93.180.198",
    [string]$User       = "root",
    [string]$RemoteDir  = "/root/zc/app",
    [string]$Service    = "zhuochong-qq",
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
function Say($m)  { Write-Host "[push] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[push] $m" -ForegroundColor Green }
function Die($m)  { Write-Host "[push] $m" -ForegroundColor Red; exit 1 }

$ProjDir = Split-Path -Parent $PSScriptRoot      # deploy 的上一层 = zhuochong
Push-Location $ProjDir
try {
    Say "打包（-Pcloud，排除语音模型/live2d 等重资源）..."
    & .\mvnw.cmd -o -Pcloud -DskipTests package
    if ($LASTEXITCODE -ne 0) { Die "打包失败" }

    $jar = Join-Path $ProjDir "target\zhuochong-0.0.1-SNAPSHOT.jar"
    if (-not (Test-Path $jar)) { Die "没找到 $jar" }
    $mb = [math]::Round((Get-Item $jar).Length / 1MB, 1)
    Ok "jar 大小：$mb MB"

    Say "上传到 ${User}@${ServerHost}:$RemoteDir/zhuochong.jar ..."
    & ssh "${User}@${ServerHost}" "mkdir -p $RemoteDir && cp -f $RemoteDir/zhuochong.jar $RemoteDir/zhuochong.jar.prev 2>/dev/null || true"
    & scp $jar "${User}@${ServerHost}:$RemoteDir/zhuochong.jar"
    if ($LASTEXITCODE -ne 0) { Die "scp 失败" }
    Ok "上传完成"

    if ($Restart) {
        Say "重启 $Service 并体检..."
        & ssh "${User}@${ServerHost}" "systemctl restart $Service && sleep 6 && curl -s --max-time 5 http://127.0.0.1:8080/qq/status"
        if ($LASTEXITCODE -ne 0) { Die "重启或体检失败，去服务器看 journalctl -u $Service -n 60" }
        Ok "已重启（上面是 /qq/status 返回）"
    } else {
        Say "没有传 -Restart，线上还是旧版本；手工生效：ssh ${User}@${ServerHost} 'systemctl restart $Service'"
    }
}
finally { Pop-Location }
