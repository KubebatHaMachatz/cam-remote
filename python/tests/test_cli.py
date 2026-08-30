"""Tests for the command-line interface.

The CLI is what a reviewer actually runs, so its contract is what a script depends on: the exit
codes, where output goes, and whether the device's remediation reaches the operator.
"""

import io
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from camremote import cli, config
from camremote.discovery.mdns import DiscoveredAgent
from camremote.errors import CommandFailed, TransportError
from camremote.models import CommandResponse


class FakeClient:
    """Stands in for a RemoteClient, recording calls and replaying scripted results."""

    def __init__(self, results=None, health=None, raises=None, failures=None):
        self.results = results or {}
        self._health = health or {
            "service": "cam-remote",
            "apiVersion": "v1",
            "device": {"model": "realme RMX3563"},
        }
        self.raises = raises
        self.failures = failures or {}
        self.calls = []
        self.downloads = []
        self.base_url = "http://10.0.0.4:8099"

    def invoke(self, command, params=None):
        self.calls.append((command, params))
        if self.raises:
            raise self.raises
        if command in self.failures:
            raise self.failures[command]
        data = self.results.get(command, {})
        return CommandResponse(id="1", command=command, status="OK", data=data, duration_ms=5)

    def health(self):
        if self.raises:
            raise self.raises
        return self._health

    def download(self, path, destination):
        self.downloads.append((path, destination))
        target = Path(destination) / "capture.jpg" if destination.suffix == "" else destination
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b"jpeg")
        return target


class CliTestCase(unittest.TestCase):
    """Shared setup: captured output streams and a config file in a temporary directory."""

    def setUp(self):
        self.out = io.StringIO()
        self.err = io.StringIO()
        self.directory = TemporaryDirectory()
        self.config_path = Path(self.directory.name) / "camremote.toml"
        config.save(config.AgentConfig(host="10.0.0.4", port=8099), self.config_path)
        self.addCleanup(self.directory.cleanup)

    def run_cli(self, *argv, client=None, agents=None):
        return cli.main(
            list(argv),
            connect=lambda agent: client or FakeClient(),
            discover=lambda timeout: agents if agents is not None else [],
            config_path=self.config_path,
            out=self.out,
            err=self.err,
        )


class GetPropTest(CliTestCase):
    """Reading properties, and how they are printed."""

    def test_prints_a_property(self):
        client = FakeClient({"device.getprop": {"properties": {"ro.product.model": "RMX3563"}}})

        code = self.run_cli("getprop", "ro.product.model", client=client)

        self.assertEqual(0, code)
        self.assertIn("ro.product.model", self.out.getvalue())
        self.assertIn("RMX3563", self.out.getvalue())

    def test_sends_several_keys_as_one_request(self):
        client = FakeClient({"device.getprop": {"properties": {}}})

        self.run_cli("getprop", "ro.a", "ro.b", client=client)

        self.assertEqual([("device.getprop", {"keys": ["ro.a", "ro.b"]})], client.calls)

    def test_marks_an_unset_property_rather_than_printing_none(self):
        client = FakeClient({"device.getprop": {"properties": {"ro.absent": None}}})

        self.run_cli("getprop", "ro.absent", client=client)

        self.assertIn("(not set)", self.out.getvalue())

    def test_json_output_is_machine_readable(self):
        client = FakeClient({"device.getprop": {"properties": {"ro.a": "1"}}})

        self.run_cli("--json", "getprop", "ro.a", client=client)

        self.assertEqual({"properties": {"ro.a": "1"}}, json.loads(self.out.getvalue()))


class FailureTest(CliTestCase):
    """Exit codes and error reporting -- the part a calling script actually depends on."""

    def test_a_failed_command_exits_one_and_shows_the_remediation(self):
        client = FakeClient(
            raises=CommandFailed(
                command="camera.open",
                code="PRECONDITION_FAILED",
                message="overlay permission missing",
                remediation="Open cam-remote on the device and grant Display over other apps",
            )
        )

        code = self.run_cli("open-camera", client=client)

        self.assertEqual(1, code)
        # The device knows exactly what is wrong; the point of carrying remediation across the wire
        # is that the operator gets to see it.
        self.assertIn("PRECONDITION_FAILED", self.err.getvalue())
        self.assertIn("Display over other apps", self.err.getvalue())

    def test_an_unreachable_agent_exits_three(self):
        client = FakeClient(raises=TransportError("Could not reach the agent at 10.0.0.4:8099"))

        code = self.run_cli("system-ping", client=client)

        # Distinct from 1 so a script can tell "the phone said no" from "the phone was not there".
        self.assertEqual(3, code)
        self.assertIn("Could not reach", self.err.getvalue())

    def test_an_unreachable_agent_points_at_what_is_actually_on_the_network(self):
        # The failure this exists to prevent: testing a second handset, the saved config still names
        # the first, and the error says only "connection refused" while the new device sits there
        # answering mDNS.
        client = FakeClient(raises=TransportError("Could not reach the agent at 10.0.0.4:8099"))
        agent = DiscoveredAgent(
            instance="cam-remote SM-S921B",
            host="10.0.0.8",
            port=8099,
            attributes={"model": "SM-S921B"},
        )

        code = self.run_cli("system-ping", client=client, agents=[agent])

        self.assertEqual(3, code)
        self.assertIn("10.0.0.8:8099", self.err.getvalue())
        self.assertIn("--host", self.err.getvalue())

    def test_does_not_invent_suggestions_when_the_network_is_empty(self):
        client = FakeClient(raises=TransportError("Could not reach the agent at 10.0.0.4:8099"))

        self.run_cli("system-ping", client=client, agents=[])

        self.assertNotIn("Found these", self.err.getvalue())

    def test_an_unknown_subcommand_exits_two(self):
        self.assertEqual(2, self.run_cli("teleport"))

    def test_no_subcommand_prints_help_and_exits_two(self):
        code = self.run_cli()

        self.assertEqual(2, code)
        self.assertIn("usage", self.err.getvalue().lower() + self.out.getvalue().lower())


class CaptureTest(CliTestCase):
    """Taking a photograph and bringing it back to this machine."""

    def test_takes_a_picture_and_downloads_it(self):
        client = FakeClient(
            {
                "camera.capture": {
                    "id": "abc",
                    "path": "/storage/x.jpg",
                    "sizeBytes": 2993636,
                    "widthPx": 2448,
                    "heightPx": 3264,
                    "downloadPath": "/v1/media/abc",
                }
            }
        )

        with TemporaryDirectory() as directory:
            code = self.run_cli("take-picture", "--out", directory, client=client)

            self.assertEqual(0, code)
            self.assertEqual("/v1/media/abc", client.downloads[0][0])
            self.assertIn("capture.jpg", self.out.getvalue())

    def test_passes_capture_options_through(self):
        client = FakeClient({"camera.capture": {"downloadPath": "/v1/media/abc"}})

        with TemporaryDirectory() as directory:
            self.run_cli(
                "take-picture",
                "--out",
                directory,
                "--filename",
                "door",
                "--quality",
                "70",
                "--path",
                "reports",
                client=client,
            )

        self.assertEqual(
            {
                "filename": "door",
                "jpegQuality": 70,
                "path": "reports",
            },
            client.calls[0][1],
        )

    def test_can_leave_the_photo_on_the_device(self):
        client = FakeClient({"camera.capture": {"downloadPath": "/v1/media/abc"}})

        self.run_cli("take-picture", "--no-download", client=client)

        self.assertEqual([], client.downloads)

    def test_open_camera_passes_the_lens_hint(self):
        client = FakeClient({"camera.open": {"component": "com.oplus.camera/.Camera"}})

        self.run_cli("open-camera", "--lens", "rear", client=client)

        self.assertEqual([("camera.open", {"lens": "rear"})], client.calls)


class DiscoveryTest(CliTestCase):
    """Finding an agent when none has been configured."""

    def test_lists_agents_it_finds(self):
        agent = DiscoveredAgent(
            instance="cam-remote realme RMX3563",
            host="10.0.0.4",
            port=8099,
            attributes={"model": "realme RMX3563"},
        )

        code = self.run_cli("discover", agents=[agent])

        self.assertEqual(0, code)
        self.assertIn("10.0.0.4:8099", self.out.getvalue())

    def test_says_so_when_the_network_yields_nothing(self):
        code = self.run_cli("discover", agents=[])

        self.assertEqual(3, code)
        # Multicast is blocked on plenty of networks, so the failure has to point at the way out.
        self.assertIn("--host", self.err.getvalue())

    def test_falls_back_to_discovery_when_no_host_is_configured(self):
        empty_config = Path(self.directory.name) / "empty.toml"
        agent = DiscoveredAgent(instance="a", host="10.0.0.7", port=8099, attributes={})
        seen = {}

        def connect(resolved):
            seen["host"] = resolved.host
            return FakeClient({"system.ping": {"pong": True}})

        code = cli.main(
            ["system-ping"],
            connect=connect,
            discover=lambda timeout: [agent],
            config_path=empty_config,
            out=self.out,
            err=self.err,
        )

        self.assertEqual(0, code)
        self.assertEqual("10.0.0.7", seen["host"])


class PairingTest(CliTestCase):
    """Finding the agent and remembering its address -- no code, no handshake."""

    def test_confirms_reachability_and_saves_the_address(self):
        code = self.run_cli("pair", client=FakeClient())

        self.assertEqual(0, code)
        saved = config.load(self.config_path)
        self.assertEqual("10.0.0.4", saved.host)
        self.assertEqual(8099, saved.port)

    def test_a_client_that_cannot_be_reached_fails_pairing(self):
        client = FakeClient(raises=TransportError("Could not reach the agent at 10.0.0.4:8099"))

        code = self.run_cli("pair", client=client)

        self.assertEqual(3, code)

    def test_records_the_agent_address_discovery_found(self):
        agent = DiscoveredAgent(instance="a", host="10.0.0.7", port=8099, attributes={})
        empty_config = Path(self.directory.name) / "empty.toml"

        cli.main(
            ["pair"],
            connect=lambda resolved: FakeClient(),
            discover=lambda timeout: [agent],
            config_path=empty_config,
            out=self.out,
            err=self.err,
        )

        saved = config.load(empty_config)
        self.assertEqual("10.0.0.7", saved.host)
        self.assertEqual(8099, saved.port)

    def test_names_the_device_found(self):
        client = FakeClient(health={"service": "cam-remote", "device": {"model": "SM-S921B"}})

        self.run_cli("pair", client=client)

        self.assertIn("SM-S921B", self.out.getvalue())


class CatalogTest(CliTestCase):
    """Reporting what the device supports and whether it is ready."""

    def test_lists_the_commands_the_device_supports(self):
        client = FakeClient(
            {
                "system.commands": {
                    "commands": [
                        {
                            "name": "device.getprop",
                            "description": "Read properties.",
                            "parameters": [
                                {
                                    "name": "key",
                                    "type": "STRING",
                                    "required": False,
                                    "description": "A property name.",
                                }
                            ],
                        }
                    ]
                }
            }
        )

        code = self.run_cli("commands", client=client)

        self.assertEqual(0, code)
        self.assertIn("device.getprop", self.out.getvalue())
        self.assertIn("key", self.out.getvalue())

    def test_status_reports_missing_grants(self):
        client = FakeClient(
            {
                "system.status": {
                    "device": {"model": "realme RMX3563", "androidRelease": "14", "apiLevel": 34},
                    "setupComplete": False,
                    "missing": ["camera", "canDrawOverlays"],
                    "hasRearCamera": True,
                }
            }
        )

        self.run_cli("status", client=client)

        self.assertIn("canDrawOverlays", self.out.getvalue())


class DeviceReportTest(CliTestCase):
    """A single command that gathers everything a new handset reveals."""

    def report_client(self, **overrides):
        results = {
            "system.status": {
                "device": {"model": "SM-S921B", "androidRelease": "14", "apiLevel": 34},
                "setupComplete": True,
                "missing": [],
                "hasRearCamera": True,
                "permissions": {"camera": True},
            },
            "camera.apps": {
                "wouldUseStrategy": "still_image_camera",
                "wouldUseComponent": "com.sec.android.app.camera/.Camera",
                "strategies": [
                    {
                        "strategy": "still_image_camera",
                        "action": "android.media.action.STILL_IMAGE_CAMERA",
                        "chosen": "com.sec.android.app.camera/.Camera",
                        "handlers": [
                            {
                                "package": "com.sec.android.app.camera",
                                "activity": ".Camera",
                                "preinstalled": True,
                                "defaultHandler": False,
                            }
                        ],
                    }
                ],
            },
            "device.getprop": {"properties": {"ro.product.manufacturer": "samsung"}},
            "system.commands": {"commands": [{"name": "camera.open", "description": "x"}]},
        }
        results.update(overrides.pop("results", {}))
        return FakeClient(results=results, **overrides)

    def test_summarises_the_device(self):
        code = self.run_cli("device-report", client=self.report_client())

        self.assertEqual(0, code)
        output = self.out.getvalue()
        self.assertIn("SM-S921B", output)
        self.assertIn("com.sec.android.app.camera", output)
        self.assertIn("still_image_camera", output)

    def test_gathers_everything_in_one_round_of_commands(self):
        client = self.report_client()

        self.run_cli("device-report", client=client)

        self.assertEqual(
            {"system.status", "camera.apps", "device.getprop", "system.commands"},
            {command for command, _ in client.calls},
        )

    def test_json_output_is_one_blob_to_paste_into_a_matrix(self):
        self.run_cli("--json", "device-report", client=self.report_client())

        report = json.loads(self.out.getvalue())
        self.assertIn("status", report)
        self.assertIn("cameraApps", report)
        self.assertIn("properties", report)

    def test_writes_the_report_to_a_file_when_asked(self):
        with TemporaryDirectory() as directory:
            target = Path(directory) / "s24.json"

            self.run_cli("device-report", "--out", str(target), client=self.report_client())

            self.assertIn("SM-S921B", json.loads(target.read_text())["status"]["device"]["model"])

    def test_keeps_going_when_part_of_the_device_is_broken(self):
        # The whole point of a diagnostic is to run on a device that is not working. If camera.apps
        # fails because the camera permission is missing, the report must still tell you that.
        client = self.report_client(
            failures={
                "camera.apps": CommandFailed(
                    command="camera.apps",
                    code="PERMISSION_DENIED",
                    message="camera permission missing",
                )
            }
        )

        code = self.run_cli("--json", "device-report", client=client)

        self.assertEqual(0, code)
        report = json.loads(self.out.getvalue())
        self.assertEqual("PERMISSION_DENIED", report["cameraApps"]["error"]["code"])
        self.assertIn("SM-S921B", report["status"]["device"]["model"])

    def test_reports_the_error_visibly_in_the_human_summary_too(self):
        client = self.report_client(
            failures={
                "camera.apps": CommandFailed(
                    command="camera.apps", code="PERMISSION_DENIED", message="nope"
                )
            }
        )

        self.run_cli("device-report", client=client)

        self.assertIn("PERMISSION_DENIED", self.out.getvalue())


class CameraAppsTest(CliTestCase):
    """Listing the camera apps a device offers."""

    def test_lists_every_camera_app_the_device_offers(self):
        client = FakeClient(
            {
                "camera.apps": {
                    "wouldUseStrategy": "app_camera_category",
                    "wouldUseComponent": "com.android.camera2/.CameraActivity",
                    "strategies": [
                        {
                            "strategy": "app_camera_category",
                            "action": "android.intent.action.MAIN",
                            "chosen": "com.android.camera2/.CameraActivity",
                            "handlers": [
                                {
                                    "package": "com.android.camera2",
                                    "activity": ".CameraActivity",
                                    "preinstalled": True,
                                    "defaultHandler": False,
                                }
                            ],
                        }
                    ],
                }
            }
        )

        code = self.run_cli("camera-apps", client=client)

        self.assertEqual(0, code)
        self.assertIn("com.android.camera2", self.out.getvalue())
        self.assertIn("app_camera_category", self.out.getvalue())


if __name__ == "__main__":
    unittest.main()
