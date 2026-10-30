#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('fetcher', ROOT / 'scripts/prepare-page-aligned-native-inputs.py')
FETCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCHER)


class NativeFetchTest(unittest.TestCase):
    def setUp(self):
        self.owned = tempfile.TemporaryDirectory(prefix='native-fetch-contract-')
        self.root = Path(self.owned.name)
        self.path = self.root / 'publisher-source'
        self.data = b'original publisher bytes'
        self.identity = {'url': 'https://dl.google.com/exact-publisher-artifact',
                         'sha256': hashlib.sha256(self.data).hexdigest()}

    def tearDown(self):
        self.owned.cleanup()

    def test_verified_cache_is_used_offline(self):
        self.path.write_bytes(self.data)
        FETCHER.fetch(self.identity, self.path, True)

    def test_same_size_cache_tamper_is_rejected(self):
        self.path.write_bytes(b'X' + self.data[1:])
        with self.assertRaisesRegex(ValueError, 'differs'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_missing_offline_cache_is_an_error(self):
        with self.assertRaisesRegex(ValueError, 'offline'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_cached_symlink_is_rejected(self):
        original = self.root / 'outside'
        original.write_bytes(self.data)
        self.path.symlink_to(original)
        with self.assertRaisesRegex(ValueError, 'differs'):
            FETCHER.fetch(self.identity, self.path, True)

    def test_foreign_publisher_is_refused_before_network(self):
        identity = {**self.identity, 'url': 'https://foreign.invalid/source'}
        with self.assertRaisesRegex(ValueError, 'publisher URL'):
            FETCHER.fetch(identity, self.path, False)

    def test_cache_parent_symlink_is_rejected(self):
        outside = self.root / 'outside-cache'
        outside.mkdir()
        (outside / 'publisher-source').write_bytes(self.data)
        parent = self.root / 'alias'
        parent.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'parent'):
            FETCHER.fetch(self.identity, parent / 'publisher-source', True)

    def test_credential_bearing_publisher_is_refused(self):
        identity = {**self.identity, 'url': 'https://synthetic:synthetic@dl.google.com/source'}
        with self.assertRaisesRegex(ValueError, 'publisher URL'):
            FETCHER.fetch(identity, self.path, False)


if __name__ == '__main__':
    unittest.main()
