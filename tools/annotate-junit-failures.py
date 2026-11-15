#!/usr/bin/env python3
"""Emit GitHub annotations for failed JUnit XML cases (read-only; never changes reports)."""
import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def failures(reports):
    for path in sorted(Path(reports).glob('TEST-*.xml')):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter('testcase'):
            bad = case.find('failure')
            if bad is None:
                bad = case.find('error')
            if bad is None:
                continue
            name = (case.get('classname') or '?') + '#' + (case.get('name') or '?')
            message = ' '.join((bad.get('message') or '').split())[:180]
            yield name, message


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports', type=Path)
    parser.add_argument('--limit', type=int, default=200)
    args = parser.parse_args(argv)
    count = 0
    for name, message in failures(args.reports):
        if count >= args.limit:
            print('::warning title=JUnit failure limit::more than %d failing tests' % args.limit)
            break
        count += 1
        if message:
            print(f'::error title=JUnit failure::{name} — {message}')
        else:
            print(f'::error title=JUnit failure::{name}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
