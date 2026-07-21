#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

MIN_PYTHON = (3, 9)
ROOT = Path(__file__).resolve().parents[1]
APPS_DIR = ROOT / "apps"
APPS_CONFIG = ROOT / "apps-config.json"
LOCAL_CONFIG = ROOT / ".app.local.properties"
BASE_PACKAGE = "jp.oist.abcvlib"
RECOMMENDED_TEMPLATES = ("basicAssembler", "basicSubscriberPython")
IGNORED_COPY_DIRS = {"build", ".gradle", ".kotlin", ".idea", ".settings"}
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class AppCliError(Exception):
    pass


@dataclass(frozen=True)
class Target:
    app: str
    package_name: str
    activity: str


def require_python() -> None:
    if sys.version_info < MIN_PYTHON:
        version = ".".join(str(part) for part in MIN_PYTHON)
        current = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
        raise AppCliError(
            f"Python {version} or newer is required; found {current}. See docs/demo-app-cloning.md."
        )


def app_display_name(app_name: str) -> str:
    words = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?=[A-Z]|$)|\d+", app_name)
    return " ".join(words) if words else app_name


def app_package(app_name: str) -> str:
    return f"{BASE_PACKAGE}.{app_name}"


def validate_app_name(app_name: str) -> None:
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", app_name):
        raise AppCliError(
            "App name must start with a letter and contain only letters, numbers, or underscores."
        )


def read_apps_config() -> dict:
    with APPS_CONFIG.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data.get("apps"), list):
        raise AppCliError("apps-config.json must contain an apps list.")
    return data


def write_apps_config(data: dict) -> None:
    with APPS_CONFIG.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")


def known_apps() -> list[str]:
    return list(read_apps_config()["apps"])


def template_apps() -> list[str]:
    templates = [
        app_name
        for app_name in known_apps()
        if (APPS_DIR / app_name).is_dir()
        and (APPS_DIR / app_name / "build.gradle.kts").is_file()
    ]
    recommended = [name for name in RECOMMENDED_TEMPLATES if name in templates]
    remaining = [name for name in templates if name not in recommended]
    return recommended + remaining


def load_properties(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def write_target(target: Target) -> None:
    LOCAL_CONFIG.write_text(
        "\n".join(
            [
                "# Local app CLI target. This file is intentionally ignored by Git.",
                f"app={target.app}",
                f"package={target.package_name}",
                f"activity={target.activity}",
                "",
            ]
        ),
        encoding="utf-8",
    )


def load_target(app_override: str | None = None) -> Target:
    app = app_override
    props = load_properties(LOCAL_CONFIG)
    if not app:
        app = props.get("app")
    if not app:
        app_list = ", ".join(known_apps())
        raise AppCliError(
            "No target app selected. Run './app target <app>' or pass '--app <app>'.\n"
            f"Known apps: {app_list}"
        )
    validate_app_exists(app)
    package_name = props.get("package") if props.get("app") == app else None
    activity = props.get("activity") if props.get("app") == app else None
    if not package_name or not activity:
        package_name, activity = resolve_manifest_target(app)
    return Target(app=app, package_name=package_name, activity=activity)


def validate_app_exists(app_name: str) -> None:
    if app_name not in known_apps() or not (APPS_DIR / app_name).is_dir():
        raise AppCliError(f"Unknown app '{app_name}'. Run './app target --list' to see apps.")


def template_package(template: str) -> str:
    build_file = APPS_DIR / template / "build.gradle.kts"
    text = build_file.read_text(encoding="utf-8")
    match = re.search(r'namespace\s*=\s*"([^"]+)"', text)
    if not match:
        raise AppCliError(f"Unable to find namespace in {build_file}.")
    return match.group(1)


def package_path(package_name: str) -> Path:
    return Path(*package_name.split("."))


def copy_ignore(_: str, names: list[str]) -> set[str]:
    return {name for name in names if name in IGNORED_COPY_DIRS}


def replace_in_text_files(root: Path, replacements: dict[str, str]) -> None:
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        updated = text
        for old, new in replacements.items():
            updated = updated.replace(old, new)
        if updated != text:
            path.write_text(updated, encoding="utf-8")


def move_package_dir(app_dir: Path, old_package: str, new_package: str) -> None:
    source_root = app_dir / "src" / "main" / "java"
    old_dir = source_root / package_path(old_package)
    new_dir = source_root / package_path(new_package)
    if not old_dir.exists():
        return
    if new_dir.exists():
        raise AppCliError(f"Generated package directory already exists: {new_dir}")
    new_dir.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(old_dir), str(new_dir))

    current = old_dir.parent
    while current != source_root and current.exists():
        try:
            current.rmdir()
        except OSError:
            break
        current = current.parent


def update_app_name(app_dir: Path, display_name: str) -> None:
    strings_file = app_dir / "src" / "main" / "res" / "values" / "strings.xml"
    if not strings_file.exists():
        return
    tree = ET.parse(strings_file)
    root = tree.getroot()
    for child in root.findall("string"):
        if child.attrib.get("name") == "app_name":
            child.text = display_name
            tree.write(strings_file, encoding="utf-8", xml_declaration=False)
            return


def resolve_manifest_target(app_name: str) -> tuple[str, str]:
    app_dir = APPS_DIR / app_name
    build_file = app_dir / "build.gradle.kts"
    manifest_file = app_dir / "src" / "main" / "AndroidManifest.xml"
    package_name = app_package(app_name)
    if build_file.exists():
        text = build_file.read_text(encoding="utf-8")
        match = re.search(r'namespace\s*=\s*"([^"]+)"', text)
        if match:
            package_name = match.group(1)

    activity = ".MainActivity"
    if manifest_file.exists():
        tree = ET.parse(manifest_file)
        root = tree.getroot()
        application = root.find("application")
        if application is not None:
            for activity_node in application.findall("activity"):
                name = activity_node.attrib.get(f"{ANDROID_NS}name")
                if name:
                    activity = name
                    break
    if activity and not activity.startswith(".") and "." not in activity:
        activity = f".{activity}"
    return package_name, activity


def gradle_cmd() -> list[str]:
    if os.name == "nt":
        return [str(ROOT / "gradlew.bat")]
    return [str(ROOT / "gradlew")]


def adb_cmd() -> str:
    candidates: list[tuple[str, Path]] = []
    diagnostics: list[str] = []
    if os.environ.get("ADB"):
        candidates.append(("ADB", Path(os.environ["ADB"])))
    else:
        diagnostics.append("ADB is not set.")

    exe = "adb.exe" if os.name == "nt" else "adb"
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(key)
        if value:
            candidates.append((f"{key}/platform-tools/{exe}", Path(value) / "platform-tools" / exe))
        else:
            diagnostics.append(f"{key} is not set.")

    found = shutil.which("adb")
    if found:
        candidates.append(("PATH", Path(found)))
    else:
        diagnostics.append("adb was not found on PATH.")

    for label, candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
        if candidate.exists():
            diagnostics.append(f"{label} points to {candidate}, but it is not executable.")
        else:
            diagnostics.append(f"{label} expected {candidate}, but that file does not exist.")

    raise AppCliError(
        "Unable to locate executable adb.\n"
        + "\n".join(f"- {message}" for message in diagnostics)
        + "\nSee docs/troubleshooting.md#adb-is-not-found."
    )


def run_command(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def run_install_command(adb: str, apk: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [adb, "install", "-r", "-d", str(apk)],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def print_process_output(result: subprocess.CompletedProcess[str]) -> None:
    if result.stdout:
        print(result.stdout, end="")
    if result.stderr:
        print(result.stderr, end="", file=sys.stderr)


def prompt_reinstall_after_signature_mismatch(target: Target) -> bool:
    print(
        "\nThe installed app has a different signing certificate.",
        file=sys.stderr,
    )
    print(
        f"Uninstalling {target.package_name} will remove that app's local data.",
        file=sys.stderr,
    )
    if not sys.stdin.isatty():
        print(
            "Rerun this command in an interactive terminal to uninstall and retry.",
            file=sys.stderr,
        )
        return False
    answer = input(f"Uninstall {target.package_name} and retry install? [y/N]: ").strip().lower()
    return answer in {"y", "yes"}


def debug_apk(app_name: str) -> Path:
    return APPS_DIR / app_name / "build" / "outputs" / "apk" / "debug" / f"{app_name}-debug.apk"


def interactive_template_choice() -> str:
    options = template_apps()
    if not options:
        raise AppCliError("No template apps found in apps-config.json.")
    print("Template:")
    recommended = set(RECOMMENDED_TEMPLATES)
    max_name_length = max(len(option) for option in options)
    for index, option in enumerate(options, start=1):
        suffix = " (recommended)" if option in recommended else ""
        print(f"  {index}. {option.ljust(max_name_length)}{suffix}")
    while True:
        value = input("Choose a number: ").strip()
        if value.isdigit():
            index = int(value)
            if 1 <= index <= len(options):
                return options[index - 1]
        print("Invalid choice.")


def interactive_app_choice() -> str:
    options = known_apps()
    if not options:
        raise AppCliError("No apps found in apps-config.json.")
    current = load_properties(LOCAL_CONFIG).get("app")
    print("Target app:")
    max_name_length = max(len(option) for option in options)
    for index, option in enumerate(options, start=1):
        suffix = " (current)" if option == current else ""
        print(f"  {index}. {option.ljust(max_name_length)}{suffix}")
    while True:
        prompt = "Choose a number"
        if current:
            prompt += f" [Enter for {current}]"
        value = input(f"{prompt}: ").strip()
        if not value and current:
            return current
        if value.isdigit():
            index = int(value)
            if 1 <= index <= len(options):
                return options[index - 1]
        print("Invalid choice.")


def format_templates(templates: list[str]) -> str:
    return ", ".join(templates)


def prompt_new_app(args: argparse.Namespace) -> tuple[str, str]:
    name = args.name
    template = args.template
    if not name:
        name = input("New app name: ").strip()
    if not template:
        template = interactive_template_choice()
    return name, template


def create_app(args: argparse.Namespace) -> None:
    app_name, template = prompt_new_app(args)
    validate_app_name(app_name)
    templates = template_apps()
    if template not in templates:
        raise AppCliError(
            f"Unsupported template '{template}'. Choose one of: {format_templates(templates)}"
        )

    app_dir = APPS_DIR / app_name
    template_dir = APPS_DIR / template
    if app_dir.exists():
        raise AppCliError(f"App directory already exists: {app_dir}")
    if not template_dir.exists():
        raise AppCliError(f"Template app does not exist: {template_dir}")

    data = read_apps_config()
    if app_name in data["apps"]:
        raise AppCliError(f"apps-config.json already includes '{app_name}'.")

    old_package = template_package(template)
    new_package = app_package(app_name)

    if args.dry_run:
        print(f"Would create apps/{app_name} from {template}.")
        print(f"Would set package to {new_package}.")
        print("Would update apps-config.json and .app.local.properties.")
        return

    shutil.copytree(template_dir, app_dir, ignore=copy_ignore)
    move_package_dir(app_dir, old_package, new_package)
    replace_in_text_files(
        app_dir,
        {
            old_package: new_package,
            template: app_name,
            app_display_name(template): app_display_name(app_name),
        },
    )
    update_app_name(app_dir, app_display_name(app_name))

    data["apps"].append(app_name)
    write_apps_config(data)
    package_name, activity = resolve_manifest_target(app_name)
    target = Target(app=app_name, package_name=package_name, activity=activity)
    write_target(target)

    print(f"Created apps/{app_name} from {template}.")
    print(f"Target app set to {app_name}.")
    print("Next:")
    print("  ./app run")
    print("  ./app logcat")


def set_target(args: argparse.Namespace) -> None:
    if args.list:
        for app_name in known_apps():
            print(app_name)
        return
    if args.select:
        args.app = interactive_app_choice()
    if not args.app:
        target = load_target(None)
        print(f"Target app: {target.app}")
        print(f"Package: {target.package_name}")
        print(f"Activity: {target.activity}")
        return
    validate_app_name(args.app)
    validate_app_exists(args.app)
    package_name, activity = resolve_manifest_target(args.app)
    write_target(Target(args.app, package_name, activity))
    print(f"Target app set to {args.app}.")


def build_app(args: argparse.Namespace) -> None:
    target = load_target(args.app)
    run_command(gradle_cmd() + [f":{target.app}:assembleDebug"])


def install_app(args: argparse.Namespace) -> None:
    target = load_target(args.app)
    apk = debug_apk(target.app)
    if not apk.exists() or getattr(args, "build", False):
        run_command(gradle_cmd() + [f":{target.app}:assembleDebug"])
    adb = adb_cmd()
    result = run_install_command(adb, apk)
    if result.returncode == 0:
        print_process_output(result)
        return

    combined_output = f"{result.stdout}\n{result.stderr}"
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in combined_output:
        print_process_output(result)
        raise subprocess.CalledProcessError(result.returncode, result.args)

    print_process_output(result)
    if not prompt_reinstall_after_signature_mismatch(target):
        raise subprocess.CalledProcessError(result.returncode, result.args)

    run_command([adb, "uninstall", target.package_name])
    retry_result = run_install_command(adb, apk)
    print_process_output(retry_result)
    if retry_result.returncode != 0:
        raise subprocess.CalledProcessError(retry_result.returncode, retry_result.args)


def launch_app(args: argparse.Namespace) -> None:
    target = load_target(args.app)
    run_command([adb_cmd(), "shell", "am", "start", "-n", f"{target.package_name}/{target.activity}"])


def run_app(args: argparse.Namespace) -> None:
    build_app(args)
    install_app(args)
    launch_app(args)


def logcat_app(args: argparse.Namespace) -> None:
    target = load_target(args.app)
    adb = adb_cmd()
    if args.clear:
        run_command([adb, "logcat", "-c"])

    pid_result = subprocess.run(
        [adb, "shell", "pidof", target.package_name],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    pid = pid_result.stdout.strip().split()
    if pid:
        run_command([adb, "logcat", "--pid", pid[0]])
        return

    filters = [
        "AndroidRuntime:E",
        "MainActivity:D",
        "abcvlib:D",
        "serial:D",
        "UsbSerial:D",
        "*:S",
    ]
    run_command([adb, "logcat", *filters])


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="app",
        description="Create, target, build, install, launch, and inspect Android app modules.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    new_parser = subparsers.add_parser(
        "new",
        help="Create a new app from a supported template.",
        description=(
            "Create a new app module by copying an existing app from apps-config.json. "
            "The command rewrites package/namespace references, appends the new module to "
            "apps-config.json, and makes it the local target in .app.local.properties."
        ),
    )
    new_parser.add_argument("--name", help="New app module name.")
    new_parser.add_argument("--template", help="Existing app module to copy as the template.")
    new_parser.add_argument("--dry-run", action="store_true", help="Validate inputs without creating files.")
    new_parser.set_defaults(func=create_app)

    target_parser = subparsers.add_parser(
        "target",
        help="Show or set the target app.",
        description=(
            "Show or set the local target app used by build, install, launch, run, and logcat. "
            "The selected target is stored in .app.local.properties, which is ignored by Git."
        ),
    )
    target_parser.add_argument("app", nargs="?", help="App to target.")
    target_parser.add_argument("--list", action="store_true", help="List known apps.")
    target_parser.add_argument("--select", action="store_true", help="Choose the target app from an interactive list.")
    target_parser.set_defaults(func=set_target)

    command_descriptions = {
        "build": (
            "Build the target app's debug APK with Gradle by running :<app>:assembleDebug "
            "from the repository root. The target app is shown with './app target', set with "
            "'./app target <app>', or overridden once with '--app <app>'."
        ),
        "install": (
            "Install the target app's debug APK on a connected Android device with adb. "
            "If the APK does not exist, the command builds it first. Use --build to force a rebuild. "
            "The target app is shown with './app target', set with './app target <app>', or "
            "overridden once with '--app <app>'."
        ),
        "launch": (
            "Launch the target app on a connected Android device with adb shell am start. "
            "The package and activity are read from the app's Gradle namespace and AndroidManifest.xml. "
            "The target app is shown with './app target', set with './app target <app>', or "
            "overridden once with '--app <app>'."
        ),
        "run": (
            "Build the target app's debug APK, install it on a connected Android device, "
            "then launch its main activity. This is equivalent to running build, install, and launch in order. "
            "The target app is shown with './app target', set with './app target <app>', or "
            "overridden once with '--app <app>'."
        ),
        "logcat": (
            "Stream logcat output for the target app. If the app process is already running, "
            "the command follows that PID; otherwise it falls back to useful project log filters. "
            "The target app is shown with './app target', set with './app target <app>', or "
            "overridden once with '--app <app>'."
        ),
    }
    for command, help_text, func in [
        ("build", "Build the target app.", build_app),
        ("install", "Install the target app on a connected device.", install_app),
        ("launch", "Launch the target app on a connected device.", launch_app),
        ("run", "Build, install, and launch the target app.", run_app),
        ("logcat", "Stream useful logcat output for the target app.", logcat_app),
    ]:
        command_parser = subparsers.add_parser(
            command,
            help=help_text,
            description=command_descriptions[command],
        )
        command_parser.add_argument(
            "--app",
            help=(
                "Use this app for this command only instead of the saved local target "
                "from .app.local.properties."
            ),
        )
        if command == "install":
            command_parser.add_argument("--build", action="store_true", help="Build before installing.")
        if command == "logcat":
            command_parser.add_argument("--clear", action="store_true", help="Clear logcat before streaming.")
        command_parser.set_defaults(func=func)

    return parser


def main() -> int:
    try:
        require_python()
        parser = make_parser()
        args = parser.parse_args()
        args.func(args)
        return 0
    except KeyboardInterrupt:
        print("\nCanceled.", file=sys.stderr)
        return 130
    except subprocess.CalledProcessError as exc:
        return exc.returncode
    except AppCliError as exc:
        print(f"app: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
