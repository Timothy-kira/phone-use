"""Exact-text patcher for porting Ditto sources: every replacement must match exactly once.

    python patch.py <file> <patch.json>

patch.json is a list of [old, new] pairs. A pair whose `old` is missing or ambiguous aborts the
whole file untouched, so a port never half-applies.
"""
import json
import sys


def main() -> int:
    path, spec = sys.argv[1], sys.argv[2]
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    with open(spec, encoding="utf-8") as fh:
        pairs = json.load(fh)
    for i, (old, new) in enumerate(pairs):
        count = text.count(old)
        if count != 1:
            print(f"pair {i}: expected 1 match, found {count}: {old[:80]!r}", file=sys.stderr)
            return 1
        text = text.replace(old, new)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print(f"{path}: {len(pairs)} replacements")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
