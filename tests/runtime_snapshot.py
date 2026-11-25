"""Run the real Gradle launcher with a resource probe while rebuilding its JAR.

Uses temporary files and an isolated JVM home; never opens the emulator database.
Run with JDK 25 in JAVA_HOME: python tests/runtime_snapshot.py
"""

import os
from pathlib import Path
import subprocess
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ["JAVA_HOME"])
NO_WINDOW = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
GRADLE = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")

PROBE = r"""
import java.nio.file.*;
import java.util.*;

public class RuntimeSnapshotProbe {
    static byte[] resource(String name) throws Exception {
        try (var stream = RuntimeSnapshotProbe.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new AssertionError("Missing resource: " + name);
            return stream.readAllBytes();
        }
    }
    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Path snapshot = null;
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path parent = Path.of(entry).toRealPath().getParent();
            if (!parent.getFileName().toString().startsWith("ocpp-emulator-run-")) {
                throw new AssertionError("Mutable runtime path: " + entry);
            }
            if (snapshot != null && !snapshot.equals(parent)) throw new AssertionError("Mixed snapshots");
            snapshot = parent;
        }
        byte[] before = resource("icons/visibility.svg");
        Files.writeString(home.resolve("ready"), snapshot.toString());
        long deadline = System.nanoTime() + 180_000_000_000L;
        while (!Files.exists(home.resolve("rebuilt"))) {
            if (System.nanoTime() > deadline) throw new AssertionError("Rebuild timed out");
            Thread.sleep(100);
        }
        if (!Arrays.equals(before, resource("icons/visibility.svg"))) throw new AssertionError("Icon changed");
        // Read a previously unopened icon and class after the original JAR was replaced.
        resource("icons/visibility_off.svg");
        resource("com/monta/ocpp/emulator/v16/connection/ChargePointConnection.class");
        System.out.println("Runtime snapshot survived a concurrent JAR rebuild");
    }
}
"""

INIT = """
gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':v16')
    def probeHome = System.getenv('OCPP_SNAPSHOT_PROBE_HOME')
    app.tasks.named('run', JavaExec) {
        mainClass.set('RuntimeSnapshotProbe')
        classpath += app.files(new File(probeHome, 'classes'))
        systemProperty('user.home', probeHome)
    }
}
"""


def main():
    with tempfile.TemporaryDirectory(prefix="ocpp-snapshot-test-") as directory:
        temporary = Path(directory)
        source = temporary / "RuntimeSnapshotProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        classes = temporary / "classes"
        classes.mkdir()
        subprocess.run([str(JAVA_HOME / "bin" / "javac"), "-d", str(classes), str(source)], check=True, creationflags=NO_WINDOW)
        init = temporary / "probe.gradle"
        init.write_text(INIT, encoding="utf-8")
        env = dict(os.environ, OCPP_SNAPSHOT_PROBE_HOME=directory)
        with (temporary / "run.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen([str(GRADLE), ":v16:run", "--init-script", str(init), "--console=plain"],
                                       cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)
            try:
                deadline = time.monotonic() + 180
                while not (temporary / "ready").exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Probe did not start:\n" + (temporary / "run.log").read_text(encoding="utf-8"))
                    time.sleep(0.2)
                snapshot = Path((temporary / "ready").read_text(encoding="utf-8"))
                print("Private runtime ready; rebuilding the original application JAR…", flush=True)
                subprocess.run([str(GRADLE), ":v16:jvmJar", "--rerun-tasks", "--console=plain"],
                               cwd=ROOT, env=env, check=True, timeout=150, creationflags=NO_WINDOW)
                (temporary / "rebuilt").write_text("done", encoding="utf-8")
                if process.wait(timeout=30) != 0:
                    raise RuntimeError((temporary / "run.log").read_text(encoding="utf-8"))
                assert not snapshot.exists(), "Runtime snapshot was not cleaned after JVM exit"
                print("PASS: launcher isolates resources from rebuilds and cleans its snapshot.", flush=True)
            finally:
                # Release the owned probe even when the second build fails.
                (temporary / "rebuilt").write_text("done", encoding="utf-8")
                if process.poll() is None:
                    process.wait(timeout=30)


if __name__ == "__main__":
    main()
