#!/usr/bin/env python3
"""Emit GitHub annotations for failed host-stage tests from manifest.json (read-only)."""
import argparse
import json
import sys
from pathlib import Path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--name', default='manifest.json')
    parser.add_argument('--limit', type=int, default=200)
    args = parser.parse_args(argv)
    candidates = sorted(args.root.rglob(args.name)) if args.root.is_dir() else [args.root]
    if not candidates:
        print('::warning title=Host failure manifest::no manifest.json under %s' % args.root)
        return 0
    data = json.loads(candidates[0].read_text(encoding='utf-8'))
    rows = data['tests'] if isinstance(data, dict) and 'tests' in data else data
    count = 0
    for row in rows:
        if row.get('status') == 'PASS':
            continue
        if count >= args.limit:
            print('::warning title=Host failure limit::more than %d failing tests' % args.limit)
            break
        count += 1
        reason = str(row.get('exitCode'))
        print(f'::error title=Host test failure::{row.get("path")} (exit {reason})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
