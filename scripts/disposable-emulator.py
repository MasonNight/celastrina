"""Run both Celastrina instrumented suites in one disposable Windows AVD."""

import argparse
import contextlib
import ctypes
import errno
from ctypes import wintypes
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid


PORTS = range(5560, 5584, 2)
IMAGE = "system-images;android-28;android-tv;x86"


@contextlib.contextmanager
def slot_lock(path):
    # Keep the lock file in place so all contenders lock the same file. The OS
    # releases the byte lock when its owner exits, including after termination.
    with path.open("a+b") as handle:
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            # A denied lock is contention. Propagate unrelated filesystem errors.
            if error.errno not in (errno.EACCES, errno.EAGAIN, errno.EDEADLK):
                raise
            yield False
        else:
            yield True


def ports_available(port):
    try:
        with socket.socket() as console, socket.socket() as transport:
            for sock, number in ((console, port), (transport, port + 1)):
                if os.name == "nt":
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
                sock.bind(("127.0.0.1", number))
    except OSError:
        return False
    return True


@contextlib.contextmanager
def reserve_slot(pool, slots, available=None):
    available = available or ports_available
    pool.mkdir(parents=True, exist_ok=True)
    for port in PORTS[:slots]:
        with slot_lock(pool / f"{port}.lock") as reserved:
            if reserved and available(port):
                yield port
                return
    raise RuntimeError("No disposable emulator slot is free. Try again when a run finishes.")


class JobLimits(ctypes.Structure):
    # JOBOBJECT_EXTENDED_LIMIT_INFORMATION, with the IO counters and memory limits
    # left at zero. Use pointer-sized quantities for the Windows size_t fields.
    class Basic(ctypes.Structure):
        _fields_ = [
            ("per_process_time", ctypes.c_int64), ("per_job_time", ctypes.c_int64),
            ("flags", wintypes.DWORD), ("min_working_set", ctypes.c_size_t),
            ("max_working_set", ctypes.c_size_t), ("active_limit", wintypes.DWORD),
            ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
            ("scheduling_class", wintypes.DWORD),
        ]

    _fields_ = [
        ("basic", Basic), ("io_counters", ctypes.c_uint64 * 6),
        ("memory_limits", ctypes.c_size_t * 4),
    ]


class WindowsJob:
    """Own the emulator and its subprocesses, even if this runner is killed."""

    def __enter__(self):
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        api = self.kernel
        signatures = (
            ("CreateJobObjectW", (wintypes.LPVOID, wintypes.LPCWSTR), wintypes.HANDLE),
            ("SetInformationJobObject", (wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID, wintypes.DWORD), wintypes.BOOL),
            ("OpenProcess", (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD), wintypes.HANDLE),
            ("AssignProcessToJobObject", (wintypes.HANDLE, wintypes.HANDLE), wintypes.BOOL),
            ("CloseHandle", (wintypes.HANDLE,), wintypes.BOOL),
        )
        for name, arguments, result in signatures:
            function = getattr(api, name)
            function.argtypes = arguments
            function.restype = result
        self.handle = api.CreateJobObjectW(None, None)  # Non-inheritable.
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = JobLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error())
            api.CloseHandle(self.handle)
            raise error
        return self

    def start(self, command, input_data=b"", **options):
        # The child waits for one byte before starting the command. This lets us
        # attach it before it can create a QEMU or test-runner subprocess.
        gate = (
            "import subprocess, sys; "
            "sys.exit(subprocess.run(sys.argv[2:], input=bytes.fromhex(sys.argv[1])).returncode "
            "if sys.stdin.buffer.read(1) == b'1' else 1)"
        )
        process = subprocess.Popen(
            [sys.executable, "-c", gate, input_data.hex(), *map(str, command)], stdin=subprocess.PIPE, **options
        )
        try:
            child = self.kernel.OpenProcess(0x0101, False, process.pid)  # SET_QUOTA | TERMINATE
            if not child:
                raise ctypes.WinError(ctypes.get_last_error())
            try:
                if not self.kernel.AssignProcessToJobObject(self.handle, child):
                    raise ctypes.WinError(ctypes.get_last_error())
            finally:
                self.kernel.CloseHandle(child)
            with process.stdin:
                process.stdin.write(b"1")
        except BaseException:
            process.stdin.close()
            process.wait()
            raise
        return process

    def __exit__(self, *_):
        self.kernel.CloseHandle(self.handle)


def wait_for_boot(process, adb, serial, avd, seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if process.poll() is not None:
            raise RuntimeError(f"Emulator exited before booting (exit {process.returncode}).")
        try:
            # A manual emulator could race for a port after our bind check.
            # Confirm the AVD identity before handing this serial to a test.
            identity = subprocess.run(
                [adb, "-s", serial, "emu", "avd", "name"],
                capture_output=True, text=True, timeout=3,
            )
            if identity.returncode == 0 and identity.stdout.splitlines()[:1] == [avd]:
                boot = subprocess.run(
                    [adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                    capture_output=True, text=True, timeout=3,
                )
                if boot.returncode == 0 and boot.stdout.strip() == "1":
                    return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1)
    raise RuntimeError(f"{serial} did not boot as {avd} within {seconds} seconds.")


def run_device(pool, slots, sdk, image, boot_timeout, test_timeout, check, bundle, logs):
    adb = sdk / "platform-tools" / "adb.exe"
    emulator = sdk / "emulator" / "emulator.exe"
    avdmanager = sdk / "cmdline-tools" / "latest" / "bin" / "avdmanager.bat"

    with reserve_slot(pool, slots) as port:
        serial = f"emulator-{port}"
        avd = f"celastrina-run-{uuid.uuid4().hex[:12]}"
        work = pool / str(port)
        if work.exists():
            shutil.rmtree(work)  # An abandoned AVD in this reserved, offline slot.
        work.mkdir()
        (work / "tmp").mkdir()
        env = dict(
            os.environ, ANDROID_AVD_HOME=str(work), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
            CELASTRINA_ADB_SERIAL=serial, CELASTRINA_TV_LOCK_DIR=(work / "device.lock").as_posix(),
            TMPDIR=(work / "tmp").as_posix(),
        )
        env["PATH"] = str(adb.parent) + os.pathsep + env["PATH"]
        processes = []
        try:
            # Keep the host ADB server outside the per-run Windows job.
            subprocess.run([adb, "start-server"], check=True, timeout=20)
            with WindowsJob() as job, (logs / "emulator.log").open("wb") as emulator_log:
                with (logs / "avdmanager.log").open("wb") as output:
                    create = [avdmanager, "create", "avd", "-n", avd, "-k", image, "-p", work / f"{avd}.avd"]
                    creation = job.start(create, input_data=b"no\n", stdout=output, stderr=subprocess.STDOUT, env=env)
                    processes.append(creation)
                    if code := creation.wait(timeout=60):
                        raise subprocess.CalledProcessError(code, create)
                print(f"Booting {avd} on {serial}; logs: {logs}", flush=True)
                process = job.start(
                    [emulator, "-avd", avd, "-port", str(port), "-accel", "on",
                     "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-gpu", "swiftshader"],
                    env=env, stdout=emulator_log, stderr=subprocess.STDOUT,
                )
                processes.append(process)
                wait_for_boot(process, adb, serial, avd, boot_timeout)
                with (logs / "tests.log").open("wb") as output:
                    tests = job.start(
                        [*check, "--apks", bundle], env=env, stdout=output, stderr=subprocess.STDOUT
                    )
                    processes.append(tests)
                    return tests.wait(timeout=test_timeout)
        finally:
            # Closing the job kills its members. Wait for QEMU to release its
            # ports and AVD files before making the slot available again.
            for process in processes:
                process.wait(timeout=15)
            deadline = time.monotonic() + 15
            while processes and not ports_available(port):
                if time.monotonic() >= deadline:
                    raise RuntimeError(f"{serial} still holds its ports; AVD retained at {work}.")
                time.sleep(0.25)
            shutil.rmtree(work)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apks", type=Path, help="Reuse the four APKs produced by emulator-check.sh --prepare-apks")
    parser.add_argument("--bash", default="bash", help="Path to Git for Windows bash.exe")
    parser.add_argument("--image", default=IMAGE, help="Already installed Android TV system image")
    parser.add_argument("--boot-timeout", type=int, default=180)
    parser.add_argument("--test-timeout", type=int, default=900)
    args = parser.parse_args()
    if os.name != "nt":
        parser.error("The disposable emulator runner is supported on Windows.")
    if args.boot_timeout <= 0 or args.test_timeout <= 0:
        parser.error("Timeouts must be positive.")
    if not re.fullmatch(r"system-images;android-\d+;android-tv;\w+", args.image):
        parser.error("--image must be an Android TV SDK system image.")

    try:
        slots = int(os.environ.get("CELASTRINA_EMULATOR_SLOTS", "1"))
        if not 1 <= slots <= len(PORTS):
            parser.error(f"CELASTRINA_EMULATOR_SLOTS must be between 1 and {len(PORTS)}.")
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]).resolve()
        if not (sdk / Path(*args.image.split(";")) / "source.properties").is_file():
            raise RuntimeError(f"Install {args.image} in {sdk} before running this check.")
        for tool in ("platform-tools/adb.exe", "emulator/emulator.exe", "cmdline-tools/latest/bin/avdmanager.bat"):
            if not (sdk / tool).is_file():
                raise RuntimeError(f"Missing {sdk / tool}; no APKs built.")
        bash = shutil.which(args.bash)
        if not bash:
            raise RuntimeError("Git for Windows bash.exe was not found; pass --bash.")
        repo = Path(__file__).resolve().parent.parent
        check = [bash, (repo / "scripts" / "emulator-check.sh").as_posix()]
        logs = repo / "app" / "build" / "reports" / "disposable-emulator" / uuid.uuid4().hex[:12]
        logs.mkdir(parents=True)
        print(f"Device check logs: {logs}", flush=True)
        pool = Path(os.environ["LOCALAPPDATA"]) / "Celastrina" / "emulator-pool"
        # Fail fast if the host is busy without reserving a slot during Gradle.
        with reserve_slot(pool, slots):
            pass
        with tempfile.TemporaryDirectory(prefix="celastrina-apks-") as temporary:
            bundle = args.apks.resolve() if args.apks else Path(temporary)
            if args.apks is None:
                subprocess.run([*check, "--prepare-apks", bundle.as_posix()], check=True)
            for apk in ("debug.apk", "debug-test.apk", "release.apk", "release-test.apk"):
                if not (bundle / apk).is_file():
                    raise RuntimeError(f"Missing {bundle / apk}; no emulator started.")
            return run_device(pool, slots, sdk, args.image, args.boot_timeout, args.test_timeout,
                              check, bundle.as_posix(), logs)
    except (OSError, KeyError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f"Device check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
