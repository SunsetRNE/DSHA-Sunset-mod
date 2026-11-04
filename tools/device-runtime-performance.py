#!/usr/bin/env python3
"""有界只读 Android 运行采样；负载由操作者产生，不触屏、不重置计数或修改设置。"""
import argparse
import base64
import binascii
import csv
import datetime
import json
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time

from device_script_privacy import redact


SCENARIOS = {"idle": 30, "light": 30, "heavy": 60}
GROUPS = ("app", "renderer", "node", "proot", "other_uid")
UNKNOWN = "unknown"
SOURCES = {
    "dumpsys": "https://developer.android.com/tools/dumpsys",
    "frametimeline": "https://perfetto.dev/docs/data-sources/frametimeline",
    "cpu_frequency": "https://perfetto.dev/docs/data-sources/cpu-freq",
    "gpu": "https://perfetto.dev/docs/data-sources/gpu",
    "perfetto_cli": "https://perfetto.dev/docs/reference/perfetto-cli",
    "adb_shell": "https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md",
}


def metric(value=None, reason=None):
    result = {"status": "observed" if value is not None else UNKNOWN, "value": value}
    if reason:
        result["reason"] = reason
    return result


def parse_proc_stat(text):
    """comm 可以包含空格和右括号；身份使用 PID + starttime。"""
    match = re.match(r"^(\d+) \((.*)\) (\S) (.*)$", text.strip())
    if not match:
        return None
    fields = [match[3]] + match[4].split()
    try:
        return {"pid": int(match[1]), "comm": match[2], "ppid": int(fields[1]),
                "ticks": int(fields[11]) + int(fields[12]), "start_ticks": int(fields[19])}
    except (IndexError, ValueError):
        return None


def parse_ps(text):
    rows = {}
    for line in text.splitlines():
        fields = line.split(None, 3)
        if len(fields) == 4 and all(value.isdigit() for value in fields[:3]):
            pid, ppid, uid = map(int, fields[:3])
            rows[pid] = {"pid": pid, "ppid": ppid, "uid": uid, "name": fields[3]}
    return rows


def parse_cpu_stat(text):
    for line in text.splitlines():
        fields = line.split()
        if fields and fields[0] == "cpu" and len(fields) >= 5:
            try:
                values = list(map(int, fields[1:]))
                # guest/guest_nice 已计入 user/nice，不能再加一次。
                total = sum(values[:8])
                idle = values[3] + (values[4] if len(values) > 4 else 0)
                return {"total": total, "idle_iowait": idle}
            except ValueError:
                return None
    return None


def parse_meminfo(text):
    pss = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if not pss:
        pss = re.search(r"^\s*TOTAL\s+(\d+)\s", text, re.M)
    rss = re.search(r"TOTAL RSS:\s*(\d+)", text)
    pid = re.search(r"MEMINFO in pid (\d+)", text)
    return {"pid": int(pid[1]) if pid else None,
            "pss_kib": metric(int(pss[1]) if pss else None, None if pss else "not exposed by meminfo"),
            "rss_kib": metric(int(rss[1]) if rss else None, None if rss else "not exposed by meminfo")}


def parse_sections(text):
    sections, key = {}, None
    for line in text.splitlines():
        if line.startswith("@@ "):
            key = line[3:]
            sections.setdefault(key, [])
        elif key is not None:
            sections[key].append(line)
    return {key: "\n".join(lines) for key, lines in sections.items()}


def scalar(text, integer=False):
    try:
        return int(text.strip()) if integer else float(text.split()[0])
    except (ValueError, IndexError):
        return None


def parse_snapshot(text):
    sections = parse_sections(text)
    processes = parse_ps(sections.get("ps", ""))
    for pid, process in processes.items():
        process["stat"] = parse_proc_stat(sections.get(f"stat {pid}", ""))
        rss = re.search(r"^VmRSS:\s*(\d+)\s+kB", sections.get(f"rss {pid}", ""), re.M)
        process["rss_kib"] = int(rss[1]) if rss else None
    return {"uptime_s": scalar(sections.get("uptime", "")),
            "cpu": parse_cpu_stat(sections.get("cpu", "")),
            "processes": processes,
            "online_cpus": sections.get("online", UNKNOWN).strip(),
            "cpu_frequency_khz": {key[5:]: scalar(value, True) for key, value in sections.items()
                                  if key.startswith("freq ")},
            "gpu_raw": {key[4:]: value for key, value in sections.items() if key.startswith("gpu ")}}


def renderer_candidate(name):
    return bool(re.search(r"sandboxed_process|webview.*renderer|chromium.*renderer", name, re.I))


def classify(processes, package, uid, renderer_ids, node_ids=(), proot_ids=()):
    """返回互斥分组；Node 子树从 proot/app 中扣除，避免双计。"""
    eligible = {pid: row for pid, row in processes.items()
                if row["uid"] == uid or pid in renderer_ids}
    groups = {name: [] for name in GROUPS}
    for pid, row in eligible.items():
        chain, seen, current = [], set(), pid
        while current in eligible and current not in seen:
            seen.add(current)
            chain.append(current)
            current = eligible[current]["ppid"]
        names = [eligible[item]["name"].rsplit("/", 1)[-1] for item in chain]
        if any(item in renderer_ids for item in chain):
            group = "renderer"
        elif any(item in node_ids for item in chain) or any(name in ("node", "nodejs", "libnode.so") for name in names):
            group = "node"
        elif any(item in proot_ids for item in chain) or any(re.fullmatch(r"(?:lib)?(?:proot|proroot)(?:[-.].*)?", name) for name in names):
            group = "proot"
        elif any(name == package or name.startswith(package + ":") for name in names):
            group = "app"
        else:
            group = "other_uid"
        groups[group].append(pid)
    return {key: sorted(value) for key, value in groups.items()}


def cpu_delta(previous, current, hz):
    result = {"single_core_percent": metric(), "machine_time_percent": metric()}
    before, after = previous.get("stat"), current.get("stat")
    if not before or not after or before["start_ticks"] != after["start_ticks"]:
        return {key: metric(reason="missing process stat or PID birth changed") for key in result}
    elapsed = current.get("uptime_s", 0) - previous.get("uptime_s", 0)
    ticks = after["ticks"] - before["ticks"]
    if elapsed <= 0 or ticks < 0:
        return {key: metric(reason="nonmonotonic counters or clock") for key in result}
    if hz and hz > 0:
        result["single_core_percent"] = metric(round(100 * ticks / hz / elapsed, 3))
    else:
        result["single_core_percent"] = metric(reason="device CLK_TCK unavailable; no assumed 100 Hz")
    total_before, total_after = previous.get("total_ticks"), current.get("total_ticks")
    total = total_after - total_before if total_before is not None and total_after is not None else None
    if total is not None and total > 0 and ticks <= total:
        result["machine_time_percent"] = metric(round(100 * ticks / total, 3))
    else:
        result["machine_time_percent"] = metric(reason="global CPU counters unavailable or inconsistent")
    return result


def summarize_interval(before, after, groups_before, groups_after, hz):
    elapsed = (after["uptime_s"] - before["uptime_s"]
               if after["uptime_s"] is not None and before["uptime_s"] is not None else 0)
    process_metrics = {}
    for pid in set(sum(groups_after.values(), [])):
        old, new = before["processes"].get(pid), after["processes"][pid]
        if old and elapsed > 0:
            old = dict(old, uptime_s=before["uptime_s"], total_ticks=(before["cpu"] or {}).get("total"))
            new = dict(new, uptime_s=after["uptime_s"], total_ticks=(after["cpu"] or {}).get("total"))
            values = cpu_delta(old, new, hz)
        else:
            values = {name: metric(reason="no matching baseline or clock")
                      for name in ("single_core_percent", "machine_time_percent")}
        process_metrics[pid] = values
    groups = {}
    for name in GROUPS:
        pids = groups_after[name]
        stable = bool(pids) and pids == groups_before[name]
        item = {"pids": pids, "membership_stable": stable}
        for field in ("single_core_percent", "machine_time_percent"):
            values = [process_metrics[pid][field]["value"] for pid in pids]
            item[field] = (metric(round(sum(values), 3)) if stable and all(v is not None for v in values)
                           else metric(reason="no observed members, membership changed, or missing CPU data"))
        rss = [after["processes"][pid]["rss_kib"] for pid in pids]
        item["rss_sum_kib"] = (metric(sum(rss)) if rss and all(v is not None for v in rss)
                               else metric(reason="one or more RSS values unavailable"))
        groups[name] = item
    whole = metric(reason="global CPU counters unavailable")
    if before["cpu"] and after["cpu"]:
        total = after["cpu"]["total"] - before["cpu"]["total"]
        idle = after["cpu"]["idle_iowait"] - before["cpu"]["idle_iowait"]
        if total > 0 and 0 <= idle <= total:
            whole = metric(round(100 * (total - idle) / total, 3))
    return {"elapsed_s": elapsed, "groups": groups, "process_cpu": process_metrics,
            "machine_busy_percent_excluding_iowait": whole}


def parse_gfx_frames(text):
    """只解析非 flag 帧，保留窗口身份；不使用累计分位数当本轮结果。"""
    frames, header, window = {}, None, "unknown-window"
    for line in text.splitlines():
        if line.startswith("Window:"):
            window = line.strip()
        if line.startswith("Flags,"):
            candidate = next(csv.reader([line]))
            if all(field in candidate for field in ("Flags", "IntendedVsync", "FrameCompleted")):
                header = candidate
                continue
        if header and re.match(r"^\d+,", line):
            values = next(csv.reader([line]))
            row = dict(zip(header, values))
            try:
                start, end = int(row["IntendedVsync"]), int(row["FrameCompleted"])
                if int(row["Flags"]) == 0 and 0 < start <= end < 2**63 - 1:
                    frames[(window, start)] = (end - start) / 1e6
            except (KeyError, ValueError):
                continue
    return frames


def summarize_cpu(intervals, requested_seconds):
    summary = {}
    for group in GROUPS:
        summary[group] = {}
        for field in ("single_core_percent", "machine_time_percent"):
            known = [(interval["elapsed_s"], interval["groups"][group][field]["value"])
                     for interval in intervals if interval["elapsed_s"] > 0
                     and interval["groups"][group][field]["value"] is not None]
            covered = sum(seconds for seconds, value in known)
            summary[group][field] = {"time_weighted_mean": metric(
                round(sum(seconds * value for seconds, value in known) / covered, 3) if covered else None),
                "observed_coverage_s": round(covered, 3), "requested_window_s": requested_seconds,
                "valid_intervals": len(known), "total_intervals": len(intervals)}
    return summary


def frame_delta(before, after):
    old, new = parse_gfx_frames(before), parse_gfx_frames(after)
    # dumpsys 为滚动缓存；只提取同窗口中超过基线末帧的行，窗口首次出现不猜起点。
    cutoff = {}
    for window, start in old:
        cutoff[window] = max(start, cutoff.get(window, 0))
    durations = [value for (window, start), value in new.items()
                 if window in cutoff and start > cutoff[window]]
    durations.sort()
    return {"status": "observed_tail_only" if durations else UNKNOWN,
            "observed_new_frames": len(durations),
            "p50_ms": statistics.median(durations) if durations else None,
            "p95_ms": durations[min(len(durations)-1, int((len(durations)-1)*0.95))] if durations else None,
            "max_ms": max(durations) if durations else None,
            "coverage": "rolling HWUI cache tail; not full-window FPS, WebView renderer FPS, or FrameTimeline jank",
            "jank_percent": metric(reason="no fixed 16.67ms threshold; use actual per-frame deadlines")}


def snapshot_script(uid, renderer_ids):
    """只插入已校验整数；没有 cmdline/environ 或任意路径参数。"""
    if not isinstance(uid, int) or uid < 0 or any(not isinstance(pid, int) or pid <= 0 for pid in renderer_ids):
        raise ValueError("invalid UID/PID")
    select = f"$3 == {uid}" + "".join(f" || $1 == {pid}" for pid in renderer_ids)
    return r'''printf '@@ uptime\n'; cat /proc/uptime
printf '@@ cpu\n'; cat /proc/stat
printf '@@ online\n'; cat /sys/devices/system/cpu/online
rows=$(ps -A -o PID,PPID,UID,NAME 2>&1)
printf '@@ ps\n%s\n' "$rows"
''' + f'''pids=$(printf '%s\\n' "$rows" | awk '{select} {{ print $1 }}' | head -n 64)
''' + r'''for pid in $pids; do
  printf '@@ stat %s\n' "$pid"; cat "/proc/$pid/stat" 2>&1
  printf '@@ rss %s\n' "$pid"; grep '^VmRSS:' "/proc/$pid/status" 2>&1
done
for cpu in /sys/devices/system/cpu/cpu[0-9]*; do
  printf '@@ freq %s\n' "$cpu"; cat "$cpu/cpufreq/scaling_cur_freq" 2>&1
done
for node in /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*gpu*/gpufreq_usage; do
  [ -e "$node" ] || continue
  printf '@@ gpu %s\n' "$node"; cat "$node" 2>&1
done
exit 0
'''


def perfetto_config(seconds):
    if seconds not in (30, 60):
        raise ValueError("only bounded 30/60 second traces")
    # FrameTimeline-only 配置不读取系统进程 cmdline；调度重跟踪由操作者另做。
    return (f"duration_ms: {seconds * 1000}\n"
            "buffers { size_kb: 8192 fill_policy: RING_BUFFER }\n"
            'data_sources { config { name: "android.surfaceflinger.frametimeline" } }\n')


def frame_sql(package):
    return f"""-- 按进程/窗口独立统计，不能把所有 layer 相加当屏幕 FPS。
SELECT p.name, f.layer_name, COUNT(*) AS layer_frames,
       SUM(CASE WHEN f.jank_type IS NULL OR f.jank_type IN ('Unknown', '') THEN 1 ELSE 0 END) AS unknown_jank_frames,
       SUM(CASE WHEN f.jank_type NOT IN ('None', 'Unknown', '') THEN 1 ELSE 0 END) AS marked_jank_frames,
       AVG(CASE WHEN f.dur >= 0 THEN f.dur END) / 1e6 AS average_slice_ms
FROM actual_frame_timeline_slice f LEFT JOIN process p USING(upid)
WHERE p.name = '{package}' OR p.name GLOB '{package}:*' OR f.layer_name GLOB '*{package}/*'
GROUP BY p.upid, f.layer_name;
-- 空结果 = unknown；先检查 trace 覆盖、数据源、trace 丢包以及该窗口是否真的产帧。
SELECT name, idx, severity, source, value FROM stats WHERE value != 0;
"""


def perfetto_command(adb_prefix):
    # exec-out 不送stdin；Windows adb shell stdout还可能文本换行，ASCII中转后严格解码。
    return list(adb_prefix) + ["shell", "-T", "set -o pipefail; perfetto --txt -c - -o - | base64"]


def decode_trace_ascii(source, output):
    if source.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("base64 trace exceeds the bounded 8 MiB capture envelope")
    encoded = re.sub(rb"[\r\n\t ]", b"", source.read_bytes())
    if not encoded:
        raise ValueError("empty base64 trace")
    try:
        decoded = base64.b64decode(encoded, validate=True)
    except binascii.Error as error:
        raise ValueError("invalid base64 trace; raw transport retained") from error
    with output.open("xb") as stream:
        stream.write(decoded)
    return len(decoded)


class Reader:
    def __init__(self, adb, serial, output):
        self.prefix = [adb, "-s", serial]
        self.identifiers = (serial, adb)
        self.output = output
        self.reads = []

    def read(self, name, args, script=None, deadline=None):
        remaining = 5 if deadline is None else min(5, deadline - time.monotonic())
        if remaining <= 0:
            return ""
        began = time.monotonic()
        try:
            result = subprocess.run(self.prefix + ["shell"] + args, input=script,
                                    capture_output=True, text=True, encoding="utf-8", errors="replace",
                                    timeout=remaining, check=False)
            text = result.stdout + result.stderr
            status, code = "completed", result.returncode
        except subprocess.TimeoutExpired as error:
            text = "read timeout; partial output not accepted"
            status, code = "timeout", None
        except OSError as error:
            text = str(error)
            status, code = "unavailable", None
        text = redact(text, self.identifiers)
        (self.output / (name + ".txt")).write_text(text, encoding="utf-8")
        self.reads.append({"name": name, "status": status, "returncode": code,
                           "host_elapsed_s": round(time.monotonic() - began, 3)})
        return text if status == "completed" and code == 0 else ""


def sample_memory(reader, label, snapshot, groups, deadline):
    result = {}
    for pid in sorted(set(sum(groups.values(), []))):
        if time.monotonic() >= deadline:
            break
        body = reader.read(f"{label}-meminfo-{pid}", ["dumpsys", "-t", "3", "meminfo", str(pid)], deadline=deadline)
        parsed = parse_meminfo(body)
        # meminfo PID 与所选 PID 不一致时不得归属；无 /proc 身份也不能强称一致。
        observed = snapshot["processes"][pid]
        stat = observed.get("stat")
        identity_text = reader.read(f"{label}-memidentity-{pid}", ["cat", f"/proc/{pid}/stat"], deadline=deadline)
        identity = parse_proc_stat(identity_text)
        # native meminfo 通常没有 MEMINFO in pid 标题；指定PID请求+前后出生围栏仍可证明归属。
        verified = bool(stat and identity and stat["start_ticks"] == identity["start_ticks"] and parsed["pid"] in (None, pid))
        result[pid] = {"name": observed["name"], "identity_verified": verified,
                       "raw_pss_kib": parsed["pss_kib"], "raw_rss_kib": parsed["rss_kib"],
                       "pss_kib": parsed["pss_kib"] if verified else metric(reason="memory PID birth unavailable/changed")}
    return result


def summarize_memory(process_memory, groups):
    result = {}
    for name, pids in groups.items():
        values = [process_memory.get(pid, {}).get("pss_kib", {}).get("value") for pid in pids]
        result[name] = {"pids": pids,
                        "pss_sum_kib": metric(sum(values)) if values and all(value is not None for value in values)
                        else metric(reason="one or more PSS/identity reads unavailable, or no observed members"),
                        "scope": "sequential diagnostic snapshots outside CPU window, not an atomic memory snapshot"}
    return result


def environmental_summary(thermal, display, battery):
    """只解析有单位/明确字段名的值；不把支持模式列表当实际刷新率。"""
    temperature = re.search(r"^\s*temperature:\s*(-?\d+)\s*$", battery, re.M)
    sensors = []
    for match in re.finditer(r"Temperature\{mValue=([-\d.]+),\s*mType=(\d+),\s*mName=([^,}]+),\s*mStatus=(\d+)", thermal):
        sensors.append({"celsius": float(match[1]), "type": int(match[2]), "name": match[3], "status": int(match[4])})
    # 多显示屏、render frameRate 与panel refreshRate可能不同；保存明确字段，不猜唯一值。
    active = [line.strip() for line in display.splitlines()
              if re.search(r"mActiveModeId|mActiveSfDisplayMode|mActiveRenderFrameRate|mRefreshRate\s*=|activeMode=", line)]
    return {"battery_temperature_c": metric(int(temperature[1]) / 10 if temperature else None),
            "thermal_sensor_readings": sensors,
            "thermal_status": "observed" if sensors else UNKNOWN,
            "active_display_evidence": active,
            "actual_panel_refresh_hz": metric(reason="review active display/mode evidence; supported modes are not actual refresh"),
            "note": "battery temperature is not CPU/GPU junction temperature; GPU histogram is frame time, not utilization"}


def protocol_text():
    return """采样协议（每次独立目录，单次 idle/light=30s、heavy=60s；不会生成负载）
1. 记录 APK SHA、设备/系统/WebView、引擎、方向、实际刷新率、亮度、充电与温度；保持前后相同。
2. 应用正常就绪后静置 60s，手动准备同一对话与内容；不清数据/缓存，不改 governor/刷新率/Scene授权。
3. idle 30s：前台同一页面无交互；light 30s：同一短提示词真实流式回复，按固定时点人工滚动。
4. heavy 60s：同一长回复/代码块/表格场景，按固定时点人工滚动；记录实际开始、首token、结束和失败。
5. 可在输出 SAMPLING 后产生负载；setup/baseline 与 tail 诊断在计时窗口外，各最多约20s。
6. 每条件至少3轮，交替旧/新版本顺序；轮间自然冷却到接近起始温度，保持网络/模型/提示词一致。
7. 单核100%=一逻辑核占满，可超过100%；machine_time%=进程ticks/全机全部ticks，不按核性能加权。
8. Node子树、proot排除Node的子树、App排除上述子树互斥；other_uid单列；瞬时退出子进程可能漏采。
9. renderer候选不等于归属；核实绑定后传 --renderer-pid 和 --renderer-note。PID出生变化停止归属。
10. RSS相加会重复共享页，PSS需逐进程有效身份；频率不是利用率，GPU原始计数未经驱动语义核实不换算%。
11. gfxinfo只给滚动HWUI尾部，不当完整FPS/网页FPS；可加 --frame-timeline 采有界8MiB trace，不改变设置。
12. FrameTimeline须本地trace_processor执行生成SQL并核对窗口/丢包；有trace文件不等于有有效帧，空数据unknown。
13. 比较每轮CPU时间加权均值、PSS、帧分位数/丢帧及响应时延；合并轮次报中位数和范围，不用峰值单点宣称提升。
14. 所有原始数据仅保存在本机；不自动上传；记录采样开销，不把USB充电功率当应用功耗。
"""


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("protocol", help="打印协议，不调用ADB")
    capture = sub.add_parser("capture", help="一次有界只读采样，设备必须显式选择")
    capture.add_argument("--adb", required=True)
    capture.add_argument("--serial", required=True)
    capture.add_argument("--out", type=Path, required=True, help="必须是不存在的新目录")
    capture.add_argument("--package", default="dsh.android.client")
    capture.add_argument("--scenario", choices=SCENARIOS, required=True)
    capture.add_argument("--interval", type=float, default=2.0)
    capture.add_argument("--renderer-pid", type=int, action="append", default=[])
    capture.add_argument("--renderer-note", default="", help="明确归属的现场证据说明，不填凭据")
    capture.add_argument("--node-pid", type=int, action="append", default=[], help="已核实Node根PID，可处理proroot进程名")
    capture.add_argument("--proot-pid", type=int, action="append", default=[], help="已核实proot/supervisor根PID")
    capture.add_argument("--runtime-note", default="", help="Node/proot明确归属的现场证据说明")
    capture.add_argument("--frame-timeline", action="store_true", help="可选有界FrameTimeline，仅流到本地文件")
    return parser


def capture(args):
    args.out.mkdir(parents=True, exist_ok=False)
    raw = args.out / "raw"
    raw.mkdir()
    reader = Reader(args.adb, args.serial, raw)
    seconds = SCENARIOS[args.scenario]
    config = perfetto_config(seconds)
    (args.out / "frametimeline.pbtxt").write_text(config, encoding="utf-8")
    (args.out / "frametimeline.sql").write_text(frame_sql(args.package), encoding="utf-8")
    record = {"schema": 1, "started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scenario": args.scenario, "requested_seconds": seconds, "interval_seconds": args.interval,
              "package": args.package, "renderer_note": args.renderer_note, "runtime_note": args.runtime_note, "sources": SOURCES,
              "protocol": protocol_text(), "samples": [], "intervals": [], "errors": [],
              "frame_timeline": {"status": UNKNOWN, "reason": "not requested"},
              "gpu_utilization_percent": metric(reason="driver-specific counters not validated; frequency is not utilization")}
    trace_proc = trace_file = trace_error = None
    try:
        setup_end = time.monotonic() + 20
        uid_text = reader.read("package-uid", ["cmd", "package", "list", "packages", "-U", args.package], deadline=setup_end)
        matches = re.findall(r"^package:" + re.escape(args.package) + r" uid:(\d+)\s*$", uid_text, re.M)
        if len(set(matches)) != 1:
            raise ValueError("exact package UID unavailable/ambiguous; no process attribution attempted")
        uid = int(matches[0])
        record["uid"] = uid
        hz = scalar(reader.read("clock-ticks", ["getconf", "CLK_TCK"], deadline=setup_end), True)
        record["device_clock_ticks_per_second"] = metric(hz)
        record["device"] = reader.read("device", ["sh", "-s"], script="getprop ro.product.model\ngetprop ro.build.version.release\ngetprop ro.build.version.sdk\n", deadline=setup_end)
        capability = reader.read("perfetto-query", ["perfetto", "--query"], deadline=setup_end)
        record["frame_timeline_source_registered"] = "android.surfaceflinger.frametimeline" in capability if capability else UNKNOWN
        record["gpu_perfetto_source_lines"] = [line.strip() for line in capability.splitlines() if "gpu" in line.lower()]
        environment_before = {}
        for name, command in (("thermal", ["dumpsys", "-t", "3", "thermalservice"]),
                              ("display", ["dumpsys", "-t", "3", "display"]),
                              ("battery", ["dumpsys", "-t", "3", "battery"])):
            environment_before[name] = reader.read("before-" + name, command, deadline=setup_end)
        record["environment_before"] = environmental_summary(**environment_before)
        gfx_before = reader.read("before-gfx", ["dumpsys", "-t", "3", "gfxinfo", args.package, "framestats"], deadline=setup_end)
        script = snapshot_script(uid, args.renderer_pid)
        initial = parse_snapshot(reader.read("initial-processes", ["sh", "-s"], script, deadline=setup_end))
        accepted_renderers = {}
        for pid in args.renderer_pid:
            row = initial["processes"].get(pid, {})
            if renderer_candidate(row.get("name", "")) and row.get("stat"):
                accepted_renderers[pid] = row["stat"]["start_ticks"]
            else:
                record["errors"].append(f"renderer PID {pid}: name/birth unavailable; remains unassociated")
        runtime_roots = {"node": {}, "proot": {}}
        for kind, pids in (("node", args.node_pid), ("proot", args.proot_pid)):
            for pid in pids:
                row = initial["processes"].get(pid, {})
                if row.get("uid") == uid and row.get("stat"):
                    runtime_roots[kind][pid] = row["stat"]["start_ticks"]
                else:
                    record["errors"].append(f"{kind} PID {pid}: package UID/birth unavailable; explicit association rejected")
        groups = classify(initial["processes"], args.package, uid, accepted_renderers,
                          runtime_roots["node"], runtime_roots["proot"])
        record["memory_before"] = sample_memory(reader, "before", initial, groups, setup_end)
        record["memory_groups_before"] = summarize_memory(record["memory_before"], groups)
        began, deadline = time.monotonic(), time.monotonic() + seconds
        record["sampling_started_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        if args.frame_timeline:
            trace_file = (args.out / "frametimeline.base64").open("xb")
            trace_error = (args.out / "frametimeline-stderr.txt").open("xb")
            trace_proc = subprocess.Popen(perfetto_command(reader.prefix),
                                          stdin=subprocess.PIPE, stdout=trace_file, stderr=trace_error)
            trace_proc.stdin.write(config.encode("utf-8"))
            trace_proc.stdin.close()
            record["frame_timeline"] = {"status": "recording", "parsed_frame_metrics": UNKNOWN,
                                         "buffer_kib": 8192, "duration_ms": seconds * 1000,
                                         "transport": "device base64 over shell -T; strict local decode"}
        print("SAMPLING " + json.dumps({"scenario": args.scenario, "seconds": seconds}), flush=True)
        previous, previous_groups, index = None, None, 0
        while time.monotonic() < deadline:
            snap = parse_snapshot(reader.read(f"sample-{index:03}", ["sh", "-s"], script, deadline=deadline))
            for pid, born in list(accepted_renderers.items()):
                stat = snap["processes"].get(pid, {}).get("stat")
                if not stat or stat["start_ticks"] != born:
                    del accepted_renderers[pid]
                    record["errors"].append(f"renderer PID {pid}: disappeared/unreadable/birth changed; association retired")
            for kind, roots in runtime_roots.items():
                for pid, born in list(roots.items()):
                    stat = snap["processes"].get(pid, {}).get("stat")
                    if not stat or stat["start_ticks"] != born:
                        del roots[pid]
                        record["errors"].append(f"{kind} PID {pid}: disappeared/unreadable/birth changed; explicit association retired")
            grouped = classify(snap["processes"], args.package, uid, accepted_renderers,
                               runtime_roots["node"], runtime_roots["proot"])
            snap["host_elapsed_s"] = round(time.monotonic() - began, 3)
            snap["groups"] = grouped
            snap["unattributed_renderer_candidates"] = [row for pid, row in snap["processes"].items()
                                                       if renderer_candidate(row["name"]) and pid not in accepted_renderers]
            # 不持久化不相关进程表到JSON；原始ps仅含名称而不含参数/环境。
            snap["processes"] = {pid: row for pid, row in snap["processes"].items()
                                 if row["uid"] == uid or pid in accepted_renderers}
            record["samples"].append(snap)
            if previous:
                record["intervals"].append(summarize_interval(previous, snap, previous_groups, grouped, hz))
            previous, previous_groups = snap, grouped
            index += 1
            time.sleep(max(0, min(args.interval - (time.monotonic() - began) % args.interval, deadline - time.monotonic())))
        record["sampling_host_elapsed_s"] = round(time.monotonic() - began, 3)
        print("SAMPLING_DONE", flush=True)
        tail_end = time.monotonic() + 20
        gfx_after = reader.read("after-gfx", ["dumpsys", "-t", "3", "gfxinfo", args.package, "framestats"], deadline=tail_end)
        record["gfxinfo"] = frame_delta(gfx_before, gfx_after)
        environment_after = {}
        for name, command in (("thermal", ["dumpsys", "-t", "3", "thermalservice"]),
                              ("display", ["dumpsys", "-t", "3", "display"]),
                              ("battery", ["dumpsys", "-t", "3", "battery"])):
            environment_after[name] = reader.read("after-" + name, command, deadline=tail_end)
        record["environment_after"] = environmental_summary(**environment_after)
        if previous:
            record["memory_after"] = sample_memory(reader, "after", previous, previous_groups, tail_end)
            record["memory_groups_after"] = summarize_memory(record["memory_after"], previous_groups)
    except (OSError, ValueError, KeyboardInterrupt) as error:
        record["errors"].append(redact(str(error) or "interrupted", reader.identifiers))
    finally:
        if trace_proc:
            try:
                code = trace_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                trace_proc.kill()  # 仅结束本机 adb 客户端；设备trace按 duration_ms 自行到期。
                trace_proc.wait(timeout=5)
                code = None
            trace_file.close()
            trace_error.close()
            size = 0
            try:
                size = decode_trace_ascii(args.out / "frametimeline.base64", args.out / "frametimeline.pftrace")
            except (OSError, ValueError) as error:
                record["errors"].append("FrameTimeline decode: " + str(error))
            record["frame_timeline"].update(status="captured_unparsed" if code == 0 and size else UNKNOWN,
                                             exit_code=code, bytes=size, parsed_frame_metrics=UNKNOWN)
        elif trace_file:
            trace_file.close()
            if trace_error:
                trace_error.close()
        record["reads"] = reader.reads
        record["cpu_summary"] = summarize_cpu(record["intervals"], seconds)
        record["completed_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (args.out / "measurement.json").write_text(json.dumps(redact(record, reader.identifiers), ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(args.out / "measurement.json"), "samples": len(record["samples"]),
                      "errors": record["errors"]}, ensure_ascii=False))
    return 0 if len(record["samples"]) >= 2 else 1


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "protocol":
        print(protocol_text())
        return 0
    if not args.adb.strip() or not args.serial.strip() or args.serial.startswith("-"):
        parser.error("adb and serial must be explicit nonempty values")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("invalid package")
    if not 2 <= args.interval <= 10:
        parser.error("interval must be between 2 and 10 seconds")
    if any(pid <= 0 for pid in args.renderer_pid) or len(args.renderer_pid) > 16:
        parser.error("at most 16 positive renderer PIDs")
    if args.renderer_pid and not args.renderer_note.strip():
        parser.error("renderer PIDs require --renderer-note with observed attribution evidence")
    if any(pid <= 0 for pid in args.node_pid + args.proot_pid) or len(args.node_pid + args.proot_pid) > 16:
        parser.error("at most 16 positive runtime root PIDs")
    if set(args.node_pid) & set(args.proot_pid):
        parser.error("a runtime root cannot be both Node and proot")
    if (args.node_pid or args.proot_pid) and not args.runtime_note.strip():
        parser.error("explicit runtime roots require --runtime-note with observed attribution evidence")
    if args.out.exists():
        parser.error("output directory must not already exist; evidence is never overwritten")
    return capture(args)


if __name__ == "__main__":
    sys.exit(main())
