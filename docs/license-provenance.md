# License and provenance inventory

This inventory records license files and dependency declarations present in the repository at the D1 review. It is not a legal review. The repository has no top-level `LICENSE`, `COPYING`, or `NOTICE` file, and GitHub reports no detected repository license.

## Owner decision required

The project owner must choose and approve a license for OpenStream's original work after reviewing this inventory. No project-wide license is implied by the public GitHub repository or by the licenses of its dependencies. Do not add a root license or describe the repository as open source until the owner has made that choice and the approved file is present.

The repository does not state who owns copyright in the original Android, OBS, website, documentation, and tooling work. Git history shows commits authored as `YashasVM` with the email `admin5@duck.com`; commit authorship alone does not establish copyright ownership. Confirm the correct individual or organization before adding a copyright notice.

## Bundled and declared dependencies

| Component | Evidence in this checkout | License or terms | Distribution notes |
|---|---|---|---|
| SRT 1.5.4 | `android/app/src/main/cpp/third_party/srt/include/srt/version.h`; static libraries under `android/app/src/main/cpp/third_party/srt/lib/` for four Android ABIs | Mozilla Public License 2.0 in `android/app/src/main/cpp/third_party/srt/LICENSE`. SRT headers also contain third-party notices, including University of Illinois and Aladdin Enterprises notices. | Android's normal build links the bundled static libraries. Review the source-form and notice obligations before distributing APKs. The vendored directory contains 49 files and is about 41 MB in this checkout. |
| JUnit 4.13.2 | `android/app/build.gradle.kts` | Declared test dependency. The repository does not vendor its license text. | Test-only dependency. Confirm its license in release dependency reporting if it becomes part of a distributed artifact. |
| JSON 20240303 | `android/app/build.gradle.kts` | Declared test dependency. The repository does not vendor its license text. | Test-only dependency; not declared for the Android runtime. |
| React, React DOM, Vite, and the Vite React plugin | `website/package.json` and lockfile | Declared website dependencies. The repository does not include their license texts. | Website build dependencies. Generate a versioned dependency/license report before distributing a bundled website. |
| OBS Studio, FFmpeg, Qt, Android SDK/NDK, Gradle | Build files and workflows resolve platform development packages or tools. They are not vendored as product libraries here. | Their terms vary by package and distribution. | The Windows plugin links against the OBS/FFmpeg ABI; Linux uses the host OBS, FFmpeg, and Qt libraries. The release package must be checked for bundled runtime files before distribution. |

The inventory above lists dependencies found by inspecting build declarations and the checked-in SRT directory. It is not a complete software bill of materials. It does not establish the licenses of every transitive build dependency or every file in historical release artifacts.

## Copyright notices found

- SRT headers name Haivision Systems Inc. and include additional upstream notices.
- The checked-in Gradle wrapper scripts contain Apache-2.0 notices for the wrapper scripts.
- No project-wide copyright notice was found outside dependency files and generated tooling.

## Evidence and refresh commands

Run these commands from the repository root when refreshing this inventory:

```sh
rg --files -g 'LICENSE*' -g 'COPYING*' -g 'NOTICE*' -g '*license*' -g '*LICENSE*'
rg -n 'Copyright|copyright|©|SPDX-License-Identifier' --glob '!android/app/src/main/cpp/third_party/srt/LICENSE' .
rg -n 'implementation\(|api\(|testImplementation\(|find_package\(|FetchContent|ExternalProject' android obs-plugin website
```

The published v1.0.1 GitHub release is dated 2026-08-04 and lists `openstream-android.apk`, `openstream-obs-plugin-installer-windows-x64.exe`, and `openstream-obs-windows-x64.zip`. GitHub reports these SHA-256 digests for those assets:

| Published asset | SHA-256 reported by GitHub |
|---|---|
| `openstream-android.apk` | `0a442b4123f97adab6c93953b869bf55d2813a2354d84cad21a498fa7107cc5b` |
| `openstream-obs-plugin-installer-windows-x64.exe` | `27b6f7380fb10190fbb0c422eb5d9fb641228384649fedd6bfaaae2818665a1d` |
| `openstream-obs-windows-x64.zip` | `31e6ad1ea64d0829aa689dc7abed361ba99ebeab1b87d1373786b40d6f8ff619` |

These digests identify the published files. They do not verify the APK package ID, version code, signing certificate, plugin source IDs, or scene compatibility. The release notes claim V7 scene compatibility, while the current source and docs do not prove it. B1 must inspect the published APK and test an old scene in real OBS before release compatibility claims are repeated.
