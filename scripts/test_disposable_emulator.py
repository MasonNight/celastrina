"""Lifecycle checks that do not require an SDK image, GPU, or emulator."""

import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
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

    def test_failed_boot_keeps_logs_and_releases_slot(self):
        self.check_cleanup(boot_error=RuntimeError("boot failure"))

    def test_test_exit_is_preserved_and_releases_slot(self):
        self.check_cleanup(exit_code=7)

    def test_test_timeout_releases_slot(self):
        self.check_cleanup(test_error=subprocess.TimeoutExpired("tests", 1))

    def check_cleanup(self, boot_error=None, exit_code=0, test_error=None):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            pool = root / "pool"
            logs = root / "logs"
            logs.mkdir()
            child = unittest.mock.MagicMock()
            job = unittest.mock.MagicMock()
            job.__enter__.return_value.start.return_value = child
            if test_error:
                child.wait.side_effect = [test_error, 0, 0]
            else:
                child.wait.return_value = exit_code
            with patch.object(runner, "WindowsJob", return_value=job):
                with patch.object(runner, "wait_for_boot", side_effect=boot_error):
                    with patch.object(runner, "ports_available", return_value=True):
                        with patch.object(runner.subprocess, "run"):
                            args = (pool, 1, root, runner.IMAGE, 1, 1, ["bash", "check"], "bundle", logs)
                            if boot_error or test_error:
                                with self.assertRaises(type(boot_error or test_error)):
                                    runner.run_device(*args)
                            else:
                                self.assertEqual(exit_code, runner.run_device(*args))
            job.__exit__.assert_called_once()
            self.assertTrue((logs / "emulator.log").is_file())
            self.assertEqual(not boot_error, (logs / "tests.log").is_file())
            self.assertFalse((pool / str(runner.PORTS[0])).exists())
            with runner.reserve_slot(pool, 1, lambda _: True):
                pass

    @unittest.skipUnless(os.name == "nt", "Windows job objects")
    def test_windows_job_kills_child_when_owner_exits_abruptly(self):
        code = (
            "import runpy, os, sys; "
            "Job = runpy.run_path(sys.argv[1])['WindowsJob']; "
            "job = Job().__enter__(); "
            "child = job.start([sys.executable, '-c', "
            "'import os, time; print(os.getpid(), flush=True); time.sleep(60)'], stdout=-1); "
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
                child = job.kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE
                self.assertTrue(child)
                try:
                    owner.kill()
                    owner.wait(timeout=5)
                    self.assertEqual(0, job.kernel.WaitForSingleObject(child, 5000))
                finally:
                    job.kernel.CloseHandle(child)
        finally:
            if owner.poll() is None:
                owner.kill()
            owner.communicate(timeout=5)


if __name__ == "__main__":
    unittest.main()
