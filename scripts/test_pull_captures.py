import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('pull_captures', Path(__file__).with_name('pull-captures.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CaptureValidationTest(unittest.TestCase):
    def validate(self, rows, suffix=b''):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'capture.jsonl'
            path.write_bytes(b''.join(json.dumps(row).encode() + b'\n' for row in rows) + suffix)
            return module.validate_capture(path)

    def manifest(self, count):
        return {'type': 'needlebub.capture.adb', 'formatVersion': 3, 'recordCount': count}

    def record(self, identifier):
        return {'schema': 'needlebub.capture.record.v3', 'id': identifier}

    def test_full_export(self):
        rows = [self.manifest(4711)] + [self.record(str(i)) for i in range(4711)]
        self.assertEqual(4711, self.validate(rows)['recordCount'])

    def test_truncation_at_record_boundary(self):
        with self.assertRaisesRegex(ValueError, 'record count'):
            self.validate([self.manifest(4711), self.record('one')])

    def test_truncation_inside_record(self):
        with self.assertRaisesRegex(ValueError, 'incomplete JSON'):
            self.validate([self.manifest(2), self.record('one')], b'{"id":')

    def test_duplicate_ids(self):
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.validate([self.manifest(2), self.record('one'), self.record('one')])

    def test_empty_valid_snapshot(self):
        self.assertEqual(0, self.validate([self.manifest(0)])['recordCount'])

    def test_error_response_is_not_a_capture(self):
        with self.assertRaisesRegex(ValueError, 'manifest'):
            self.validate([{'error': 'Access denied'}])


if __name__ == '__main__':
    unittest.main()
