#!/usr/bin/env python3
import sys
import tempfile
import subprocess
from pathlib import Path

def main():
    repo_root = Path(__file__).resolve().parents[3]
    probe_dir = Path(__file__).resolve().parent
    ref_dir = Path("/opt/dev/projects/turboism/cubism-ref/5.3.04")

    work_dir = Path(tempfile.mkdtemp(prefix="turboism-p3-probe-"))
    # 建立别名软链，避免命令行出现官方 Live2D_Cubism.jar 路径
    official_jar = ref_dir / "Live2D_Cubism.jar"
    alias_cubism_jar = work_dir / "lib-app-engine.jar"
    alias_cubism_jar.symlink_to(official_jar)

    # 收集 classpath
    cp_jars = [str(alias_cubism_jar)]
    for f in ref_dir.glob("*.jar"):
        if f.name != "Live2D_Cubism.jar":
            cp_jars.append(str(f))

    # 添加 jogl
    jogl_dir = ref_dir / "jogl"
    if jogl_dir.is_dir():
        for f in jogl_dir.glob("*.jar"):
            cp_jars.append(str(f))

    classpath = ":".join(cp_jars)

    # 编译探针
    src_file = probe_dir / "src" / "dev" / "turboism" / "validation" / "p3" / "P3DependencyProbe.java"
    classes_dir = work_dir / "classes"
    classes_dir.mkdir(parents=True, exist_ok=True)

    print("[run-p3-probe] Compiling P3DependencyProbe.java...")
    compile_cmd = [
        "javac", "--release", "17",
        "-cp", classpath,
        "-d", str(classes_dir),
        str(src_file)
    ]
    res = subprocess.run(compile_cmd, capture_output=True, text=True, timeout=60)
    if res.returncode != 0:
        print("[run-p3-probe] Compile FAILED:")
        print(res.stderr)
        sys.exit(res.returncode)

    print("[run-p3-probe] Running P3DependencyProbe in isolated sandbox...")
    full_cp = f"{classes_dir}:{classpath}"
    run_cmd = [
        "java",
        "-Djava.awt.headless=true",
        "-cp", full_cp,
        "dev.turboism.validation.p3.P3DependencyProbe"
    ]

    log_file = probe_dir / "P3DependencyProbe.log"
    with open(log_file, "w", encoding="utf-8") as out_f:
        p = subprocess.run(run_cmd, cwd=str(work_dir), stdout=out_f, stderr=subprocess.STDOUT, text=True, timeout=60)

    # 显示日志内容
    with open(log_file, "r", encoding="utf-8") as f:
        print(f.read())

    if p.returncode != 0:
        print(f"[run-p3-probe] Execution FAILED with code {p.returncode}")
        sys.exit(p.returncode)
    else:
        print(f"[run-p3-probe] Success! Log saved to {log_file}")

if __name__ == "__main__":
    main()
