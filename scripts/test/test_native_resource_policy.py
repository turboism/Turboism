"""Offline auxiliary admission tests; never initializes or launches Cubism."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JDK = Path(os.environ.get('JAVA17_HOME', '/usr/lib/jvm/java-17-openjdk'))
AGENT = ROOT / 'build/resource-host-validation-exerciser.jar'


class ResourcePolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not AGENT.is_file() or not (JDK / 'bin/javac').is_file():
            raise RuntimeError('buildResourceHostProbe and JAVA17_HOME are required; do not silently skip')
        cls.temp = tempfile.TemporaryDirectory()
        cls.classes = Path(cls.temp.name)
        source = cls.classes / 'PolicyWaiter.java'
        source.write_text('''
import java.nio.file.*;
import java.util.Properties;
public class PolicyWaiter {
 public static void main(String[] args) throws Exception {
  Path result = Path.of(args[0], "state/resource-workload/result.properties");
  long until = System.nanoTime() + 5_000_000_000L;
  while (!Files.isRegularFile(result) && System.nanoTime() < until) Thread.sleep(10);
  Properties p = new Properties();
  try (var in = Files.newInputStream(result)) { p.load(in); }
  if (!"FAIL".equals(p.getProperty("status")) || !p.getProperty("failure", "").contains(args[1]))
    throw new AssertionError(p.toString());
  if (p.containsKey("zoom.completed") || p.containsKey("idle.begin.epochMillis"))
    throw new AssertionError("rejected configuration reached native workload");
 }
}
''')
        subprocess.run([str(JDK / 'bin/javac'), '--release', '17', str(source)], check=True,
                       capture_output=True, text=True, timeout=30)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_rejection(self, seconds, expected, option=None):
        with tempfile.TemporaryDirectory() as home:
            command = [str(JDK / 'bin/java'), '-Djava.awt.headless=true',
                       f'-Dturboism.validation.textureUpload.home={home}',
                       f'-Dturboism.validation.textureUpload.memoryIdleSeconds={seconds}',
                       '-Dturboism.validation.textureUpload.loadingTrace=false']
            if option:
                command.append(f'-Dturboism.optimization.{option}=true')
            command += [f'-javaagent:{AGENT}', '-cp', str(self.classes), 'PolicyWaiter', home, expected]
            result = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_window_rejected(self):
        self.run_rejection(0, 'requires 300s sampler window')

    def test_excess_window_rejected(self):
        self.run_rejection(301, 'requires 300s sampler window')

    def test_each_enabled_product_candidate_rejected(self):
        for option in ('imageArchiveReuse', 'floatArrayParseCache',
                       'textureUploadPreparation', 'warpPositionProjection'):
            with self.subTest(option=option):
                self.run_rejection(300, 'requires optimizations off', option)


if __name__ == '__main__':
    unittest.main()
