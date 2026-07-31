# V4 recording validator

`validate.py` validates MKV segments with `ffprobe` and compares an impaired access-unit JSONL file with its `.gaps.json` declaration and required `.manifest.json` expected-corpus declaration. The manifest lists every expected session and its inclusive sequence range, so leading, trailing, and entirely absent sessions are detected. Output is JSON; any unreadable segment, missing video stream, packet timestamp regression, unexpected sequence, or undeclared gap fails the command.
