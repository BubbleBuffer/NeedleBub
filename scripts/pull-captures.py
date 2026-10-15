"""Pull one authenticated capture snapshot; never accept a truncated export."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def validate_capture(path):
    digest = hashlib.sha256()
    identifiers = set()
    count = 0
    manifest = None
    with path.open('rb') as stream:
        for line_number, line in enumerate(stream, 1):
            digest.update(line)
            try:
                row = json.loads(line)
            except (ValueError, UnicodeError):
                raise ValueError(f'Invalid or incomplete JSON at line {line_number}') from None
            if not isinstance(row, dict):
                raise ValueError(f'Expected an object at line {line_number}')
            if line_number == 1:
                manifest = row
                if row.get('type') != 'needlebub.capture.adb' or type(row.get('formatVersion')) is not int or row['formatVersion'] not in (1, 2, 3):
                    raise ValueError('Unsupported capture manifest')
                if type(row.get('recordCount')) is not int or row['recordCount'] < 0:
                    raise ValueError('Invalid manifest record count')
                continue
            if row.get('schema') not in {f'needlebub.capture.record.v{i}' for i in (1, 2, 3)}:
                raise ValueError(f'Unsupported record schema at line {line_number}')
            identifier = row.get('id')
            if not isinstance(identifier, str) or not identifier or identifier in identifiers:
                raise ValueError(f'Missing or duplicate record ID at line {line_number}')
            identifiers.add(identifier)
            count += 1
    if manifest is None or count != manifest['recordCount']:
        raise ValueError('Incomplete export: record count does not match manifest')
    return {'recordCount': count, 'sha256': digest.hexdigest(), 'formatVersion': manifest['formatVersion']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit authorized device serial')
    parser.add_argument('--output', type=Path, required=True, help='New private local JSONL path')
    parser.add_argument('--adb', default=str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    args = parser.parse_args()
    output = args.output.resolve()
    partial = output.with_suffix(output.suffix + '.partial')
    if output.exists() or partial.exists():
        parser.error('Output or partial file already exists; choose a new path')
    output.parent.mkdir(parents=True, exist_ok=True)
    # The extra phone-side pipe decouples Android content's copy from ADB stdout.
    # No plaintext file is created on the phone, and no automatic retry occurs.
    command = [args.adb, '-s', args.serial, 'shell', '-T', '-n',
               'set -o pipefail; content read --uri content://de.x0bubbuff.needlebub.developer/captures | cat']
    try:
        with partial.open('xb') as target:
            result = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=target,
                                    stderr=subprocess.PIPE, timeout=180)
        if result.returncode != 0:
            raise ValueError(f'ADB transfer failed (exit {result.returncode})')
        summary = validate_capture(partial)
        # Link atomically without replacing any pre-existing successful export.
        os.link(partial, output)
        partial.unlink()
        print(json.dumps({**summary, 'output': str(output)}))
        return 0
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        # Never echo device stdout/stderr or notification text in diagnostics.
        detail = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(f'Export failed: {detail}. Partial data, if any: {partial}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
