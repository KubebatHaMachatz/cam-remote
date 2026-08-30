"""Tests for configuration resolution.

Two sources can name an agent -- a command-line flag and an environment variable -- and the file
`camremote pair` saves, so the order they win in is a contract, not an implementation detail.
"""

import os
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from camremote import config
from camremote.errors import CamRemoteError


class LoadTest(unittest.TestCase):
    """Reading the saved agent, including when there is nothing saved."""

    def test_reads_a_saved_agent(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "camremote.toml"
            path.write_text('host = "10.0.0.4"\nport = 8099\n')

            loaded = config.load(path)

            self.assertEqual("10.0.0.4", loaded.host)
            self.assertEqual(8099, loaded.port)

    def test_a_missing_file_is_not_an_error(self):
        # The very first run has no config, and telling the operator off for that would be silly.
        loaded = config.load(Path("/nonexistent/camremote.toml"))

        self.assertIsNone(loaded.host)
        self.assertEqual(config.DEFAULT_PORT, loaded.port)

    def test_a_malformed_file_says_which_file(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "camremote.toml"
            path.write_text("this is not toml = = =")

            with self.assertRaises(CamRemoteError) as caught:
                config.load(path)

            self.assertIn(str(path), str(caught.exception))


class SaveTest(unittest.TestCase):
    """Writing it back, so later commands do not need --host every time."""

    def test_round_trips(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "camremote.toml"

            config.save(config.AgentConfig(host="10.0.0.4", port=9000), path)

            self.assertEqual(config.load(path).host, "10.0.0.4")
            self.assertEqual(config.load(path).port, 9000)


class ResolveTest(unittest.TestCase):
    """Precedence between flag, environment and file -- a contract, not an accident."""

    def setUp(self):
        self.directory = TemporaryDirectory()
        self.path = Path(self.directory.name) / "camremote.toml"
        self.path.write_text('host = "from-file"\nport = 1111\n')
        self.addCleanup(self.directory.cleanup)

    def test_uses_the_file_when_nothing_else_is_given(self):
        resolved = config.resolve(host=None, port=None, path=self.path)

        self.assertEqual("from-file", resolved.host)
        self.assertEqual(1111, resolved.port)

    @mock.patch.dict(os.environ, {"CAMREMOTE_HOST": "from-env"})
    def test_environment_beats_the_file(self):
        resolved = config.resolve(host=None, port=None, path=self.path)

        self.assertEqual("from-env", resolved.host)

    @mock.patch.dict(os.environ, {"CAMREMOTE_HOST": "from-env"})
    def test_flags_beat_everything(self):
        resolved = config.resolve(host="from-flag", port=2222, path=self.path)

        self.assertEqual("from-flag", resolved.host)
        self.assertEqual(2222, resolved.port)

    @mock.patch.dict(os.environ, {"CAMREMOTE_PORT": "not-a-number"})
    def test_a_bad_port_in_the_environment_is_reported_not_ignored(self):
        with self.assertRaises(CamRemoteError):
            config.resolve(host=None, port=None, path=self.path)


if __name__ == "__main__":
    unittest.main()
