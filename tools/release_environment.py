#!/usr/bin/env python3
"""Resolve existing local release tools without installing anything or reading secrets.

resolve_environment() returns safe overrides and a separately printable summary.
Inherited option values are never copied into either result. A missing/ambiguous
toolchain is a preflight error, never a request to bootstrap an SDK or new signer.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BUILD_TOOLS = "35.0.0"
PLATFORM = "android-36"
MIB = 1024 * 1024


def _properties(path):
    """Read only the nonsecret configuration keys relevant to this preflight."""
    allowed = {"sdk.dir", "forks", "testHeap", "org.gradle.jvmargs", "org.gradle.workers.max"}
    result = {}
    try:
        for line in path.read_text().splitlines():
            match = re.match(r"\s*([^#!\s:=]+)\s*[=:]\s*(.*)$", line)
            if match and match[1] in allowed:
                result[match[1]] = match[2]
    except FileNotFoundError:
        pass
    return result


def _path(raw, base=None):
    path = Path(raw).expanduser()
    return (path if path.is_absolute() else (base or Path.cwd()) / path).resolve()


def _executable(path):
    return path.is_file() and os.access(path, os.X_OK)


def _binary(directory, name):
    for suffix in ("", ".exe", ".bat"):
        candidate = directory / (name + suffix)
        if _executable(candidate):
            return candidate.resolve()
    return directory / name


def _memory():
    """Honor container memory limits; no dependency on a particular workspace."""
    total, available, sources = None, None, []
    try:
        values = dict(re.findall(r"^(MemTotal|MemAvailable):\s+(\d+) kB$",
                                 Path("/proc/meminfo").read_text(), re.M))
        total, available = int(values["MemTotal"]) * 1024, int(values["MemAvailable"]) * 1024
        sources.append("/proc/meminfo")
    except (OSError, KeyError, ValueError):
        try:
            total = os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
            sources.append("sysconf physical memory")
        except (OSError, ValueError, AttributeError):
            pass
    for limit_path, used_path in (("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current"),
                                  ("/sys/fs/cgroup/memory/memory.limit_in_bytes",
                                   "/sys/fs/cgroup/memory/memory.usage_in_bytes")):
        try:
            limit = int(Path(limit_path).read_text().strip())
            used = int(Path(used_path).read_text().strip())
            if 0 < limit < 2 ** 60:
                total = min(total, limit) if total else limit
                free = max(0, limit - used)
                available = min(available, free) if available is not None else free
                sources.append(limit_path)
        except (OSError, ValueError):
            pass
    return {"limitMiB": total // MIB if total is not None else None,
            "availableMiB": available // MIB if available is not None else None,
            "sources": sources}


def _version(path, arguments, environment):
    """Only return parsed version tokens, never arbitrary executable output."""
    try:
        result = subprocess.run([str(path), *arguments], env=environment, capture_output=True,
                                text=True, errors="replace", timeout=5, check=False)
        if result.returncode != 0:
            return None, "Version probe failed; check that the executable and its runtime libraries work."
        output = (result.stdout + result.stderr)[:8192]
        patterns = (r'(?:openjdk|java)\s+(?:version\s+)?"?([0-9][0-9A-Za-z_.+\-]*)',
                    r'javac\s+([0-9][0-9A-Za-z_.+\-]*)',
                    r'Android Asset Packaging Tool \(aapt\)\s+([0-9][0-9A-Za-z_.+\-:]*)',
                    r'D8\s+([0-9][0-9A-Za-z_.+\-]*)',
                    r'^\s*([0-9]+(?:\.[0-9]+)+)\s*$')
        for pattern in patterns:
            match = re.search(pattern, output, re.M)
            if match:
                return match[1], None
        return None, "Version probe returned an unrecognized version format."
    except (OSError, subprocess.TimeoutExpired):
        return None, "Version probe could not execute within five seconds."


def _base64_decode_probe(path, environment):
    """Check the exact signing-helper flag using public literal bytes only."""
    try:
        result = subprocess.run([str(path), "--decode"], input=b"Zg==", env=environment,
                                capture_output=True, timeout=5, check=False)
        return result.returncode == 0 and result.stdout == b"f"
    except (OSError, subprocess.TimeoutExpired):
        return False


def _profile(env, repo, summary, overrides):
    resources = summary["resources"] = _memory()
    limit = resources["limitMiB"]
    # Budget includes native/JIT/Gradle overhead: deliberately leave substantial
    # headroom rather than allocating every byte among Java maximum heaps.
    large = limit is not None and limit >= 8192
    defaults = {"forks": "2" if large else "1", "testHeap": "1024m" if large else "768m",
                "org.gradle.jvmargs": "-Xmx1536m" if large else "-Xmx1024m",
                "org.gradle.workers.max": "2"}
    project = _properties(repo / "gradle.properties")
    user_home = _path(env.get("GRADLE_USER_HOME", str(_path(env.get("HOME", str(Path.home()))) / ".gradle")))
    user = _properties(user_home / "gradle.properties")
    choices = summary["choices"]
    inherited_options = any(env.get(key) for key in ("GRADLE_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
                                                     "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"))
    system_options = []
    for key, adopted in defaults.items():
        env_key = "ORG_GRADLE_PROJECT_" + key
        explicit = env_key in env or key in user
        # These two properties exist as documented repository defaults. Other
        # project-level knobs are deliberate user choices and must be retained.
        if key in ("forks", "testHeap") and key in project:
            explicit = True
        if explicit:
            choices[key] = {"source": "existing environment" if env_key in env else
                            "existing user Gradle properties" if key in user else "existing project properties",
                            "action": "preserved; value not disclosed"}
        elif inherited_options:
            choices[key] = {"source": "inherited JVM/Gradle options", "action": "preserved; resource sizing not overridden"}
        else:
            choices[key] = {"source": "resource-aware local release default", "value": adopted,
                            "action": "adopted explicitly over repository default" if key in project else "adopted"}
            if key.startswith("org.gradle."):
                system_options.append("-D" + key + "=" + adopted)
            else:
                overrides[env_key] = adopted
    if system_options:
        # These are JVM system properties, not low-priority plain environment
        # properties. The canonical gradlew passes GRADLE_OPTS before the wrapper
        # main class; Gradle documents org.gradle.jvmargs here as the environment
        # route to daemon settings, above root gradle.properties defaults:
        # https://docs.gradle.org/current/userguide/build_environment.html
        overrides["GRADLE_OPTS"] = " ".join(system_options)
    if inherited_options:
        summary["warnings"].append("Existing JVM/Gradle options were preserved without disclosure. "
                                   "Confirm their daemon, worker and test-memory limits before building.")
    if limit is None:
        summary["warnings"].append("Memory limit could not be detected; the conservative one-fork profile is selected where no override exists.")
    elif limit < 3072:
        summary["errors"].append("Less than 3 GiB of memory is available to this worker. Use an existing local worker with at least 3 GiB "
                                 "or configure and validate smaller Gradle/test heaps explicitly; no remote build will be started.")
    if resources["availableMiB"] is not None and resources["availableMiB"] < 2048:
        summary["warnings"].append("Less than 2 GiB is currently free; stop unrelated local JVMs or work before the release gate.")


def resolve_environment(mode="build", env=None, repo=None):
    """Return {overrides, summary}; summary.ok must be true before work starts.

    build: full local SDK/JDK and bounded Gradle profile.
    import: existing Java/Python only; the signed-return bundle supplies verifier.
    sign: existing Java + keytool only; the split bundle supplies apksigner.jar.
    accept: existing Java and SDK verification tools; no compiler or build tools.
    verify: SDK package/signature/alignment tools plus the JDK for updater probes.
    No credentials, SDK licenses, network access, or dependency downloads occur.
    """
    if mode not in {"build", "import", "sign", "accept", "verify"}:
        raise ValueError("mode must be build, import, sign, accept, or verify")
    env = dict(os.environ if env is None else env)
    repo = _path(repo or ROOT)
    overrides = {}
    summary = {"mode": mode, "ok": False, "tools": {}, "choices": {}, "errors": [], "warnings": [],
               "requiredSdk": None if mode in {"sign", "import"} else {"buildTools": BUILD_TOOLS,
                   "platform": PLATFORM if mode == "build" else None}, "networkAccess": False}
    errors = summary["errors"]
    java_names = ("java",) if mode in {"accept", "import"} else ("java", "keytool") if mode == "sign" else ("java", "javac", "keytool")
    configured_java = env.get("JAVA_HOME")
    if configured_java:
        java_home, java_source = _path(configured_java), "JAVA_HOME"
    else:
        name = "java" if mode in {"sign", "accept", "import"} else "javac"
        resolved = shutil.which(name, path=env.get("PATH", ""))
        java_home = Path(resolved).resolve().parent.parent if resolved else None
        java_source = "PATH " + name
    if java_home is None:
        errors.append("No existing " + ("Java runtime" if mode in {"sign", "accept", "import"} else "JDK") +
                      " found. Set JAVA_HOME to the existing installation (JDK 17 or newer for build/verify).")
    else:
        summary["choices"]["JAVA_HOME"] = {"source": java_source, "path": str(java_home)}
        overrides["JAVA_HOME"] = str(java_home)
        overrides["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    required = {}
    # The canonical entry points are Bash/Python and the local packager uses zip.
    # Windows hosts must provide an existing compatible shell workspace; never
    # claim portability merely because a java.exe was found.
    host_names = ["python3"] if mode == "import" else ["bash", "python3", "base64", "awk", "mktemp", "rm", "dirname"] if mode == "sign" else ["bash", "python3", "git"]
    if mode == "build":
        host_names.extend(("zip", "find", "sed", "cat", "rm", "mkdir", "dirname"))
    for name in host_names:
        found = shutil.which(name, path=env.get("PATH", ""))
        if found:
            required[name] = Path(found).resolve()
        else:
            errors.append("Missing host command " + name + " on PATH. Use an existing compatible local Bash/Python release workspace.")
    if java_home:
        required.update({name: _binary(java_home / "bin", name) for name in java_names})
    if mode not in {"sign", "import"}:
        primary, secondary = env.get("ANDROID_HOME"), env.get("ANDROID_SDK_ROOT")
        if primary and secondary and _path(primary) != _path(secondary):
            errors.append("ANDROID_HOME and ANDROID_SDK_ROOT point to different SDKs; select one existing SDK explicitly.")
        sdk = _path(primary or secondary) if primary or secondary else None
        sdk_source = "ANDROID_HOME" if primary else "ANDROID_SDK_ROOT" if secondary else "PATH aapt2"
        if sdk is None:
            found = shutil.which("aapt2", path=env.get("PATH", ""))
            if found:
                candidate = Path(found).resolve()
                if candidate.parent.name == BUILD_TOOLS and candidate.parent.parent.name == "build-tools":
                    sdk = candidate.parent.parent.parent
        if sdk is None:
            errors.append("No existing pinned Android SDK found. Set ANDROID_HOME to an SDK containing build-tools/" +
                          BUILD_TOOLS + (" and platforms/" + PLATFORM if mode == "build" else "") + ".")
        else:
            summary["choices"]["ANDROID_HOME"] = {"source": sdk_source, "path": str(sdk)}
            overrides["ANDROID_HOME"] = str(sdk)
            local = _properties(repo / "local.properties").get("sdk.dir")
            # Java properties escapes most commonly used by SDK directories.
            if mode == "build" and local and _path(local.replace("\\:", ":").replace("\\\\", "\\"), repo) != sdk:
                errors.append("local.properties sdk.dir disagrees with the selected ANDROID_HOME. Reconcile this local configuration before building.")
            directory = sdk / "build-tools" / BUILD_TOOLS
            names = ("aapt2", "zipalign", "apksigner", "d8") if mode == "build" else ("aapt2", "zipalign", "apksigner")
            required.update({name: _binary(directory, name) for name in names})
            data = {"apksigner.jar": directory / "lib/apksigner.jar"}
            if mode == "build":
                data.update({"d8.jar": directory / "lib/d8.jar", "android.jar": sdk / "platforms" / PLATFORM / "android.jar"})
            for name, path in data.items():
                summary["tools"][name] = {"path": str(path), "exists": path.is_file()}
                if not path.is_file():
                    errors.append("Missing existing SDK component: " + str(path))
    runtime = env | overrides
    # JVM launchers may print arbitrary inherited options. Probes only parse known
    # version tokens; neither raw output nor option values enter the summary.
    probes = {"java": ["-version"], "javac": ["-version"], "aapt2": ["version"],
              "d8": ["--version"], "apksigner": ["version"]}
    for name, path in required.items():
        entry = summary["tools"][name] = {"path": str(path), "exists": _executable(path)}
        if not entry["exists"]:
            errors.append("Missing executable " + name + ": " + str(path) + ". Select an existing complete toolchain; preflight installs nothing.")
            continue
        entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        if name == "base64" and mode == "sign":
            entry["decodeFlagVerified"] = _base64_decode_probe(path, runtime)
            if not entry["decodeFlagVerified"]:
                errors.append("base64 --decode is unsupported or failed on this existing host. "
                              "Select an existing compatible base64 on PATH before signing; no signing material was read.")
        if name in probes:
            version, issue = _version(path, probes[name], runtime)
            entry["version"] = version
            if issue:
                errors.append(name + ": " + issue)
            elif name in {"java", "javac"} and int(version.split(".")[0]) < 17:
                errors.append(name + " is older than 17. Select the existing supported JDK 17 or newer.")
    if mode == "build":
        _profile(env, repo, summary, overrides)
    java_version = summary["tools"].get("java", {}).get("version")
    compiler_version = summary["tools"].get("javac", {}).get("version")
    if java_version and compiler_version and java_version.split(".")[0] != compiler_version.split(".")[0]:
        errors.append("java and javac are from different major JDK versions. Select one complete existing JAVA_HOME.")
    summary["ok"] = not errors
    return {"overrides": overrides, "summary": summary}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("build", "import", "sign", "accept", "verify"), default="build")
    args = parser.parse_args()
    result = resolve_environment(args.mode)
    print(json.dumps(result["summary"], indent=2, sort_keys=True))
    return 0 if result["summary"]["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
