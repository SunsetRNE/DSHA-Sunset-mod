#!/usr/bin/env python3
"""主机回归入口：执行 Rust host-JVM 源码发现及锁定编译器准备负控。"""
from pathlib import Path
import runpy


if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parent / "rust/test_host_jvm_sources.py"),
                   run_name="__main__")
