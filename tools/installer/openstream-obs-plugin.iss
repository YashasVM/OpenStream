#ifndef PluginDll
  #define PluginDll "..\..\obs-plugin\build\openstream-obs.dll"
#endif

#ifndef OutputDir
  #define OutputDir "..\..\artifacts"
#endif

#ifndef OutputBaseFilename
  #define OutputBaseFilename "openstream-obs-plugin-installer-windows-x64"
#endif

#ifndef OpenStreamVersion
  #define OpenStreamVersion "0.1.1-beta"
#endif

[Setup]
AppId={{F4B20A0C-09B9-4A62-AB51-67E13EDCF8F3}
AppName=OpenStream OBS Plugin
AppPublisher=OpenStream
AppPublisherURL=https://openstream.pages.dev
AppSupportURL=https://github.com/YashasVM/OpenStream/issues
AppUpdatesURL=https://github.com/YashasVM/OpenStream/releases
AppVersion={#OpenStreamVersion}
DefaultDirName={autopf}\obs-studio
AppendDefaultDirName=no
DisableProgramGroupPage=yes
DisableReadyPage=no
DisableFinishedPage=no
OutputDir={#OutputDir}
OutputBaseFilename={#OutputBaseFilename}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
Uninstallable=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#PluginDll}"; DestDir: "{app}\obs-plugins\64bit"; DestName: "openstream-obs.dll"; Flags: ignoreversion

[Code]
function LooksLikeObsDir(Dir: string): Boolean;
begin
  Result :=
    FileExists(AddBackslash(Dir) + 'bin\64bit\obs64.exe') or
    FileExists(AddBackslash(Dir) + 'bin\64bit\obs.exe');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;

  if CurPageID = wpSelectDir then
  begin
    if not LooksLikeObsDir(WizardDirValue) then
    begin
      Result :=
        MsgBox(
          'This folder does not look like an OBS Studio install.' + #13#10 + #13#10 +
          'Choose the folder that contains bin\64bit\obs64.exe, usually C:\Program Files\obs-studio.' + #13#10 + #13#10 +
          'Continue anyway?',
          mbConfirmation,
          MB_YESNO
        ) = IDYES;
    end;
  end;
end;
