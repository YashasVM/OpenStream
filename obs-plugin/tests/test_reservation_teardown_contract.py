from pathlib import Path

source = Path(__file__).parents[1] / "src" / "openstream-source.cpp"
text = source.read_text(encoding="utf-8")

required = [
    "bool reservation_acquired = false;",
    "reservation_acquired = true;",
    "if (!reservation_acquired || !phone.has_value())",
    "if (ctx->stop_requested.load()) {\n        queue_release_phone(ctx, *phone);\n        break;",
]

for snippet in required:
    if snippet not in text:
        raise SystemExit(f"missing reservation teardown contract: {snippet}")

success = text.index("reservation_acquired = true;")
assignment = text.index("reserved_phone = phone;", success)
stop_release = text.index("queue_release_phone(ctx, *phone);", success)
if not success < stop_release < assignment:
    raise SystemExit("reservation must be released on stop before reserved_phone/SRT activation")

print("reservation teardown contract OK")
