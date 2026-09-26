#!/usr/bin/env python3
"""Build the Liquid Glass LSPosed module on Windows.

Same pipeline as the repo's build.sh (javac -> d8 -> aapt2 -> zipalign ->
apksigner), but driven from Python so that Windows path/quoting rules and the
MSYS path translation in Git Bash cannot mangle the classpath or the tool
arguments.

Usage:
    python build-win.py [--proj DIR] [--tools DIR]
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import zipfile

JAVA_HOME_CANDIDATES = [
    r"C:\Program Files\Java\jdk-17",
    r"C:\Program Files\Java\jdk-21",
    r"C:\Program Files\Java\jdk-22",
]


def find_jdk():
    for home in JAVA_HOME_CANDIDATES:
        if os.path.isfile(os.path.join(home, "bin", "javac.exe")):
            return home
    raise SystemExit("no JDK found in %s" % JAVA_HOME_CANDIDATES)


def run(cmd, **kw):
    print("  $ " + " ".join(os.path.basename(c) if c.endswith(".exe") else c
                            for c in cmd[:3]) + (" ..." if len(cmd) > 3 else ""))
    res = subprocess.run(cmd, capture_output=True, errors="replace", **kw)
    if res.returncode != 0:
        print(res.stdout)
        print(res.stderr, file=sys.stderr)
        raise SystemExit("command failed (%d): %s" % (res.returncode, cmd[0]))
    if res.stdout.strip():
        for line in res.stdout.strip().splitlines()[-12:]:
            print("    " + line)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--proj", default=os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    ap.add_argument("--tools", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools"))
    args = ap.parse_args()
    proj = os.path.abspath(args.proj)
    tools = os.path.abspath(args.tools)

    jdk = find_jdk()
    java = os.path.join(jdk, "bin", "java.exe")
    javac = os.path.join(jdk, "bin", "javac.exe")
    keytool = os.path.join(jdk, "bin", "keytool.exe")

    platform = os.path.join(tools, "plat", "android-34", "android.jar")
    aapt2 = os.path.join(tools, "bt", "android-14", "aapt2.exe")
    zipalign = os.path.join(tools, "bt", "android-14", "zipalign.exe")
    d8jar = os.path.join(tools, "bt", "android-14", "lib", "d8.jar")
    signer = os.path.join(tools, "bt", "android-14", "lib", "apksigner.jar")
    xapi = os.path.join(tools, "xapi", "classes.jar")

    for f in (platform, aapt2, zipalign, d8jar, signer, xapi):
        if not os.path.exists(f):
            raise SystemExit("missing toolchain piece: %s" % f)

    with open(os.path.join(proj, "AndroidManifest.xml"), encoding="utf-8") as fh:
        version = re.search(r'android:versionName="([^"]+)"', fh.read()).group(1)

    out = os.path.join(proj, "build")
    if os.path.isdir(out):
        shutil.rmtree(out)
    classes = os.path.join(out, "classes")
    dex = os.path.join(out, "dex")
    os.makedirs(classes)
    os.makedirs(dex)

    src_root = os.path.join(proj, "src")
    sources = []
    for root, _, files in os.walk(src_root):
        sources += [os.path.join(root, f) for f in files if f.endswith(".java")]
    sources.sort()
    print("[1/7] javac (%d sources) with %s" % (len(sources), jdk))

    run([javac, "-encoding", "UTF-8", "--release", "11",
         "-Xlint:-options", "-nowarn",
         "-classpath", platform + os.pathsep + xapi,
         "-d", classes] + sources)

    print("[2/7] jar + d8")
    classes_jar = os.path.join(out, "classes.jar")
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(classes):
            for f in files:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, classes))
    run([java, "-cp", d8jar, "com.android.tools.r8.D8",
         "--release", "--lib", platform, "--min-api", "26",
         "--output", dex, classes_jar])

    print("[3/7] aapt2 compile/link")
    res_zip = os.path.join(out, "res.zip")
    base_apk = os.path.join(out, "base.apk")
    run([aapt2, "compile", "--dir", os.path.join(proj, "res"), "-o", res_zip])
    run([aapt2, "link", "-o", base_apk, "-I", platform,
         "--manifest", os.path.join(proj, "AndroidManifest.xml"), res_zip])

    print("[4/7] inject classes.dex + META-INF/xposed")
    unsigned = os.path.join(out, "unsigned.apk")
    shutil.copyfile(base_apk, unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dex, "classes.dex"), "classes.dex")
        meta = os.path.join(proj, "META-INF")
        for root, _, files in os.walk(meta):
            for f in sorted(files):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, proj))

    print("[5/7] zipalign")
    aligned = os.path.join(out, "aligned.apk")
    run([zipalign, "-f", "-p", "4", unsigned, aligned])

    print("[6/7] sign")
    keystore = os.path.join(proj, "debug.keystore")
    if not os.path.exists(keystore):
        run([keytool, "-genkeypair", "-keystore", keystore,
             "-storepass", "android", "-keypass", "android",
             "-alias", "androiddebugkey", "-keyalg", "RSA",
             "-validity", "10000",
             "-dname", "CN=Android Debug,O=Android,C=US"])
    final = os.path.join(proj, "LiquidGlass-v%s.apk" % version)
    run([java, "-cp", signer, "com.android.apksigner.ApkSignerTool", "sign",
         "--ks", keystore, "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--out", final, aligned])

    print("[7/7] verify")
    run([java, "-cp", signer, "com.android.apksigner.ApkSignerTool", "verify",
         "--print-certs", final])
    print("\nOK -> %s (%d bytes)" % (final, os.path.getsize(final)))


if __name__ == "__main__":
    main()
