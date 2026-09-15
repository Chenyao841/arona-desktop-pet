# ============================================================
#  停止桌宠后端（端口 8080）
#  用法：pwsh -File 停止后端.ps1     （由桌面桌宠托盘菜单的「重启后端服务」调用）
#
#  做两件事：
#    1. 杀掉监听 8080 的进程（后端本身，不管是 IDEA 起的还是脚本起的）
#    2. 杀掉跑「启动后端.bat」的命令行窗口（连同它的 Maven / Java 子进程），
#       让那个日志窗口干净地关掉，而不是卡在「请按任意键继续」
#
#  注意：本文件必须保存为 UTF-8 with BOM，否则 Windows PowerShell 5.1
#        会按 GBK 解析，脚本里的中文会乱掉。
# ============================================================
$ErrorActionPreference = 'SilentlyContinue'

$pids = @()

# 1) 监听 8080 的进程
$pids += Get-NetTCPConnection -LocalPort 8080 -State Listen |
    Select-Object -ExpandProperty OwningProcess -Unique

# 2) 跑「启动后端.bat」的 cmd 窗口（含它的子进程）
$pids += Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" |
    Where-Object { $_.CommandLine -like '*启动后端.bat*' } |
    Select-Object -ExpandProperty ProcessId

$pids = $pids | Where-Object { $_ -and $_ -gt 0 } | Sort-Object -Unique

if (-not $pids -or $pids.Count -eq 0) {
    Write-Output 'no backend process found (port 8080 is free)'
    exit 0
}

foreach ($procId in $pids) {
    taskkill /PID $procId /T /F 2>$null | Out-Null
    Write-Output ("stopped PID " + $procId)
}
exit 0
