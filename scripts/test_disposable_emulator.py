"""Lifecycle checks; set CELASTRINA_TEST_REAL_EMULATOR=1 for Windows SDK checks."""

import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("disposable-emulator.py")
SPEC = importlib.util.spec_from_file_location("disposable", SCRIPT)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class DisposableEmulatorTest(unittest.TestCase):
    def test_capacity_is_shared_and_released(self):
        with tempfile.TemporaryDirectory() as folder:
            pool = Path(folder)
            with runner.reserve_slot(pool, 2, lambda _: True) as first:
                with runner.reserve_slot(pool, 2, lambda _: True) as second:
                    self.assertNotEqual(first, second)
                    with self.assertRaisesRegex(RuntimeError, "No disposable emulator slot"):
                        with runner.reserve_slot(pool, 2, lambda _: True):
                            self.fail("a third run exceeded the host cap")
            with runner.reserve_slot(pool, 1, lambda _: True) as reclaimed:
                self.assertEqual(first, reclaimed)

    def test_occupied_port_is_skipped(self):
        with tempfile.TemporaryDirectory() as folder:
            first, second = runner.PORTS[:2]
            with runner.reserve_slot(Path(folder), 2, lambda port: port != first) as port:
                self.assertEqual(second, port)

    def test_boot_never_hands_over_an_unrelated_device(self):
        process = unittest.mock.Mock()
        process.poll.return_value = None
        answers = [subprocess.CompletedProcess([], 0, stdout="other-avd\nOK\n")]
        with patch.object(runner.time, "monotonic", side_effect=[0, 0, 2]):
            with patch.object(runner.time, "sleep"):
                with patch.object(runner.subprocess, "run", side_effect=answers) as command:
                    with self.assertRaisesRegex(RuntimeError, "did not boot as expected"):
                        runner.wait_for_boot(process, "adb", "emulator-5560", "expected", 1)
        command.assert_called_once()
        self.assertEqual(command.call_args.args[0][-3:], ["emu", "avd", "name"])

    def test_boot_accepts_matching_ready_device(self):
        process = unittest.mock.Mock()
        process.poll.return_value = None
        answers = [
            subprocess.CompletedProcess([], 0, stdout="expected\nOK\n"),
            subprocess.CompletedProcess([], 0, stdout="1\n"),
        ]
        with patch.object(runner.subprocess, "run", side_effect=answers):
            runner.wait_for_boot(process, "adb", "emulator-5560", "expected", 1)

    def test_failed_avd_creation_never_starts_the_emulator(self):
        self.check_cleanup(creation_code=2)

    def test_failed_boot_keeps_logs_and_releases_slot(self):
        self.check_cleanup(boot_error=RuntimeError("boot failure"))

    def test_test_exit_is_preserved_and_releases_slot(self):
        self.check_cleanup(exit_code=7)

    def test_test_timeout_releases_slot(self):
        self.check_cleanup(test_error=subprocess.TimeoutExpired("tests", 1))

    def check_cleanup(self, creation_code=0, boot_error=None, exit_code=0, test_error=None):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            pool = root / "pool"
            abandoned = pool / str(runner.PORTS[0])
            abandoned.mkdir(parents=True)
            (abandoned / "old-avd").touch()
            logs = root / "logs"
            logs.mkdir()
            child = unittest.mock.MagicMock()
            job = unittest.mock.MagicMock()
            creation = unittest.mock.Mock()
            creation.wait.return_value = creation_code
            emulator = unittest.mock.Mock()
            job.__enter__.return_value.start.side_effect = [creation, emulator, child]
            if test_error:
                child.wait.side_effect = [test_error, 0]
            else:
                child.wait.return_value = exit_code
            with patch.object(runner, "WindowsJob", return_value=job):
                with patch.object(runner, "wait_for_boot", side_effect=boot_error):
                    with patch.object(runner, "ports_available", return_value=True):
                        with patch.object(runner.subprocess, "run"):
                            args = (pool, 1, root, runner.IMAGE, 1, 1, ["bash", "check"], "bundle", logs)
                            if creation_code:
                                with self.assertRaises(subprocess.CalledProcessError):
                                    runner.run_device(*args)
                            elif boot_error or test_error:
                                with self.assertRaises(type(boot_error or test_error)):
                                    runner.run_device(*args)
                            else:
                                self.assertEqual(exit_code, runner.run_device(*args))
            job.__exit__.assert_called_once()
            self.assertTrue((logs / "emulator.log").is_file())
            self.assertEqual(not (creation_code or boot_error), (logs / "tests.log").is_file())
            if creation_code:
                job.__enter__.return_value.start.assert_called_once()
            self.assertFalse((pool / str(runner.PORTS[0])).exists())
            with runner.reserve_slot(pool, 1, lambda _: True):
                pass

    @unittest.skipUnless(os.name == "nt" and os.environ.get("CELASTRINA_TEST_REAL_EMULATOR"),
                         "opt-in Windows SDK lifecycle check")
    def test_windows_job_releases_real_emulator_ports(self):
        self.check_real_emulator_cleanup(abrupt=False)

    @unittest.skipUnless(os.name == "nt" and os.environ.get("CELASTRINA_TEST_REAL_EMULATOR"),
                         "opt-in Windows SDK lifecycle check")
    def test_windows_job_releases_real_emulator_after_owner_is_killed(self):
        self.check_real_emulator_cleanup(abrupt=True)

    def check_real_emulator_cleanup(self, abrupt):
        sdk = Path(os.environ["ANDROID_HOME"])
        adb = sdk / "platform-tools/adb.exe"
        subprocess.run([adb, "start-server"], check=True, capture_output=True)
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
            root = Path(folder)
            env = dict(os.environ, ANDROID_AVD_HOME=str(root))
            avd = "celastrina-lifecycle-" + uuid.uuid4().hex[:12]
            subprocess.run([sdk / "cmdline-tools/latest/bin/avdmanager.bat", "create", "avd",
                            "-n", avd, "-k", runner.IMAGE], input=b"no\n", env=env,
                           check=True, capture_output=True, timeout=60)
            owner = None
            with runner.reserve_slot(root / "pool", 1) as port:
                try:
                    # The owner holds the job and waits for normal EOF or is
                    # terminated without executing Python/context cleanup.
                    code = (
                        "import runpy, sys; "
                        "Job = runpy.run_path(sys.argv[1])['WindowsJob']; "
                        "job = Job().__enter__(); "
                        "child = job.start(sys.argv[2:], stdout=sys.stdout, stderr=-2); "
                        "sys.stdin.read(); job.__exit__(); child.wait(timeout=5)"
                    )
                    with (root / "emulator.log").open("wb") as log:
                        owner = subprocess.Popen(
                            [sys.executable, "-c", code, str(SCRIPT),
                             str(sdk / "emulator/emulator.exe"), "-avd", avd,
                             "-port", str(port), "-no-window", "-no-audio",
                             "-no-snapshot", "-gpu", "swiftshader"],
                            stdin=subprocess.PIPE, env=env, stdout=log, stderr=subprocess.STDOUT)
                        deadline = time.monotonic() + 30
                        while runner.ports_available(port) and time.monotonic() < deadline:
                            self.assertIsNone(owner.poll(), (root / "emulator.log").read_text())
                            time.sleep(0.1)
                        self.assertFalse(runner.ports_available(port), "emulator never acquired ports")
                        if abrupt:
                            owner.kill()
                        owner.communicate(timeout=5)
                        if not abrupt:
                            self.assertEqual(0, owner.returncode, (root / "emulator.log").read_text())
                    deadline = time.monotonic() + 5
                    while not runner.ports_available(port) and time.monotonic() < deadline:
                        time.sleep(0.1)
                    self.assertTrue(runner.ports_available(port), "real emulator descendant outlived job")
                finally:
                    if owner is not None and owner.poll() is None:
                        owner.kill()
                        owner.communicate(timeout=5)
                    # Only this reserved serial; never touch the shared emulator.
                    if not runner.ports_available(port):
                        subprocess.run([adb, "-s", f"emulator-{port}", "emu", "kill"],
                                       capture_output=True, timeout=10)
                        time.sleep(5)
            with runner.reserve_slot(root / "pool", 1) as reclaimed:
                self.assertEqual(port, reclaimed)
            # Deleting the AVD/logs also verifies all descendant file handles closed.
            shutil.rmtree(root)

    @unittest.skipUnless(os.name == "nt", "Windows job objects")
    def test_windows_job_kills_child_when_owner_exits_abruptly(self):
        code = (
            "import runpy, os, sys; "
            "Job = runpy.run_path(sys.argv[1])['WindowsJob']; "
            "job = Job().__enter__(); "
            "child = job.start(['powershell.exe', '-NoProfile', '-Command', "
            "'[Console]::WriteLine($PID); Start-Sleep -Seconds 60'], stdout=-1); "
            "print(int(child.stdout.readline()), flush=True); "
            "sys.stdin.read(1); os._exit(3)"
        )
        owner = subprocess.Popen(
            [sys.executable, "-c", code, str(SCRIPT)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True
        )
        try:
            pid = int(owner.stdout.readline())
            # Hold a waitable handle before killing the owner to avoid PID reuse.
            with runner.WindowsJob() as job:
                job.kernel.WaitForSingleObject.argtypes = (runner.wintypes.HANDLE, runner.wintypes.DWORD)
                job.kernel.WaitForSingleObject.restype = runner.wintypes.DWORD
                child = job.kernel.OpenProcess(0x00100001, False, pid)  # SYNCHRONIZE | TERMINATE
                self.assertTrue(child)
                try:
                    owner.kill()
                    owner.wait(timeout=5)
                    self.assertEqual(0, job.kernel.WaitForSingleObject(child, 5000))
                finally:
                    # Bound cleanup even when testing a version that leaks.
                    job.kernel.TerminateProcess.argtypes = (runner.wintypes.HANDLE, runner.wintypes.UINT)
                    job.kernel.TerminateProcess(child, 1)
                    job.kernel.WaitForSingleObject(child, 5000)
                    job.kernel.CloseHandle(child)
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
