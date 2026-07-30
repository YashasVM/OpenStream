#ifndef PluginDll
  #define PluginDll "..\..\obs-plugin\build\openstream-beta-obs.dll"
#endif

#ifndef OutputDir
  #define OutputDir "..\..\artifacts"
#endif

#ifndef OutputBaseFilename
  #define OutputBaseFilename "openstream-beta-obs-plugin-installer-windows-x64"
#endif

#ifndef OpenStreamVersion
  #define OpenStreamVersion "2.1.1-beta"
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
Source: "{#PluginDll}"; DestDir: "{app}\obs-plugins\64bit"; DestName: "openstream-beta-obs.dll"; Flags: ignoreversion
Source: "..\..\obs-plugin\data\*"; DestDir: "{app}\data\obs-plugins\openstream-beta-obs"; Flags: recursesubdirs createallsubdirs ignoreversion

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

function InitializeSetup(): Boolean;
var
  ResultCode: Integer;
begin
  Result := True;
  Exec('powershell.exe', '-NoProfile -Command "if (Get-Process obs64,obs -ErrorAction SilentlyContinue) { exit 1 }"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if ResultCode <> 0 then begin
    MsgBox('Close OBS Studio completely before installing OpenStream.', mbError, MB_OK);
    Result := False;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Names: array[0..1] of String;
  Roots: array[0..4] of String;
  I, J: Integer;
  Candidate: String;
begin
  if CurStep <> ssInstall then exit;
  Names[0] := 'openstream-obs.dll'; Names[1] := 'openstream-beta-obs.dll';
  Roots[0] := ExpandConstant('{app}\obs-plugins\64bit');
  Roots[1] := ExpandConstant('{commonappdata}\obs-studio\plugins\openstream-beta-obs\bin\64bit');
  Roots[2] := ExpandConstant('{commonappdata}\obs-studio\plugins\openstream-obs\bin\64bit');
  Roots[3] := ExpandConstant('{userappdata}\obs-studio\plugins\openstream-beta-obs\bin\64bit');
  Roots[4] := ExpandConstant('{userappdata}\obs-studio\plugins\openstream-obs\bin\64bit');
  for I := 0 to 4 do for J := 0 to 1 do begin
    Candidate := AddBackslash(Roots[I]) + Names[J];
    if FileExists(Candidate) then begin
      DeleteFile(Candidate);
      Log('Migrated legacy OpenStream module: ' + Candidate);
    end;
  end;
end;
