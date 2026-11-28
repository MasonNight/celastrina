"""Exercise the TV command interface without touching a household device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('tv.sh').resolve()


class AppsCommandsTest(unittest.TestCase):
    def run_command(self, command, *, fail=False, busy=False, fail_home=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / 'adb'
            adb.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = ' '.join(sys.argv[1:])
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(args + '\\n')
if 'media_session' in args and os.environ.get('BUSY') == '1':
    print('package=other.app\\nstate=PlaybackState {state=3,')
if 'date +%s' in args: print('1000')
if 'ALL_APPS' in args:
    # Fire OS's trampoline never draws a window for am start -W to observe.
    if ' -W ' in ' ' + args + ' ': sys.exit('Proxy activity does not draw a window')
    if os.environ.get('FAIL_APPS') == '1':
        print('Error: Activity class does not exist.')
        sys.exit(1)
if 'android.intent.category.HOME' in args and os.environ.get('FAIL_HOME') == '1':
    print('Error: Activity class does not exist.')
if 'screencap' in args: sys.stdout.buffer.write(b'\\x89PNG\\r\\n\\x1a\\nfixture')
''')
            adb.chmod(0o755)
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}',
                       CELASTRINA_ADB_SERIAL='test-tv', CELASTRINA_TV_LOCK_DIR=str(root / 'lock'),
                       COMMAND_LOG=str(root / 'commands'), FAIL_APPS=str(int(fail)), BUSY=str(int(busy)), FAIL_HOME=str(int(fail_home)))
            out = root / 'tile.png'
            args = ['bash', str(SCRIPT), command]
            if command == 'tile-shot':
                args.append(str(out))
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=5)
            log = (root / 'commands').read_text() if (root / 'commands').exists() else ''
            return result, log, out.read_bytes() if out.exists() else b'', (root / 'lock').exists()

    def test_apps_uses_the_discovered_activity_without_keys(self):
        result, log, _, locked = self.run_command('apps')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('com.amazon.tv.launcher/.util.QuickSettingsProxyActivity', log)
        self.assertIn('android.intent.action.ALL_APPS', log)
        self.assertNotIn('keyevent', log)
        self.assertFalse(locked)

    def test_tile_shot_saves_png_then_returns_home(self):
        result, log, data, locked = self.run_command('tile-shot')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(data.startswith(b'\x89PNG\r\n\x1a\n'))
        self.assertLess(log.index('screencap'), log.index('android.intent.category.HOME'))
        self.assertNotIn('keyevent', log)
        self.assertFalse(locked)

    def test_missing_activity_fails_clearly_without_keys(self):
        for command in ('apps', 'tile-shot'):
            with self.subTest(command=command):
                result, log, _, locked = self.run_command(command, fail=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Could not open Your Apps & Channels', result.stderr)
                self.assertNotIn('keyevent', log)
                self.assertFalse(locked)

    def test_failed_home_start_reports_failure_even_when_adb_exits_zero(self):
        result, _, data, locked = self.run_command('tile-shot', fail_home=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Could not return to Home', result.stderr)
        self.assertTrue(data)
        self.assertFalse(locked)

    def test_busy_tv_is_left_alone(self):
        for command in ('apps', 'tile-shot'):
            with self.subTest(command=command):
                result, log, _, _ = self.run_command(command, busy=True)
                self.assertEqual(result.returncode, 3)
                self.assertNotIn('am start', log)


if __name__ == '__main__':
    unittest.main()
