# Prepare release notes

Create release notes for each published version. Keep old notes unchanged so they describe the files users could download at that release.

## Create notes from verified changes

1. Copy [`release-notes-template.md`](release-notes-template.md) into a new file named for the release, such as `release-notes-vX.Y.Z.md`.
2. List only changes present in the release commit and artifacts.
3. State the Android version code, supported Windows and Linux hosts, and any device or OBS limits that were exercised.
4. Include exact asset names and SHA-256 values from the staged artifact manifest.
5. Link to setup instructions and list known issues that affect installation, pairing, streaming, updates, or compatibility.
6. Mark unrun physical checks as unverified. Do not turn a source-level expectation or a passing unit test into a product compatibility claim.
7. Attach the notes to the release that contains those exact tested artifact bytes.

## Keep the evidence with the release

The release coordinator records the tested commit, artifact hashes, automated check results, physical device and OBS results, and unresolved limits in the release evidence record. If any artifact changes after testing, repeat the checks for that artifact before publishing it.
