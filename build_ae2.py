"""Build the plugin-ae2 jar with JDK 25 (--release 17), because the modpack's
magic-modded AE2 jar carries class files of version 65 (Java 21) that JDK 17
cannot read, while the runtime is Forge 1.20.1 on Java 17."""
import glob
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_plugin as bp

JDK25 = r"D:\Program Files\Java\jdk-25\bin"


def build_ae2():
    mod = os.path.join(bp.ROOT, "_patch", "plugin-ae2")
    cp = bp.classpath(mod)
    src = os.path.join(mod, "src")
    sources = sorted(glob.glob(os.path.join(src, "**", "*.java"), recursive=True))
    classes = os.path.join(mod, "build", "classes")
    if os.path.isdir(classes):
        shutil.rmtree(classes)
    os.makedirs(classes, exist_ok=True)
    argfile = os.path.join(mod, "build", "sources.txt")
    with open(argfile, "w", encoding="ascii") as fh:
        for s in sources:
            fh.write(os.path.relpath(s, mod).replace(os.sep, "/") + "\n")
    javac = os.path.join(JDK25, "javac.exe")
    args = [javac, "--release", "17", "-encoding", "UTF-8", "-nowarn",
            "-cp", ";".join(cp), "-d", "build/classes", "@build/sources.txt"]
    env = dict(os.environ, JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US")
    rc = subprocess.call(args, cwd=mod, env=env)
    print("javac exit:", rc, "sources:", len(sources), "cp:", len(cp))
    if rc != 0:
        return rc
    res = os.path.join(src, "main", "resources")
    if os.path.isdir(res):
        shutil.copytree(res, classes, dirs_exist_ok=True)
    out = os.path.join(mod, "build", "numen_gto-1.0.0.jar")
    if os.path.exists(out):
        os.remove(out)
    rc = subprocess.call([os.path.join(JDK25, "jar.exe"), "--create", "--file", out, "-C", classes, "."], cwd=mod)
    print("jar exit:", rc, "->", out, os.path.getsize(out) if os.path.exists(out) else "-")
    return rc


if __name__ == "__main__":
    sys.exit(build_ae2())
