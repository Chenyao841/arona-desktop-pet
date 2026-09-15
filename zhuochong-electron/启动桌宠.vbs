' ============================================================
'  桌面桌宠：隐藏窗口启动（桌面快捷方式「桌面桌宠」指向本文件）
'  WScript.Shell.Run(cmd, 0, False) 的 0 = 窗口完全隐藏，不闪命令提示符
'  实际启动逻辑仍走 start.bat；输出写到 logs\electron.log（藏了窗口后看这个文件）
'  想要可见的命令行窗口调试：直接双击 start.bat
' ============================================================
Option Explicit
Dim sh, fso, dir, logs, logFile, cmd
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
logs = dir & "\logs"
If Not fso.FolderExists(logs) Then fso.CreateFolder(logs)
logFile = logs & "\electron.log"
' 启动时若日志超过 4MB 就先删掉，避免无限增长
If fso.FileExists(logFile) Then
    If fso.GetFile(logFile).Size > 4194304 Then fso.DeleteFile logFile
End If
sh.CurrentDirectory = dir
cmd = "cmd /c ""start.bat >> logs\electron.log 2>&1"""
sh.Run cmd, 0, False
