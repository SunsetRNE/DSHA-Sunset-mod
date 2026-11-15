#!/usr/bin/env python3
"""Compile the current PTY C handshake on Linux with explicit fake JNI callback boundaries.

Real fork/socketpair/self-stat/ACK/exec/waitpid paths are exercised. This does not
load Android, execute NativeProcess Java, rebuild libtermux.so, or claim ROM proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
JDK = None


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PtyBirthHandshakeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.platform != "linux" or not Path("/proc/self/stat").is_file():
            raise RuntimeError("ENVIRONMENT_GAP: real Linux /proc and fork are required")
        compiler = shutil.which("cc") or shutil.which("gcc") or shutil.which("clang")
        if not compiler:
            raise RuntimeError("ENVIRONMENT_GAP: a Linux host C compiler is required")
        if JDK is None:
            raise RuntimeError("ENVIRONMENT_GAP: pass --jdk with a JDK 17 directory")
        cls.sources = [ROOT / "tools/termux-jni/dsha-pty.c",
                       ROOT / "tools/termux-jni/dsha-pty.h",
                       ROOT / "tools/fixtures/pty-birth/pty-birth-host.c",
                       JDK / "include/jni.h", JDK / "include/linux/jni_md.h"]
        if not all(path.is_file() for path in cls.sources):
            raise RuntimeError("ENVIRONMENT_GAP: current PTY source and Linux JDK JNI headers required")
        cls.before = {str(path): sha256(path) for path in cls.sources}
        cls.directory = tempfile.TemporaryDirectory(prefix="dsha-pty-birth-")
        cls.binary = Path(cls.directory.name) / "pty-birth-host"
        command = [compiler, "-std=c11", "-D_GNU_SOURCE", "-Wall", "-Wextra", "-Werror",
                   "-O2", "-I", str(JDK / "include"), "-I", str(JDK / "include/linux"),
                   "-I", str(ROOT / "tools/termux-jni"), str(cls.sources[2]),
                   str(cls.sources[0]), "-o", str(cls.binary)]
        compiled = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=30)
        if compiled.returncode:
            cls.directory.cleanup()
            raise RuntimeError("PTY_HOST_CURRENT_SOURCE_COMPILE_FAILED:\n" + compiled.stdout + compiled.stderr)
        cls.binary_hash = sha256(cls.binary)
        cls.records = []

    @classmethod
    def tearDownClass(cls):
        try:
            if hasattr(cls, "before"):
                if cls.before != {str(path): sha256(path) for path in cls.sources}:
                    raise RuntimeError("PTY_HOST_INPUTS_CHANGED_DURING_PROBE")
                if sha256(cls.binary) != cls.binary_hash:
                    raise RuntimeError("PTY_HOST_BINARY_CHANGED_DURING_PROBE")
                print(json.dumps({"status": "INPUTS_UNCHANGED", "verifiedCases": len(cls.records),
                    "sourceSha256": cls.before[str(cls.sources[0])],
                    "fixtureSha256": cls.before[str(cls.sources[2])],
                    "binarySha256": cls.binary_hash,
                    "scope": "linux-host-current-pty-c-fake-jni; no Android/JVM callback or device claim"}))
        finally:
            if hasattr(cls, "directory"):
                cls.directory.cleanup()

    def probe(self, case, *, accepted=False, callbacks=0, birth_checked=0):
        # The C fixture gives its test children PDEATHSIG containment. A driver
        # timeout is a failure, never a reason to replay this subprocess.
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True, timeout=12)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        record = json.loads(result.stdout)
        self.assertEqual(case, record["case"])
        self.assertEqual(accepted, record["accepted"])
        self.assertEqual(accepted, record["guestStarted"])
        self.assertEqual(callbacks, record["callbacks"])
        self.assertEqual(birth_checked, record["birthChecked"])
        self.assertIs(record["childReaped"], True)
        self.assertIs(record["siblingUnaffected"], True)
        self.assertEqual("linux-host-current-pty-c-fake-jni", record["scope"])
        self.records.append(record)

    def test_real_self_birth_is_registered_before_ack_then_executes(self):
        self.probe("success", accepted=True, callbacks=1, birth_checked=1)

    def test_callback_reject_never_executes_and_reaps_only_owned_child(self):
        self.probe("reject", callbacks=1, birth_checked=1)

    def test_jni_exception_after_true_callback_still_blocks_ack(self):
        self.probe("exception", callbacks=1, birth_checked=1)

    def test_class_lookup_failure_blocks_ack(self):
        self.probe("class-error")

    def test_method_lookup_failure_blocks_ack(self):
        self.probe("method-error")

    def test_string_allocation_failure_blocks_ack(self):
        self.probe("string-error")

    def test_ack_write_failure_never_executes(self):
        self.probe("ack-write-error", callbacks=1, birth_checked=1)

    def test_child_rejects_non_accepting_ack(self):
        self.probe("ack-reject", birth_checked=1)

    def test_child_rejects_parent_disconnect(self):
        self.probe("parent-disconnect", birth_checked=1)

    def test_child_times_out_while_parent_channel_stays_open_without_ack(self):
        self.probe("parent-silent", birth_checked=1)

    def test_parent_times_out_without_child_birth_and_reaps_owned_child(self):
        self.probe("child-silent")

    def test_truncated_packet_without_final_newline_never_calls_java_boundary(self):
        self.probe("truncated")

    def test_oversized_packet_with_truncated_non_newline_prefix_never_calls_java_boundary(self):
        self.probe("oversized")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    args, tests = parser.parse_known_args()
    if sys.flags.optimize:
        parser.error("Assertions must remain enabled")
    JDK = args.jdk.resolve() if args.jdk else None
    unittest.main(argv=[sys.argv[0], *tests])
