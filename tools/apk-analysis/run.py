#!/usr/bin/env python3
"""Run pinned decompilers with their separate JCommander versions."""
import os
from pathlib import Path
import sys

root = Path(__file__).resolve().parent
commands = {
    "jadx": ("jadx.cli.JadxCLI", "jcommander-1.64.jar"),
    "baksmali": ("com.android.tools.smali.baksmali.Main", "jcommander-2.0.jar"),
}
if len(sys.argv) < 2 or sys.argv[1] not in commands:
    raise SystemExit("usage: python3 run.py {jadx|baksmali} [arguments...]")
main, excluded = commands[sys.argv[1]]
jars = sorted(p for p in (root / "lib").glob("*.jar") if p.name != excluded)
if not jars:
    raise SystemExit("Run installDecompilers before using this launcher.")
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
if sys.argv[1] == "jadx":
    for name, subdir in (("JADX_CONFIG_DIR", "config"), ("JADX_CACHE_DIR", "cache")):
        os.environ.setdefault(name, str(root / subdir))
os.execv(java, [java, "-Xmx2g", "-cp", os.pathsep.join(map(str, jars)), main, *sys.argv[2:]])
