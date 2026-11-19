!include LogicLib.nsh
!include "Win\RestartManager.nsh"

; 只读查询配套映像占用，不结束进程或在清理完成前替换文件。
!macro ZhijieRequireUnusedFile path
  ${If} ${FileExists} "${path}"
    !insertmacro ZhijieCheckFileUsage "${path}"
  ${EndIf}
!macroend

!macro ZhijieCheckFileUsage path
  Push $0
  Push $R0
  Push $R1
  Push $R2
  Push $R3
  Push $R4
  !insertmacro RestartManager_StartSession $R0
  ${If} $R0 == ""
    !insertmacro ZhijieUsageBlocked
  ${EndIf}
  !insertmacro RestartManager_RegisterFile $R0 "${path}"
  ${If} $0 != 0
    !insertmacro RestartManager_EndSession $R0
    !insertmacro ZhijieUsageBlocked
  ${EndIf}
  StrCpy $R1 0
  StrCpy $R2 0
  StrCpy $R3 0
  System::Call 'RSTRTMGR::RmGetList(i R0, *i .R1, *i R2, p 0, *i .R3) i.R4'
  !insertmacro RestartManager_EndSession $R0
  ${If} $R4 != 0
  ${OrIf} $R1 != 0
  ${OrIf} $R2 != 0
    !insertmacro ZhijieUsageBlocked
  ${EndIf}
  Pop $R4
  Pop $R3
  Pop $R2
  Pop $R1
  Pop $R0
  Pop $0
!macroend

!macro ZhijieUsageBlocked
  Pop $R4
  Pop $R3
  Pop $R2
  Pop $R1
  Pop $R0
  Pop $0
  SetErrorLevel 2
  DetailPrint "织界相关文件仍被使用或无法确认其状态。请正常关闭织界，等待网络清理完成后重试。"
  IfSilent +2 0
    MessageBox MB_ICONSTOP|MB_OK "请正常关闭织界，等待网络清理完成后重试。若问题持续，请联系支持人员。"
  Abort
!macroend

!macro ZhijieRequireStopped
  !insertmacro ZhijieRequireUnusedFile "$INSTDIR\zhijie-shell.exe"
  !insertmacro ZhijieRequireUnusedFile "$INSTDIR\zhijie-helper.exe"
  !insertmacro ZhijieRequireUnusedFile "$INSTDIR\wintun.dll"
!macroend

; Tauri 在项目钩子后再次检查主程序；复用只读门禁，避免竞态时强制关闭。
!ifmacrodef CheckIfAppIsRunning
  !macroundef CheckIfAppIsRunning
!endif
!macro CheckIfAppIsRunning executablePath productName
  !insertmacro ZhijieRequireUnusedFile "${executablePath}"
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro ZhijieRequireStopped
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro ZhijieRequireStopped
!macroend
