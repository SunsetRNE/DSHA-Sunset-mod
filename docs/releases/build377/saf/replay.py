"""Compare the same synthetic SAF exercises with build376 or the current build377 sources."""
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import sys

root = Path("F:/DSHA_RESTART")
here = Path(__file__).resolve().parent
src = root / "source/build377-local"
worker = json.loads((root / "artifacts/build376-formal-20261125/targeted-r1/worker-inputs.json").read_text(encoding="utf-8-sig"))
variant, label = sys.argv[1:3]
assert variant in ("before", "after") and label.isalnum()
out = here / f"{variant}-{label}"
out.mkdir(exist_ok=False)
classes = out / "classes"
classes.mkdir()
parts = ["app/src/main/java/com/deepseekharness/app/ui/WebDownloadModel.java",
         "app/src/main/java/com/deepseekharness/app/data/WorkspaceFileExports.java",
         "app/src/main/java/com/deepseekharness/app/data/DownloadsExport.java",
         "app/src/main/java/com/deepseekharness/app/backup/DocumentStreams.java"]
files = []
if variant == "after":
    parts += ["app/src/main/java/com/deepseekharness/app/transfer/SelectedDocumentSave.java"]
for part in parts:
    file = out / Path(part).name
    data = subprocess.check_output(["git", "show", f"12efafd7:{part}"], cwd=src) if variant == "before" else (src/part).read_bytes()
    file.write_bytes(data)
    files.append(file)
files += [src / "app/src/test/java/com/deepseekharness/app/ui/WebDownloadSaveTest.java",
          src / "app/src/test/java/com/deepseekharness/app/backup/BackupDownloadsPublicationTest.java",
          src / "app/src/test/java/com/deepseekharness/app/transfer/TransferBehaviorTestSupport.java",
          src / "app/src/test/java/com/deepseekharness/app/transfer/DownloadEngineBehaviorTest.java",
          src / "app/src/test/java/com/deepseekharness/app/data/WorkspaceFileDestinationTest.java", here / "SafTestRun.java"]
cp = ";".join([classes.as_posix()] + [Path(x).as_posix() for x in worker["classpath"]])
java = Path(worker["javaExecutable"])
args = out / "javac.args"
args.write_text("\n".join(["-encoding", "UTF-8", "-proc:none", "-classpath", '"'+cp+'"', "-d", '"'+classes.as_posix()+'"'] + ['"'+x.as_posix()+'"' for x in files]), encoding="utf-8")
system = ["-D"+k+"="+str(v).replace("\\","/") for k, v in worker["testSystemProperties"].items()]
run = out / "java.args"
run.write_text("\n".join(['"'+x+'"' for x in system] + ["--add-opens=java.base/java.io=ALL-UNNAMED", "-classpath", '"'+cp+'"', "SafTestRun", "com.deepseekharness.app.ui.WebDownloadSaveTest", "com.deepseekharness.app.data.WorkspaceFileDestinationTest", "com.deepseekharness.app.backup.BackupDownloadsPublicationTest","com.deepseekharness.app.transfer.DownloadEngineBehaviorTest","com.deepseekharness.app.transfer.TransferPublisherBehaviorTest"]), encoding="utf-8")
commands = [(java.with_name("javac.exe"), args)]
if variant == "after":
    cache = root / "toolchains/current/gradle-user-home/caches/modules-2/files-2.1"
    lib = []
    for group, library, version in [
        ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.2.10"),
        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.10"),
        ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.2.10"),
        ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.2.10"),
        ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
        ("org.jetbrains", "annotations", "13.0"),
    ]:
        lib.append(next((cache / group / library / version).glob("*/*.jar")).as_posix())
    friend = next(Path(x).as_posix() for x in worker["classpath"] if "runtime_app_classes_jar" in x)
    kotlin = [src / "app/src/main/java/com/deepseekharness/app/transfer"/name for name in ["TransferPublisher.kt","DownloadEngine.kt"]]
    files += kotlin
    kargs = out / "kotlin.args"
    kargs.write_text("\n".join('"'+x+'"' for x in ["-classpath",";".join(lib),"org.jetbrains.kotlin.cli.jvm.K2JVMCompiler","-no-stdlib","-no-reflect","-module-name","app","-jvm-target","17","-Xfriend-paths="+friend,"-classpath",cp,"-d",classes.as_posix()]+[p.as_posix() for p in kotlin]),encoding="utf-8")
    commands.append((java,kargs))
commands.append((java,run))
record = {"variant": variant, "start": datetime.now(timezone.utc).isoformat(),
          "inputs": {str(f): hashlib.sha256(f.read_bytes()).hexdigest() for f in files}}
with (out / "console.log").open("w", encoding="utf-8") as log:
    for binary, argfile in commands:
        result = subprocess.run([str(binary), "@"+str(argfile)], cwd=worker["workingDirectory"], stdout=log, stderr=subprocess.STDOUT)
        if result.returncode: break
record.update(end=datetime.now(timezone.utc).isoformat(), exitCode=result.returncode)
(out / "receipt.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
print((out / "console.log").read_text(encoding="utf-8", errors="replace")[-3500:])
raise SystemExit(result.returncode)
