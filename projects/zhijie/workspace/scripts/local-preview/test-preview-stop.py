import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.name == 'nt', 'requires Windows PowerShell')
class PreviewStopTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('JAVA_HOME'), 'requires JAVA_HOME for the Java exit scenarios')
    def test_live_java_exit_and_remaining_database_lock(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stop = root / 'scripts/local-preview/stop.ps1'
            stop.parent.mkdir(parents=True)
            shutil.copyfile(Path(__file__).with_name('stop.ps1'), stop)
            runtime = root / 'target/local-preview'
            database = runtime / 'postgres'
            database.mkdir(parents=True)
            source = runtime / 'LocalPreview.java'
            source.write_text('''import java.nio.file.*;
public class LocalPreview {
    public static void main(String[] args) throws Exception {
        while (!Files.exists(Path.of("stop"))) Thread.sleep(50);
        System.exit(Integer.parseInt(args[0]));
    }
}''', encoding='utf-8')
            subprocess.run([java / 'javac.exe', '-J-XX:-UsePerfData', str(source)], check=True, timeout=20)
            for exit_code, locked in [(0, False), (17, False), (0, True)]:
                with self.subTest(exit_code=exit_code, locked=locked):
                    lock = database / 'postmaster.pid'
                    if locked:
                        lock.write_bytes(b'unchanged')
                    process = subprocess.Popen(f'"{java / "java.exe"}" -XX:-UsePerfData -cp "{runtime};." LocalPreview {exit_code} 0',
                                               cwd=runtime)
                    try:
                        (runtime / 'server.pid').write_text(str(process.pid), encoding='ascii')
                        result = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(stop)],
                                                capture_output=True, timeout=30)
                        self.assertTrue((runtime / 'stop').exists(), result.stderr)
                        self.assertEqual(process.wait(timeout=5), exit_code)
                        self.assertEqual(result.returncode == 0, exit_code == 0 and not locked)
                        if locked:
                            self.assertIn(b'postmaster.pid', result.stderr)
                            self.assertEqual(lock.read_bytes(), b'unchanged')
                        elif exit_code:
                            self.assertIn(b'17', result.stderr)
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.wait(timeout=5)
                        (runtime / 'stop').unlink(missing_ok=True)

    def test_database_lock_blocks_success_with_missing_or_exited_java(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stop = root / 'scripts/local-preview/stop.ps1'
            stop.parent.mkdir(parents=True)
            shutil.copyfile(Path(__file__).with_name('stop.ps1'), stop)
            runtime = root / 'target/local-preview'
            database = runtime / 'postgres'
            database.mkdir(parents=True)
            lock = database / 'postmaster.pid'
            marker = b'fixture: leave unchanged'
            child = subprocess.Popen([sys.executable, '-B', '-c', 'pass'])
            self.assertEqual(child.wait(timeout=10), 0)
            for recorded in (False, True):
                with self.subTest(recorded=recorded):
                    if recorded:
                        (runtime / 'server.pid').write_text(str(child.pid), encoding='ascii')
                    lock.write_bytes(marker)
                    command = ['powershell', '-NoProfile', '-NonInteractive', '-File', str(stop)]
                    blocked = subprocess.run(command, capture_output=True, timeout=15)
                    self.assertNotEqual(blocked.returncode, 0)
                    self.assertIn(b'postmaster.pid', blocked.stderr)
                    self.assertEqual(lock.read_bytes(), marker)
                    self.assertFalse((runtime / 'stop').exists())
                    lock.unlink()
                    self.assertEqual(subprocess.run(command, capture_output=True, timeout=15).returncode, 0)


if __name__ == '__main__':
    unittest.main()
