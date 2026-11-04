#!/usr/bin/env python3
"""运行采样解析/身份/边界测试：只用合成数据与假进程，绝不执行ADB。"""
import contextlib
import base64
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("runtime_perf", Path(__file__).with_name("device-runtime-performance.py"))
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


def stat_line(pid=20, ppid=10, ticks=300, born=42, comm="node"):
    fields = ["S", str(ppid)] + ["0"] * 48
    fields[11], fields[12], fields[19] = str(ticks), "0", str(born)
    return f"{pid} ({comm}) " + " ".join(fields)


def row(pid, ppid, name, uid=10042):
    return {"pid": pid, "ppid": ppid, "uid": uid, "name": name,
            "stat": perf.parse_proc_stat(stat_line(pid, ppid)), "rss_kib": 100}


class RuntimePerformanceTest(unittest.TestCase):
    def test_stat_comm_with_spaces_and_parentheses(self):
        parsed = perf.parse_proc_stat(stat_line(comm="node ) child (name)"))
        self.assertEqual((20, 10, 300, 42), (parsed["pid"], parsed["ppid"], parsed["ticks"], parsed["start_ticks"]))

    def test_denied_stat_is_unknown(self):
        self.assertIsNone(perf.parse_proc_stat("cat: /proc/20/stat: Permission denied"))
        self.assertIsNone(perf.parse_proc_stat("20 (node) S"))

    def test_cpu_guest_time_not_counted_twice(self):
        parsed = perf.parse_cpu_stat("cpu 100 20 30 400 50 6 7 8 90 10\ncpu0 1 1 1 1")
        self.assertEqual({"total": 621, "idle_iowait": 450}, parsed)

    def test_cpu_single_core_and_machine_denominators(self):
        old = {"stat": perf.parse_proc_stat(stat_line(ticks=200)), "uptime_s": 5, "total_ticks": 1000}
        new = {"stat": perf.parse_proc_stat(stat_line(ticks=500)), "uptime_s": 7, "total_ticks": 3000}
        measured = perf.cpu_delta(old, new, 100)
        self.assertEqual(150, measured["single_core_percent"]["value"])
        self.assertEqual(15, measured["machine_time_percent"]["value"])

    def test_unknown_hz_not_assumed(self):
        old = {"stat": perf.parse_proc_stat(stat_line(ticks=200)), "uptime_s": 5, "total_ticks": 1000}
        new = {"stat": perf.parse_proc_stat(stat_line(ticks=500)), "uptime_s": 7, "total_ticks": 3000}
        measured = perf.cpu_delta(old, new, None)
        self.assertIsNone(measured["single_core_percent"]["value"])
        self.assertEqual(15, measured["machine_time_percent"]["value"])

    def test_missing_one_global_cpu_baseline_not_treated_as_zero(self):
        old = {"stat": perf.parse_proc_stat(stat_line(ticks=200)), "uptime_s": 5, "total_ticks": None}
        new = {"stat": perf.parse_proc_stat(stat_line(ticks=500)), "uptime_s": 7, "total_ticks": 3000}
        measured = perf.cpu_delta(old, new, 100)
        self.assertEqual(150, measured["single_core_percent"]["value"])
        self.assertIsNone(measured["machine_time_percent"]["value"])

    def test_pid_reuse_never_inherits_cpu(self):
        old = {"stat": perf.parse_proc_stat(stat_line(born=1)), "uptime_s": 5, "total_ticks": 1000}
        new = {"stat": perf.parse_proc_stat(stat_line(born=2)), "uptime_s": 7, "total_ticks": 3000}
        self.assertTrue(all(value["status"] == "unknown" for value in perf.cpu_delta(old, new, 100).values()))

    def test_runtime_subtrees_are_disjoint(self):
        processes = {10: row(10, 1, "dsh.android.client"), 11: row(11, 10, "libproot.so"),
                     12: row(12, 11, "node"), 13: row(13, 12, "worker"), 14: row(14, 11, "bash"),
                     15: row(15, 1, "com.webview:sandboxed_process0", 99001),
                     16: row(16, 1, "com.webview:sandboxed_process1", 99002),
                     17: row(17, 1, "orphan")}
        groups = perf.classify(processes, "dsh.android.client", 10042, {15})
        self.assertEqual({"app": [10], "proot": [11, 14], "node": [12, 13], "renderer": [15], "other_uid": [17]}, groups)
        self.assertNotIn(16, sum(groups.values(), []))

    def test_explicit_node_root_handles_proroot_name(self):
        processes = {10: row(10, 1, "dsh.android.client"), 11: row(11, 10, "proroot-bridge"),
                     12: row(12, 11, "proroot-bridge"), 13: row(13, 12, "proroot-bridge")}
        groups = perf.classify(processes, "dsh.android.client", 10042, {}, {12}, {11})
        self.assertEqual([12, 13], groups["node"])
        self.assertEqual([11], groups["proot"])

    def test_parent_cycle_terminates(self):
        processes = {10: row(10, 11, "x"), 11: row(11, 10, "y")}
        self.assertEqual([10, 11], perf.classify(processes, "dsh.android.client", 10042, {})["other_uid"])

    def test_group_membership_change_is_not_zero(self):
        before = {"uptime_s": 1, "cpu": {"total": 100, "idle_iowait": 50}, "processes": {}}
        after = {"uptime_s": 3, "cpu": {"total": 200, "idle_iowait": 80}, "processes": {10: row(10, 1, "dsh.android.client")}}
        gb = {name: [] for name in perf.GROUPS}
        ga = dict(gb, app=[10])
        result = perf.summarize_interval(before, after, gb, ga, 100)
        self.assertIsNone(result["groups"]["app"]["single_core_percent"]["value"])
        self.assertIsNone(result["groups"]["renderer"]["single_core_percent"]["value"])
        self.assertEqual(70, result["machine_busy_percent_excluding_iowait"]["value"])

    def test_meminfo_old_and_new(self):
        old = perf.parse_meminfo("** MEMINFO in pid 42 [node] **\n TOTAL 1536 20 30 40")
        new = perf.parse_meminfo("** MEMINFO in pid 42 [node] **\n TOTAL PSS: 2048 TOTAL RSS: 4096")
        self.assertEqual(1536, old["pss_kib"]["value"])
        self.assertIsNone(old["rss_kib"]["value"])
        self.assertEqual(4096, new["rss_kib"]["value"])
        self.assertIsNone(perf.parse_meminfo("No process found")["pss_kib"]["value"])

    def test_snapshot_gpu_raw_not_percent(self):
        sample = perf.parse_snapshot("@@ uptime\n123.4 50\n@@ cpu\ncpu 10 2 3 20\n@@ ps\nPID PPID UID NAME\n20 10 10042 node\n@@ stat 20\n" + stat_line() + "\n@@ rss 20\nVmRSS: 4096 kB\n@@ gpu /sys/class/devfreq/gpufreq_core/gpufreq_usage\n15 200\n@@ freq /sys/devices/system/cpu/cpu0\nPermission denied")
        self.assertEqual(4096, sample["processes"][20]["rss_kib"])
        self.assertEqual({"/sys/class/devfreq/gpufreq_core/gpufreq_usage": "15 200"}, sample["gpu_raw"])
        self.assertIsNone(sample["cpu_frequency_khz"]["/sys/devices/system/cpu/cpu0"])

    def test_gfx_tail_excludes_flags_old_frames_and_new_window(self):
        header = "Window: main\nFlags,IntendedVsync,Vsync,FrameCompleted,\n"
        old = header + "0,100,100,10000100,\n"
        new = header + "0,100,100,10000100,\n0,200,200,20000200,\n1,300,300,90000300,\nWindow: new\n0,400,400,90000400,\n"
        result = perf.frame_delta(old, new)
        self.assertEqual(1, result["observed_new_frames"])
        self.assertEqual(20, result["p50_ms"])
        self.assertIsNone(result["jank_percent"]["value"])
        self.assertEqual("unknown", perf.frame_delta("", new)["status"])

    def test_android17_gfx_extra_header_fields(self):
        header = "Window: web\nFlags,FrameTimelineVsyncId,IntendedVsync,Vsync,FrameCompleted,\n"
        before = header + "0,123,100,100,9000100,\n"
        after = header + "0,124,200,200,11000200,\n"
        self.assertEqual(11, perf.frame_delta(before, after)["p50_ms"])

    def test_bounded_frame_config(self):
        self.assertIn("duration_ms: 60000", perf.perfetto_config(60))
        self.assertIn("android.surfaceflinger.frametimeline", perf.perfetto_config(30))
        with self.assertRaises(ValueError):
            perf.perfetto_config(600)

    def test_perfetto_transport_can_send_config_and_receive_binary(self):
        command = perf.perfetto_command(["fake-adb", "-s", "explicit-device"])
        self.assertEqual(["fake-adb", "-s", "explicit-device", "shell", "-T", "set -o pipefail; perfetto --txt -c - -o - | base64"], command)
        self.assertNotIn("exec-out", command)
        self.assertNotIn("-x", command)

    def test_base64_trace_roundtrip_all_byte_values_crlf_safe(self):
        binary = bytes(range(256)) + b"\r\n\x00\r\r\n\xff"
        encoded = base64.encodebytes(binary).replace(b"\n", b"\r\n")
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory)/"raw.b64", Path(directory)/"trace.pftrace"
            source.write_bytes(encoded)
            self.assertEqual(len(binary), perf.decode_trace_ascii(source, output))
            self.assertEqual(binary, output.read_bytes())
            self.assertEqual(encoded, source.read_bytes())

    def test_invalid_base64_not_repaired_silently(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory)/"raw.b64", Path(directory)/"trace.pftrace"
            source.write_bytes(b"some message, not a trace!")
            with self.assertRaises(ValueError):
                perf.decode_trace_ascii(source, output)
            self.assertFalse(output.exists())

    def test_native_meminfo_without_pid_header_uses_birth_fence(self):
        process = row(20, 10, "node")
        responses = [" TOTAL PSS: 1536 TOTAL RSS: 4096", stat_line()]
        with patch.object(perf.Reader, "read", side_effect=responses):
            reader = perf.Reader("fake", "fake", Path("unused"))
            result = perf.sample_memory(reader, "after", {"processes": {20: process}}, {"node": [20]}, perf.time.monotonic()+10)
        self.assertTrue(result[20]["identity_verified"])
        self.assertEqual(1536, result[20]["pss_kib"]["value"])

    def test_snapshot_only_embeds_numeric_ids(self):
        with self.assertRaises(ValueError):
            perf.snapshot_script("100; reboot", [])
        script = perf.snapshot_script(10042, [123])
        self.assertIn("$3 == 10042 || $1 == 123 { print $1 }", script)
        self.assertNotIn("cmdline", script)
        self.assertNotIn("environ", script)

    def test_invalid_cli_never_contacts_device(self):
        base = ["capture", "--out", "unused-no-device-directory", "--scenario", "light"]
        variants = [base, base + ["--adb", "fake"], base + ["--serial", "fake"],
                    base + ["--adb", "fake", "--serial", "fake", "--package", "x;reboot"],
                    base + ["--adb", "fake", "--serial", "fake", "--renderer-pid", "2"],
                    base + ["--adb", "fake", "--serial", "fake", "--node-pid", "2"],
                    base + ["--adb", "fake", "--serial", "fake", "--interval", "0"]]
        for argv in variants:
            with self.subTest(argv=argv), patch.object(subprocess, "run") as run, patch.object(subprocess, "Popen") as popen, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as stopped:
                    perf.main(argv)
                self.assertEqual(2, stopped.exception.code)
                run.assert_not_called()
                popen.assert_not_called()

    def test_protocol_never_contacts_device(self):
        with patch.object(subprocess, "run") as run, patch.object(subprocess, "Popen") as popen, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, perf.main(["protocol"]))
            run.assert_not_called()
            popen.assert_not_called()

    def test_reader_selects_serial_and_records_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            reader = perf.Reader("test-adb", "secret-serial", Path(directory))
            response = subprocess.CompletedProcess([], 1, "denied secret-serial", "error")
            with patch.object(subprocess, "run", return_value=response) as run:
                self.assertEqual("", reader.read("failed", ["cat", "/proc/20/stat"]))
            self.assertEqual(["test-adb", "-s", "secret-serial", "shell", "cat", "/proc/20/stat"], run.call_args.args[0])
            self.assertNotIn("secret-serial", (Path(directory) / "failed.txt").read_text())
            self.assertEqual(1, reader.reads[0]["returncode"])

    def test_incomplete_pss_sum_remains_unknown(self):
        memory = {10: {"pss_kib": perf.metric(100)}, 11: {"pss_kib": perf.metric()}}
        groups = {"node": [10, 11], "app": [], "proot": [10]}
        result = perf.summarize_memory(memory, groups)
        self.assertIsNone(result["node"]["pss_sum_kib"]["value"])
        self.assertIsNone(result["app"]["pss_sum_kib"]["value"])
        self.assertEqual(100, result["proot"]["pss_sum_kib"]["value"])

    def test_temperature_and_mode_list_not_actual_refresh(self):
        result = perf.environmental_summary(
            "Temperature{mValue=43.5, mType=1, mName=CPU, mStatus=0}",
            "supportedModes=[60,90,120]\nmActiveModeId=3", "  temperature: 325\n")
        self.assertEqual(32.5, result["battery_temperature_c"]["value"])
        self.assertEqual(43.5, result["thermal_sensor_readings"][0]["celsius"])
        self.assertEqual(["mActiveModeId=3"], result["active_display_evidence"])
        self.assertIsNone(result["actual_panel_refresh_hz"]["value"])

    def test_cpu_summary_is_time_weighted_and_exposes_coverage(self):
        intervals = []
        for duration, value in ((2, 10), (4, 40), (2, None)):
            groups = {group: {field: perf.metric(value) for field in ("single_core_percent", "machine_time_percent")}
                      for group in perf.GROUPS}
            intervals.append({"elapsed_s": duration, "groups": groups})
        result = perf.summarize_cpu(intervals, 30)["node"]["single_core_percent"]
        self.assertEqual(30, result["time_weighted_mean"]["value"])
        self.assertEqual(6, result["observed_coverage_s"])
        self.assertEqual(2, result["valid_intervals"])

    def test_full_capture_fake_clock_and_fake_reader_no_subprocess(self):
        now = [0.0]

        def read(reader, name, args, script=None, deadline=None):
            now[0] += 0.01
            if name == "package-uid":
                return "package:dsh.android.client uid:10042\n"
            if name == "clock-ticks":
                return "100"
            if name.endswith("-processes") or name.startswith("sample-"):
                body = (f"@@ uptime\n{100 + now[0]} 0\n@@ cpu\ncpu {int(now[0]*100)} 0 0 {int(now[0]*900)}\n"
                        "@@ ps\nPID PPID UID NAME\n10 1 10042 dsh.android.client\n11 10 10042 proroot-bridge\n12 11 10042 proroot-bridge\n20 1 99001 com.webview:sandboxed_process0\n")
                for pid, parent in ((10, 1), (11, 10), (12, 11), (20, 1)):
                    body += f"@@ stat {pid}\n" + stat_line(pid, parent, int(now[0] * 20)) + f"\n@@ rss {pid}\nVmRSS: 4096 kB\n"
                return body
            if "-meminfo-" in name:
                return f"** MEMINFO in pid {args[-1]} [name] **\n TOTAL PSS: 1024 TOTAL RSS: 2048"
            if "-memidentity-" in name:
                return stat_line(int(args[-1].split("/")[2]))
            return ""

        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "new-run"
            with patch.object(perf.Reader, "read", read), patch.object(perf.time, "monotonic", side_effect=lambda: now[0]), \
                 patch.object(perf.time, "sleep", side_effect=lambda delay: now.__setitem__(0, now[0]+delay)), \
                 patch.object(subprocess, "run", side_effect=AssertionError("device contacted")) as run, \
                 patch.object(subprocess, "Popen", side_effect=AssertionError("device contacted")) as popen, \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, perf.main(["capture", "--adb", "fake", "--serial", "fake", "--scenario", "light",
                                              "--out", str(target), "--renderer-pid", "20", "--renderer-note", "test fixture binding",
                                              "--node-pid", "12", "--proot-pid", "11", "--runtime-note", "test fixture ancestry"]))
            run.assert_not_called()
            popen.assert_not_called()
            report = json.loads((target / "measurement.json").read_text(encoding="utf-8"))
            self.assertEqual(15, len(report["samples"]))
            self.assertEqual(14, len(report["intervals"]))
            self.assertEqual([12], report["samples"][0]["groups"]["node"])
            self.assertEqual([11], report["samples"][0]["groups"]["proot"])
            self.assertEqual([20], report["samples"][0]["groups"]["renderer"])
            self.assertEqual(1024, report["memory_groups_after"]["node"]["pss_sum_kib"]["value"])
            self.assertEqual("unknown", report["frame_timeline"]["status"])
            self.assertIsNone(report["gpu_utilization_percent"]["value"])
            self.assertLess(now[0], 40)


if __name__ == "__main__":
    unittest.main()
