#!/usr/bin/env python3
"""Index every DEX, preserve decompiler evidence, and compare clipboard helpers between APKs."""
import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
FRAMEWORK_READ = "Landroid/content/ClipboardManager;->getPrimaryClip()Landroid/content/ClipData;"


def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, text=True, **kwargs)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n")


def owner(descriptor):
    return descriptor.split("->", 1)[0]


def compare(previous, current):
    before = {h["method"]: h for h in previous["readHelpers"]}
    after = {h["method"]: h for h in current["readHelpers"]}
    old_calls = {c["caller"] + " => " + c["target"] for c in previous["frameworkReads"]}
    new_calls = {c["caller"] + " => " + c["target"] for c in current["frameworkReads"]}
    return {
        "baselineApkSha256": previous["apkSha256"],
        "currentApkSha256": current["apkSha256"],
        "baselineVersion": previous["identity"],
        "currentVersion": current["identity"],
        "addedReadHelpers": sorted(after.keys() - before.keys()),
        "removedReadHelpers": sorted(before.keys() - after.keys()),
        "changedReadHelperBodies": sorted(k for k in before.keys() & after.keys()
                                           if before[k]["smaliBodySha256"] != after[k]["smaliBodySha256"]),
        "addedFrameworkReadCallers": sorted(new_calls - old_calls),
        "removedFrameworkReadCallers": sorted(old_calls - new_calls),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="Dedicated analysis directory outside the checkout")
    parser.add_argument("--tool-home", type=Path, default=ROOT, help="Directory containing installed lib/*.jar")
    parser.add_argument("--baseline", type=Path, help="A previous summary.json to compare")
    args = parser.parse_args()
    apk, output, home = args.apk.resolve(), args.output.resolve(), args.tool_home.resolve()
    if output.is_relative_to(ROOT.parent.parent):
        parser.error("write generated analysis outside the repository checkout")
    output.mkdir(parents=True, exist_ok=True)
    jars = sorted((home / "lib").glob("*.jar"))
    if not jars:
        parser.error("install the pinned tools with the installDecompilers Gradle task first")
    java_home = Path(os.environ["JAVA_HOME"])
    sdk = Path(os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools" / "37.0.0"
    signatures = run([java_home / "bin" / "java", "-jar", build_tools / "lib" / "apksigner.jar",
                      "verify", "--print-certs", apk], capture_output=True).stdout
    badging = run([build_tools / "aapt2", "dump", "badging", apk], capture_output=True).stdout
    (output / "apk-signature.txt").write_text(signatures)
    (output / "apk-badging.txt").write_text(badging)
    match = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging, re.M)
    if not match:
        raise RuntimeError("could not establish APK identity from aapt2")
    identity = dict(zip(("package", "versionCode", "versionName"), match.groups()))
    classes_dir = output / "tool-classes"
    classes_dir.mkdir(exist_ok=True)
    classpath = os.pathsep.join(map(str, jars))
    run([java_home / "bin" / "javac", "-proc:none", "-cp", classpath, "-d", classes_dir, ROOT / "ClipboardIndex.java"])
    index_path = output / "clipboard-index.json"
    run([java_home / "bin" / "java", "-Xmx2g", "-cp", classpath + os.pathsep + str(classes_dir),
         "ClipboardIndex", apk, index_path])
    index = json.loads(index_path.read_text())
    marker_owners = defaultdict(set)
    for item in index["strings"]:
        marker_owners[owner(item["method"])].add(item["value"])
    checker_owners = {name for name, values in marker_owners.items()
                      if {"clipboard.read_enable", "ClipboardChecker"} <= values}
    checker_owners.update(name for name in index["clipboardNamedClasses"]
                          if name.endswith("/ui/clipboard/ClipboardChecker;"))
    shapes_by_owner = defaultdict(list)
    for shape in index["readHelperShapes"]:
        shapes_by_owner[owner(shape["method"])].append(shape)
    framework_reads = [c for c in index["calls"] if c["target"] == FRAMEWORK_READ]
    read_methods = {c["caller"] for c in framework_reads}
    helpers = [shape for cls in sorted(checker_owners) if len(shapes_by_owner[cls]) == 1
               for shape in shapes_by_owner[cls] if shape["method"] in read_methods]
    groups = defaultdict(set)
    for name, dex in index["clipboardNamedClasses"].items():
        if "/ui/clipboard/" in name or "/gripper/clipboard/" in name or name == "Lcom/bilibili/droid/ClipboardHelper;":
            groups[dex].add(name)
    for shape in helpers:
        groups[shape["dex"]].add(owner(shape["method"]))
    for item in index["strings"]:
        if item["value"] == "start check clipboard from launcher":
            groups[item["dex"]].add(owner(item["method"]))
    # The two command-line tools depend on incompatible JCommander versions.
    def decompiler(which, command):
        excluded = "jcommander-1.64.jar" if which == "jadx" else "jcommander-2.0.jar"
        cp = os.pathsep.join(str(p) for p in jars if p.name != excluded)
        main_class = "jadx.cli.JadxCLI" if which == "jadx" else "com.android.tools.smali.baksmali.Main"
        env = os.environ.copy()
        env["JADX_CONFIG_DIR"] = str(output / "jadx-config")
        env["JADX_CACHE_DIR"] = str(output / "jadx-cache")
        return run([java_home / "bin" / "java", "-Xmx2g", "-cp", cp, main_class, *command], env=env)
    with zipfile.ZipFile(apk) as archive:
        for dex, selected in sorted(groups.items()):
            dex_path = output / dex
            dex_path.write_bytes(archive.read(dex))
            decompiler("baksmali", ["disassemble", "--classes", ",".join(sorted(selected)), "--code-offsets", "-j", "3",
                                    "-o", output / "smali" / dex, dex_path])
    for shape in helpers:
        descriptor = shape["method"]
        cls = owner(descriptor)
        path = output / "smali" / shape["dex"] / (cls[1:-1] + ".smali")
        code = path.read_text()
        tail = descriptor.split("->", 1)[1]
        blocks = re.findall(r"^\.method [^\n]*\n.*?^\.end method", code, re.M | re.S)
        body = next(block for block in blocks if block.splitlines()[0].endswith(" " + tail))
        # Ignore source line/debug locations while retaining actual bytecode instructions and branch targets.
        normalized = "\n".join(line.strip() for line in body.splitlines()
                               if line.strip() and not line.lstrip().startswith(("#", ".line", ".local", ".end local", ".restart local", ".source")))
        shape["smaliBodySha256"] = hashlib.sha256(normalized.encode()).hexdigest()
        shape["smaliPath"] = str(path.relative_to(output))
        decompiler("jadx", ["--no-res", "--single-class", cls[1:-1].replace("/", "."), "-j", "3",
                            "-d", output / "jadx", output / shape["dex"]])
    helper_descriptors = {h["method"] for h in helpers}
    summary = {
        "schemaVersion": 1,
        "identity": identity,
        "apkSha256": sha256(apk),
        "certificateSha256": re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]+)", signatures),
        "tools": {"jadx": "1.5.3", "baksmali": "3.0.9"},
        "dexCount": len(index["dex"]),
        "classCount": index["classCount"],
        "methodCount": index["methodCount"],
        "readHelpers": helpers,
        "helperCallers": [c for c in index["calls"] if c["target"] in helper_descriptors],
        "frameworkReads": framework_reads,
        "checkerMarkers": {name: sorted(marker_owners[name]) for name in sorted(checker_owners)},
        "candidateStatus": "unique" if len(helpers) == 1 else "missing" if not helpers else "ambiguous",
        "runtimeValidated": False,
    }
    write_json(output / "summary.json", summary)
    if args.baseline:
        write_json(output / "diff.json", compare(json.loads(args.baseline.read_text()), summary))
    print("APK:", identity, "DEX:", summary["dexCount"], "helper candidates:", summary["candidateStatus"])
    print("Report:", output / "summary.json")


if __name__ == "__main__":
    main()
