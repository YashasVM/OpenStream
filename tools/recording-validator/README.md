# V4 recording validator

`validate.py` validates MKV segments with `ffprobe` and optionally compares an impaired access-unit JSONL file with its `.gaps.json` declaration. Output is JSON; any unreadable segment, missing video stream, packet timestamp regression, or undeclared sequence gap fails the command.
