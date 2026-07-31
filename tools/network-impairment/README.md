# V4 deterministic impairment harness

`impair.py` applies bounded random loss, burst loss, jitter, reordering, outage, and one-session disconnect to simulator JSONL. It requires the source `.manifest.json` expected-corpus declaration and copies it beside the output, then records original source timestamps, computed arrival timestamps, and all missing ranges. Timing values must be finite and non-negative; burst length is at least one. This validates workload accounting; it does not emulate SRT or RTP recovery.
