; The Windows installer, built on Linux with NSIS (makensis) by
; :desktop:windowsX64. It installs for the current user only, with no
; administrator prompt, into %LOCALAPPDATA%\Programs\Acidulous, with a Start
; menu entry and an uninstall entry in Settings > Apps.
;
; Songs and settings live in %APPDATA%\Acidulous, so upgrading or
; uninstalling leaves them alone.
;
;   makensis -DVERSION=0.9.8 -DSTAGE=<the staged folder> -DOUT=<setup.exe> installer.nsi

Unicode true
SetCompressor /SOLID lzma

!ifndef VERSION | STAGE | OUT
  !error "VERSION, STAGE and OUT are given by the build"
!endif

!define KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\Acidulous"

Name "Acidulous"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\Acidulous"
InstallDirRegKey HKCU "${KEY}" "InstallLocation"
RequestExecutionLevel user

VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "Acidulous"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "Acidulous setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "Copyright (C) 2026 Dan Hunke. GPLv3 or later."

!include "MUI2.nsh"
!define MUI_ICON "acidulous.ico"
!define MUI_UNICON "acidulous.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\Acidulous.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Start Acidulous"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section
  SetOutPath "$INSTDIR"
  ; An upgrade replaces the app and its Java completely, since a jar left
  ; over from the last version would still be on the class path.
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  File /r "${STAGE}\*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "$SMPROGRAMS\Acidulous.lnk" "$INSTDIR\Acidulous.exe"

  WriteRegStr HKCU "${KEY}" "DisplayName" "Acidulous"
  WriteRegStr HKCU "${KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${KEY}" "Publisher" "Dan Hunke"
  WriteRegStr HKCU "${KEY}" "DisplayIcon" "$INSTDIR\Acidulous.exe"
  WriteRegStr HKCU "${KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${KEY}" "URLInfoAbout" "https://github.com/roge-rm/Acidulous"
  WriteRegDWORD HKCU "${KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Delete "$SMPROGRAMS\Acidulous.lnk"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\Acidulous.exe"
  Delete "$INSTDIR\NOTICE.txt"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${KEY}"
SectionEnd
