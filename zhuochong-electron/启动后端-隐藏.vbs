' ============================================================
'  桌宠后端：隐藏窗口启动（托盘菜单 / 等待卡片调用）
'  日志写到 logs\backend.log；想看着命令行窗口跑，改双击 启动后端.bat
' ============================================================
Option Explicit
Dim sh, fso, dir, logs, logFile, cmd
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
logs = dir & "\logs"
If Not fso.FolderExists(logs) Then fso.CreateFolder(logs)
logFile = logs & "\backend.log"
If fso.FileExists(logFile) Then
    If fso.GetFile(logFile).Size > 4194304 Then fso.DeleteFile logFile
End If
sh.CurrentDirectory = dir
cmd = "cmd /c ""启动后端.bat >> logs\backend.log 2>&1"""
sh.Run cmd, 0, False
