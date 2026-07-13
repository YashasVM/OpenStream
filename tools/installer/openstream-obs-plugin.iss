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
  #define OpenStreamVersion "2.1.0-beta"
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
Uninstallable=yes
CloseApplications=yes
RestartApplications=no
ChangesEnvironment=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#PluginDll}"; DestDir: "{app}\obs-plugins\64bit"; DestName: "openstream-obs.dll"; Flags: ignoreversion restartreplace

[InstallDelete]
; V2 preview builds used this non-standard per-user location. Keeping exactly
; one canonical copy prevents OBS from loading stale plugin versions.
Type: files; Name: "{userappdata}\obs-studio\plugins\openstream-obs.dll"
Type: files; Name: "{userappdata}\obs-studio\plugins\openstream-obs\bin\64bit\openstream-obs.dll"
Type: files; Name: "{commonappdata}\obs-studio\plugins\openstream-obs\bin\64bit\openstream-obs.dll"

