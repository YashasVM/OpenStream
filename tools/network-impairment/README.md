# V4 deterministic impairment harness

`impair.py` applies bounded random loss, burst loss, jitter, reordering, outage, and one-session disconnect to simulator JSONL. It records original source timestamps, computed arrival timestamps, and all missing ranges. This validates workload accounting; it does not emulate SRT or RTP recovery.
