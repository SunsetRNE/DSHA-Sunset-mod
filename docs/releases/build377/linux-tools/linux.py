"""Execute the frozen build's assigned JUnit methods on ext4 with the current compiled inputs."""
from argparse import ArgumentParser
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import shutil
import subprocess
import sys
import tempfile
import time


HERE = Path(__file__).resolve().parent
CONFIG = Path("com/android/tools/test_config.properties")
ANDROID_CONFIG_PATHS = {"android_merged_assets", "android_resource_apk", "android_merged_manifest"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_sha(path):
    paths = sorted(file for file in path.rglob("*") if file.is_file())
    digest = hashlib.sha256()
    for file in paths:
        digest.update(f"{file.relative_to(path).as_posix()}\0{sha(file)}\n".encode())
    return {"files": len(paths), "treeSha256": digest.hexdigest()}


def wsl_path(windows):
    path = PureWindowsPath(windows)
    if not path.is_absolute() or len(path.drive) != 2 or ".." in path.parts:
        raise ValueError(f"Unexpected absolute Windows path: {windows}")
    return Path("/mnt", path.drive[0].lower(), *path.parts[1:])


def snapshot_classpath(worker, working, outgoing):
    record = []
    classpath = []
    android_details = None
    app = wsl_path(worker["testSystemProperties"]["dsha.assetsDir"]).parents[2]
    for index, windows_path in enumerate(worker["classpath"]):
        path = wsl_path(windows_path)
        selected = path
        if not path.exists():
            raise FileNotFoundError(path)
        if path.is_relative_to(app / "build"):
            selected = working / "classpath" / f"{index}-{path.name}"
            selected.parent.mkdir(exist_ok=True)
            if path.is_dir():
                shutil.copytree(path, selected)
            else:
                shutil.copyfile(path, selected)
        row = dict(source=str(path), used=str(selected))
        row.update(tree_sha(selected) if selected.is_dir() else dict(sha256=sha(selected)))
        if selected != path:
            source_digest = tree_sha(path) if path.is_dir() else dict(sha256=sha(path))
            if any(row[key] != value for key, value in source_digest.items()):
                raise ValueError(f"Classpath snapshot drift: {path}")
        if (selected / CONFIG).is_file():
            if android_details is not None:
                raise ValueError("Multiple AGP unit test config files on the classpath")
            config = selected / CONFIG
            android_details = dict(originalSha256=sha(config), inputPaths={})
            config_rows = []
            for line in config.read_text(encoding="utf-8").splitlines():
                key, _, value = line.partition("=")
                if key in ANDROID_CONFIG_PATHS:
                    win_path = PureWindowsPath(value.replace("\\\\", "\\"))
                    translated = wsl_path(str(win_path)) if win_path.is_absolute() else app.joinpath(*win_path.parts)
                    if not translated.exists() or not translated.is_relative_to(app / "build"):
                        raise ValueError(f"Missing or foreign AGP test asset {translated}")
                    entry = dict(path=str(translated), **(tree_sha(translated) if translated.is_dir()
                                                        else dict(sha256=sha(translated))))
                    android_details["inputPaths"][key] = entry
                    line = f"{key}={translated}"
                config_rows.append(line)
            if set(android_details["inputPaths"]) != ANDROID_CONFIG_PATHS:
                raise ValueError("Incomplete AGP config path schema")
            config.write_text("\n".join(config_rows) + "\n", encoding="utf-8")
            android_details["translatedSha256"] = sha(config)
            shutil.copyfile(config, outgoing / "translated-test-config.properties")
        classpath.append(str(selected))
        record.append(row)
    if android_details is None:
        raise ValueError("Missing generated AGP unit test config")
    return classpath, record, android_details


def run_batch(name, targets, expected, jvm_args, output, app):
    command = [*jvm_args, "MatrixJUnit", *targets]
    log = output / (name + ".log")
    started = time.time()
    started_at = datetime.now(timezone.utc).isoformat()
    with log.open("wb") as stream:
        process = subprocess.run(command, cwd=app, stdout=stream, stderr=subprocess.STDOUT, timeout=600, check=False)
    cases = {}
    mapping = []
    first_result = None
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("CASE\t"):
            _, status, method = line.split("\t", 2)
            if method in cases:
                raise ValueError(f"Duplicate Linux method: {method}")
            cases[method] = status
        if line.startswith("JNI_MAP\t"):
            mapping.append(line.split("\t", 1)[1])
        if line.startswith("FINISH\t") and first_result is None:
            _, finished_at, method = line.split("\t", 2)
            first_result = dict(finishedAt=finished_at, method=method)
    missing = sorted(set(expected) - set(cases))
    print(f"Linux {name}: {len(cases)} cases, {len(missing)} missing, exit {process.returncode}", flush=True)
    return dict(command=command, exitCode=process.returncode, durationSeconds=round(time.time() - started, 3),
                startedAt=started_at, firstResult=first_result, finishedAt=datetime.now(timezone.utc).isoformat(),
                methods=cases, missing=missing,
                jniMappings=mapping, logSha256=sha(log))


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--windows", required=True, type=Path)
    parser.add_argument("--rust-dir", type=Path)
    parser.add_argument("--run", required=True)
    parser.add_argument("--wait-for-worker", action="store_true")
    parser.add_argument("--only-class", action="append", default=[])
    args = parser.parse_args()
    if Path(args.run).name != args.run or args.run in {".", ".."}:
        parser.error("--run must name a distinct evidence directory")
    if not args.only_class and args.rust_dir is None:
        parser.error("--rust-dir is required for the complete Linux run")
    output = HERE / args.run
    output.mkdir()
    work = Path(tempfile.mkdtemp(prefix="dsha-build375-junit-"))
    filesystem = subprocess.check_output(["findmnt", "-T", str(work), "-o", "FSTYPE", "-n"], text=True).strip()
    if filesystem != "ext4":
        raise RuntimeError(f"Test workdir needs Linux ext4, got {filesystem}")
    temp = work / "tmp"
    temp.mkdir()
    (temp / "directory").mkdir()
    (temp / "directory-link").symlink_to(temp / "directory", target_is_directory=True)
    os.mkfifo(temp / "capability.fifo", 0o600)
    if not (temp / "directory-link").is_symlink():
        raise RuntimeError("Linux symlink creation capability did not hold")

    worker_file = args.windows / "worker-inputs.json"
    deadline = time.monotonic() + 1500 if args.wait_for_worker else 0
    while not worker_file.is_file() and time.monotonic() < deadline:
        time.sleep(3)
    worker = read(worker_file)
    matrix = read(args.windows / "method-matrix.json")
    if worker["commit"] != args.commit or set(worker["excludePatterns"]) != set(matrix["windowsExclusions"]):
        raise ValueError("Windows inputs do not show the expected source and platform allocation")
    classpath, inputs, android_details = snapshot_classpath(worker, work, output)
    worker_props = worker["testSystemProperties"]
    if worker_props["dsha.sourceOnlyTests"] != "false" or str(worker_props["robolectric.offline"]).lower() != "true":
        raise ValueError("Expected full source-enabled tests and the pinned offline Robolectric frameworks")
    working_directory = wsl_path(worker["workingDirectory"])
    frameworks = wsl_path(worker_props["robolectric.dependency.dir"])
    assets = wsl_path(worker_props["dsha.assetsDir"])
    runner = work / "runner"
    runner.mkdir()
    cp = os.pathsep.join(classpath)
    subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", cp, "-d", str(runner),
                    str(HERE / "MatrixJUnit.java")], check=True, cwd=work)
    base = ["java", "-ea", "-Xmx1024m", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true", "--add-opens=java.base/java.io=ALL-UNNAMED", f"-Djava.io.tmpdir={temp}",
            f"-Ddsha.assetsDir={assets}", "-Ddsha.sourceOnlyTests=false", "-Drobolectric.offline=true",
            f"-Drobolectric.dependency.dir={frameworks}"]
    cp_flags = ["-cp", os.pathsep.join((str(runner), cp))]
    linux_classes = {method.rsplit(".", 1)[0] for method in matrix["linuxMethods"]}
    native_classes = sorted(linux_classes & set(matrix["windowsExclusions"]))
    expected_linux = [name for name in matrix["linuxMethods"]
                      if not args.only_class or name.rsplit(".", 1)[0] in args.only_class]
    native_methods = [name for name in expected_linux if name.rsplit(".", 1)[0] in native_classes]
    posix = [name for name in expected_linux if name not in native_methods]
    posix_targets = ["#".join(name.rsplit(".", 1)) for name in posix]
    if len(expected_linux) != len(posix_targets) + len(native_methods):
        raise AssertionError("Linux method allocation is ambiguous")
    host_rust = None
    if native_methods:
        if args.rust_dir is None:
            parser.error("--rust-dir is required when selecting JNI-dependent tests")
        lib_receipt_path = args.rust_dir / "receipt.json"
        lib_receipt = read(lib_receipt_path)
        lib = work / "libdsha_rs.so"
        shutil.copyfile(args.rust_dir / "libdsha_rs.so", lib)
        lib_sha = sha(lib)
        if (lib_receipt["commit"] != args.commit or lib_receipt["target"] != "host" or
            lib_receipt["artifact"]["sha256"] != lib_sha):
            raise ValueError("Expected exact Linux Rust build artifact for the executed source")
        host_rust = dict(sha256=lib_sha, receiptSha256=sha(lib_receipt_path), path=str(lib))
    recorded = dict(commit=args.commit, workDirectory=str(work), filesystem=filesystem,
                    host=dict(kernel=os.uname().release, architecture=os.uname().machine),
                    java=subprocess.run(["java", "-version"], check=True, capture_output=True, text=True).stderr,
                    runnerSourceSha256=sha(HERE / "MatrixJUnit.java"),
                    workerInputsSha256=sha(worker_file), matrixSha256=sha(args.windows / "method-matrix.json"),
                    classpath=inputs, androidConfig=android_details, frameworks=tree_sha(frameworks), hostRust=host_rust)
    write(output / "inputs.json", recorded)
    batches = {}
    if posix:
        batches["posix"] = run_batch("posix", posix_targets, posix, base + cp_flags, output, working_directory)
    if native_methods:
        targets = [name for name in native_classes if not args.only_class or name in args.only_class]
        native_flags = ["-Xcheck:jni", "-Ddsha.rust.required=true", f"-Ddsha.rust.hostLib={lib}"]
        batches["native"] = run_batch("native", targets, native_methods, base + native_flags + cp_flags, output, working_directory)
    all_methods = {}
    overlap = []
    for batch in batches.values():
        overlap.extend(sorted(all_methods.keys() & batch["methods"].keys()))
        all_methods.update(batch["methods"])
    ok = bool(batches) and all(not batch["exitCode"] and not batch["missing"] for batch in batches.values()) and not overlap
    receipt = dict(status="PASS" if ok else "FAILED_RETAINED", commit=args.commit,
                   batches=batches, methods=all_methods, missing=sorted(set(expected_linux) - set(all_methods)),
                   additions=sorted(set(all_methods) - set(expected_linux)), overlap=overlap,
                   inputsSha256=sha(output / "inputs.json"))
    write(output / "summary.json", receipt)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
