#!/usr/bin/env python3
"""Run the real Android pipeline against an independent, network-only ID8 peer.

From the repository root (use an isolated Android emulator, API 34 recommended):
  python3 -m pip install -r tools/e2e/requirements.txt  # Python 3.11+
  python3 tools/e2e/run_id8.py --serial emulator-5580 --artifacts /tmp/id8-network-run

Builds/installs the app and transport-only test APK. --no-build uses existing APKs.
No physical Bluetooth/HU software is involved. The peer judges received network media,
not Android logs, driver acknowledgements, private fields, or app readiness counters.
The emulator must have Wi-Fi enabled. The ADB server must run on the fixture host
(or its reverse mappings must be externally forwarded there). 10.0.2.2 is the
standard Android emulator route to this host; override --receiver-host otherwise.
The network-drop scenario toggles emulator Wi-Fi and resets the peer TCP transport.
ADB-reversed CarLife input remains loopback; this does not validate physical radio association.
Every outcome, including setup failures, leaves outcome.json and rerun.sh.
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import importlib.metadata
import json
from pathlib import Path
import platform
import shlex
import shutil
import socket
import sys
import tempfile
import time
import traceback


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.shaonialife.carptocar"
INSTRUMENTATION = f"{PACKAGE}.test/{PACKAGE}.Id8NetworkInstrumentation"
SCENARIOS = (
    "baseline", "late-invite", "duplicate-invite", "screen-yield-return",
    "radio-yield-return", "peer-reset", "teardown-reconnect", "navigation",
    "network-drop-reconnect", "source-reconnect", "stale-audio-take",
    "car-take-during-claim", "concurrent-audio", "track-transition", "iap-window",
    "source-start-order", "audio-record-delay", "explicit-source-intent", "initial-source-cancel",
    "negative-bad-auth", "negative-iap-auth", "negative-no-audio", "negative-no-video",
)


def save_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def digest(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


class Commands:
    def __init__(self, artifact_dir: Path, args: argparse.Namespace):
        self.artifact_dir = artifact_dir
        self.args = args
        self.sequence = 0

    def adb(self, *arguments: str) -> list[str]:
        return [self.args.adb, "-H", self.args.adb_host, "-P", str(self.args.adb_port),
                "-s", self.args.serial, *arguments]

    async def start(self, argv: list[str], label: str) -> tuple[asyncio.subprocess.Process, Path]:
        self.sequence += 1
        output = self.artifact_dir / f"command-{self.sequence:03d}-{label}.log"
        record = {"argv": argv, "cwd": str(ROOT), "output": output.name,
                  "started_unix": time.time()}
        with (self.artifact_dir / "commands.jsonl").open("a") as handle:
            handle.write(json.dumps(record) + "\n")
        with output.open("wb") as handle:
            process = await asyncio.create_subprocess_exec(
                *argv, cwd=ROOT, stdout=handle, stderr=asyncio.subprocess.STDOUT,
            )
        return process, output

    async def run(self, argv: list[str], label: str, timeout: float = 120) -> str:
        process, output = await self.start(argv, label)
        try:
            await asyncio.wait_for(process.wait(), timeout)
        except BaseException:
            if process.returncode is None:
                process.kill()
                await process.wait()
            raise
        with (self.artifact_dir / "commands.jsonl").open("a") as handle:
            handle.write(json.dumps({"argv": argv, "output": output.name,
                                     "finished_unix": time.time(),
                                     "returncode": process.returncode}) + "\n")
        text = output.read_text(errors="replace")
        if process.returncode:
            raise RuntimeError(f"{label} exited {process.returncode}; see {output}\n{text}")
        return text.strip()


async def stop_process(process: asyncio.subprocess.Process | None) -> None:
    if process is None or process.returncode is not None:
        return
    process.terminate()
    try:
        await asyncio.wait_for(process.wait(), 10)
    except asyncio.TimeoutError:
        process.kill()
        await process.wait()


class AndroidDriver:
    """Normal lifecycle actions plus discovery of a network listener's address."""

    def __init__(self, commands: Commands, artifacts: Path):
        self.commands = commands
        self.artifacts = artifacts
        self.process = None
        self.logcat = None
        self.reader = None
        self.writer = None
        self.forwards: set[int] = set()
        self.udp_redirections: dict[int, int] = {}
        self.ctrl_endpoint = None
        self.lock = asyncio.Lock()
        self.instrumentation_output = None
        self.wifi_needs_restore = False

    async def forward(self, device_port: int) -> int:
        port = int(await self.commands.run(
            self.commands.adb("forward", "tcp:0", f"tcp:{device_port}"), "forward",
        ))
        self.forwards.add(port)
        return port

    async def start(self, receiver_host: str, receiver_port: int, device_port: int) -> None:
        self.logcat, _ = await self.commands.start(
            self.commands.adb("logcat", "-v", "threadtime", "-T", "1"), "logcat",
        )
        local_port = await self.forward(device_port)
        self.process, self.instrumentation_output = await self.commands.start(self.commands.adb(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "receiverHost", receiver_host,
            "-e", "receiverPort", str(receiver_port),
            "-e", "driverPort", str(device_port), INSTRUMENTATION,
        ), "instrumentation")
        deadline = asyncio.get_running_loop().time() + 45
        last_error = None
        while asyncio.get_running_loop().time() < deadline:
            if self.process.returncode is not None:
                raise RuntimeError("Instrumentation exited before its command transport opened")
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(self.commands.args.adb_forward_host, local_port), 2,
                )
                # ADB forward can accept before the remote listener exists. Only transport
                # discovery acknowledges that the test driver, not the app pipeline, is present.
                await self.command("transport")
                return
            except (OSError, EOFError, asyncio.TimeoutError, ConnectionError) as error:
                last_error = error
                if self.writer:
                    self.writer.close()
                    try:
                        await self.writer.wait_closed()
                    except OSError:
                        pass
                    self.writer = None
                await asyncio.sleep(0.25)
        raise TimeoutError(f"Driver command transport did not open: {last_error}")

    async def command(self, command: str) -> dict:
        async with self.lock:
            self.writer.write((json.dumps({"command": command}) + "\n").encode())
            await self.writer.drain()
            line = await asyncio.wait_for(self.reader.readline(), 30)
            if not line:
                raise EOFError("Android driver closed its command transport")
            response = json.loads(line)
            with (self.artifacts / "driver-transcript.jsonl").open("a") as handle:
                handle.write(json.dumps({"time": time.time(), "request": command,
                                         "response": response}) + "\n")
            if response.get("command") != command or response.get("accepted") is not True:
                raise RuntimeError(f"Android driver rejected {command}: {response}")
            return response

    async def __call__(self, action: str, port: int | None = None):
        if action == "udp-endpoint":
            if not isinstance(port, int) or not 1 <= port <= 65535:
                raise ValueError("udp-endpoint requires a wire-advertised guest UDP port")
            if port not in self.udp_redirections:
                # Emulator console has no ephemeral-port allocation command. Reserve a
                # host port briefly; console errors (including allocation races) fail the run.
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
                    reservation.bind(("0.0.0.0", 0))
                    host_port = reservation.getsockname()[1]
                reply = await self.commands.run(self.commands.adb(
                    "emu", "redir", "add", f"udp:{host_port}:{port}"), "udp-redirection")
                if any(line.startswith("KO:") for line in reply.splitlines()):
                    raise RuntimeError(f"Emulator UDP redirection failed: {reply}")
                self.udp_redirections[port] = host_port
            return self.commands.args.adb_forward_host, self.udp_redirections[port]
        if action in ("network-down", "network-up"):
            enable = action == "network-up"
            if not enable:
                self.wifi_needs_restore = True
            await self.commands.run(self.commands.adb(
                "shell", "svc", "wifi", "enable" if enable else "disable"), action)
            deadline = asyncio.get_running_loop().time() + 30
            while asyncio.get_running_loop().time() < deadline:
                if enable:
                    state = await self.commands.run(self.commands.adb(
                        "shell", "ip", "-4", "-o", "addr", "show", "up", "dev",
                        self.commands.args.wifi_interface), "wifi-address")
                    observed = " inet " in state
                else:
                    state = await self.commands.run(self.commands.adb(
                        "shell", "cmd", "wifi", "status"), "wifi-status")
                    observed = "Wifi is disabled" in state
                if observed:
                    if enable:
                        self.wifi_needs_restore = False
                    return
                await asyncio.sleep(0.25)
            raise TimeoutError(f"Emulator OS did not complete {action}; see command transcripts")
        if action == "ctrl-endpoint":
            if self.ctrl_endpoint is None:
                deadline = asyncio.get_running_loop().time() + 15
                while asyncio.get_running_loop().time() < deadline:
                    port = (await self.command("transport"))["ctrlPort"]
                    if port:
                        forwarded = await self.forward(port)
                        self.ctrl_endpoint = (self.commands.args.adb_forward_host, forwarded)
                        break
                    await asyncio.sleep(0.1)
                else:
                    raise TimeoutError("Production control-invite listener did not open")
            return self.ctrl_endpoint
        if action not in ("connect", "disconnect"):
            raise ValueError(f"Unsupported lifecycle action: {action}")
        if action == "disconnect":
            self.ctrl_endpoint = None
        await self.command(action)

    async def close(self) -> None:
        errors = []
        try:
            if self.writer:
                try:
                    await self.command("stop")
                finally:
                    self.writer.close()
                    await self.writer.wait_closed()
                if self.process:
                    await asyncio.wait_for(self.process.wait(), 30)
                    text = self.instrumentation_output.read_text(errors="replace")
                    if (self.process.returncode != 0 or "INSTRUMENTATION_CODE: -1" not in text
                            or "driverError=" in text or "cleanupError=" in text):
                        raise RuntimeError("Instrumentation driver failed; see " + str(self.instrumentation_output))
        except Exception as error:
            errors.append(str(error))
        finally:
            if self.wifi_needs_restore:
                try:
                    await self("network-up")
                except Exception as error:
                    errors.append(f"Restoring emulator Wi-Fi: {error}")
            await stop_process(self.process)
            await stop_process(self.logcat)
            for host_port in self.udp_redirections.values():
                try:
                    reply = await self.commands.run(self.commands.adb(
                        "emu", "redir", "del", f"udp:{host_port}"), "remove-udp-redirection")
                    if any(line.startswith("KO:") for line in reply.splitlines()):
                        raise RuntimeError(f"Emulator UDP redirection cleanup failed: {reply}")
                except Exception as error:
                    errors.append(str(error))
            for port in self.forwards:
                try:
                    await self.commands.run(self.commands.adb(
                        "forward", "--remove", f"tcp:{port}"), "remove-forward")
                except Exception as error:
                    errors.append(str(error))
        if errors:
            raise RuntimeError("Driver cleanup failed: " + "; ".join(errors))


async def run_scenario(name: str, args: argparse.Namespace, artifacts: Path,
                       commands: Commands) -> dict:
    # Imported only after the input manifest exists so missing dependencies are recorded.
    from id8 import Harness

    scenario_dir = artifacts / name
    scenario_dir.mkdir()
    harness = Harness(scenario_dir, host=args.peer_bind, control_port=args.control_port,
                      source_host=args.source_bind, scenario=name)
    driver = AndroidDriver(commands, scenario_dir)
    reverse_ports = []
    outcome = {"scenario": name, "passed": False}
    try:
        await harness.start()
        for port in harness.source_ports:
            await commands.run(commands.adb("reverse", f"tcp:{port}", f"tcp:{port}"),
                               "reverse")
            reverse_ports.append(port)
        await driver.start(args.receiver_host, harness.control_port, args.driver_port)
        # The independent peer arms faults before calling the normal connect action.
        observed = await asyncio.wait_for(
            harness.run_scenario(name, timeout=args.timeout, driver=driver), args.timeout + 45,
        )
        outcome["network_outcome"] = observed
        if name.startswith("negative-"):
            outcome["passed"] = (observed.get("passed") is False
                                 and observed.get("oracle_rejected") is True)
            outcome["expectation"] = "Independent network oracle rejects the deliberate wire fault"
        else:
            outcome["passed"] = observed.get("passed") is True
            outcome["expectation"] = "Independent network oracle accepts decoded network effects"
    except Exception as error:
        outcome["error"] = str(error)
        outcome["traceback"] = traceback.format_exc()
    finally:
        cleanup_errors = []
        try:
            await driver.close()
        except Exception as error:
            cleanup_errors.append(str(error))
        try:
            await harness.close()
        except Exception as error:
            cleanup_errors.append(str(error))
        for port in reverse_ports:
            try:
                await commands.run(commands.adb("reverse", "--remove", f"tcp:{port}"),
                                   "remove-reverse")
            except Exception as error:
                cleanup_errors.append(str(error))
        if cleanup_errors:
            outcome["cleanup_errors"] = cleanup_errors
            outcome["passed"] = False
        save_json(scenario_dir / "runner-outcome.json", outcome)
    return outcome


async def execute(args: argparse.Namespace, artifacts: Path) -> list[dict]:
    commands = Commands(artifacts, args)
    await commands.run(commands.adb("wait-for-device"), "wait-device", timeout=60)
    emulator = await commands.run(commands.adb("shell", "getprop", "ro.kernel.qemu"), "device-kind")
    if emulator != "1":
        raise RuntimeError("Refusing a non-emulator device: this runner must never touch the connected tablet")
    sdk = await commands.run(commands.adb("shell", "getprop", "ro.build.version.sdk"), "device-sdk")
    if int(sdk) < 31:
        raise RuntimeError(f"The app requires API 31 or newer; connected emulator SDK is {sdk}")
    await commands.run(commands.adb("shell", "getprop"), "device-properties")
    mappings = await commands.run(commands.adb("reverse", "--list"), "existing-reverse")
    fixture_ports = (7240, 8240, 9240, 9241, 9242, 9340, 9440)
    if any(f"tcp:{port}" in mappings.split() for port in fixture_ports):
        raise RuntimeError("Fixture reverse ports are already in use; use a fresh isolated emulator")
    if not args.no_build:
        await commands.run([str(ROOT / "gradlew"), ":app:assembleDebug",
                            ":app:assembleDebugAndroidTest"], "build", timeout=args.build_timeout)
    apk_inputs = []
    for original in (args.apk, args.test_apk):
        path = Path(original).resolve()
        if not path.is_file():
            raise FileNotFoundError(f"APK input not found: {path}")
        destination = artifacts / "inputs" / path.name
        shutil.copy2(path, destination)
        apk_inputs.append({"path": str(path), "artifact": str(destination.relative_to(artifacts)),
                           "sha256": digest(path)})
        await commands.run(commands.adb("install", "-r", "-t", "-g", str(path)), "install")
    save_json(artifacts / "apk-inputs.json", apk_inputs)
    outcomes = []
    for name in args.scenarios:
        # Instrumentation terminates the target process; a new invocation exercises a clean
        # application lifecycle while the peer tests reconnect within its own scenario.
        result = await run_scenario(name, args, artifacts, commands)
        outcomes.append(result)
        print(f"{name}: {'PASS' if result['passed'] else 'FAIL'}", flush=True)
    return outcomes


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="Explicit isolated emulator serial; physical devices are refused")
    parser.add_argument("--artifacts", type=Path, help="New output directory (default: temporary directory)")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--adb-host", default="127.0.0.1")
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--adb-forward-host", help="Host on which ADB forward listeners open; defaults to --adb-host")
    parser.add_argument("--receiver-host", default="10.0.2.2", help="Fixture host address reachable from emulator")
    parser.add_argument("--peer-bind", default="0.0.0.0")
    parser.add_argument("--source-bind", default="127.0.0.1")
    parser.add_argument("--control-port", type=int, default=0)
    parser.add_argument("--driver-port", type=int, default=18765)
    parser.add_argument("--wifi-interface", default="wlan0",
                        help="Emulator Wi-Fi interface used to observe OS address restoration")
    parser.add_argument("--timeout", type=float, default=120, help="Per-scenario network deadline in seconds")
    parser.add_argument("--build-timeout", type=float, default=900)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--scenarios", nargs="+", choices=SCENARIOS, default=list(SCENARIOS))
    args = parser.parse_args()
    if len(set(args.scenarios)) != len(args.scenarios):
        parser.error("Each scenario may be selected only once")
    args.adb_forward_host = args.adb_forward_host or args.adb_host
    return args


def main() -> int:
    args = arguments()
    if args.artifacts:
        artifacts = args.artifacts.resolve()
        artifacts.mkdir(parents=True, exist_ok=False)
    else:
        artifacts = Path(tempfile.mkdtemp(prefix="carptocarl-id8-network-"))
    (artifacts / "inputs").mkdir()
    print(f"Artifacts: {artifacts}", flush=True)
    rerun_args = [arg for arg in sys.argv[1:]]
    # A fresh directory is essential: never overwrite evidence from the failed run.
    if "--artifacts" in rerun_args:
        index = rerun_args.index("--artifacts")
        del rerun_args[index:index + 2]
    rerun_args = [arg for arg in rerun_args if not arg.startswith("--artifacts=")]
    (artifacts / "rerun.sh").write_text(
        "#!/bin/sh\nset -eu\ncd " + shlex.quote(str(ROOT)) + "\nexec "
        + shlex.join([sys.executable, str(Path(__file__).resolve()), *rerun_args]) + "\n",
    )
    inputs = []
    source_inputs = list((ROOT / "tools/e2e").rglob("*"))
    source_inputs.extend((ROOT / "app/src/androidTest").rglob("*"))
    source_inputs.append(ROOT / "app/build.gradle.kts")
    for path in sorted(source_inputs):
        if (path.is_file() and "__pycache__" not in path.parts and ".venv" not in path.parts
                and not path.is_relative_to(artifacts)):
            relative = path.relative_to(ROOT)
            target = artifacts / "inputs" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
            inputs.append({"path": str(relative), "sha256": digest(path)})
    versions = {}
    for name in ("cryptography", "av", "numpy"):
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = None
    save_json(artifacts / "input-manifest.json", {
        "argv": sys.argv, "cwd": str(ROOT), "python": sys.version,
        "platform": platform.platform(), "dependencies": versions,
        "configuration": {key: str(value) if isinstance(value, Path) else value
                          for key, value in vars(args).items()},
        "fixtures_and_runner": inputs,
        "oracle": "Independent peer network effects only; instrumentation acknowledgements are not success",
    })
    outcome = {"passed": False, "scenarios": []}
    try:
        outcome["scenarios"] = asyncio.run(execute(args, artifacts))
        outcome["passed"] = bool(outcome["scenarios"]) and all(
            result["passed"] for result in outcome["scenarios"]
        )
    except (Exception, KeyboardInterrupt) as error:
        outcome["error"] = str(error)
        outcome["traceback"] = traceback.format_exc()
    finally:
        save_json(artifacts / "outcome.json", outcome)
    print(f"{'PASS' if outcome['passed'] else 'FAIL'}: {artifacts / 'outcome.json'}", flush=True)
    return 0 if outcome["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
