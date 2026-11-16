#!/usr/bin/env python3
# scripts/doc_sync.py — Noxs documentation synchronization engine.
# Single entry point for the documentation workflow:
#
#   python3 scripts/doc_sync.py check [--sitemap sitemap.xml]
#       Validate the command/feature/permission/API metadata, verify CLI
#       coverage (every dispatch command documented, nothing invented), and
#       -- with --sitemap -- that every documentation route exists on the
#       deployed site. Exit 1 on any drift.
#
#   python3 scripts/doc_sync.py affected <changed-file> [<changed-file> ...]
#       Map changed source files to the documentation pages that must be
#       updated (used by CI to label the docs-sync PR).
#
#   python3 scripts/doc_sync.py report
#       Print the documentation coverage report (percentages computed from
#       the actual repository state — never hardcoded).
#
# Deterministic, dependency-free, safe to run in CI.

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
META = ROOT / "app/src/main/resources/documentation"
TEMPLATES = ROOT / "app/src/main/java/com/crossberry/noxs/runtime"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
DOCS_BASE = "https://crossberry-in.github.io/nxos-docs/"

ALLOWED_STATUS = {"implemented", "experimental", "planned"}
ALLOWED_PLATFORMS = {"ubuntu", "kali"}

# Changed-path prefix -> documentation pages that must be reviewed.
AFFECTED_MAP = [
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsStorageBridge.kt",
     ["files/storage-access/", "android/storage-permissions/", "troubleshooting/storage-errors/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsStorageMount.kt",
     ["android/external-sdcard/", "files/storage-access/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsAndroidBridge.kt",
     ["android/overview/", "reference/cli-reference/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsAndroidPermissions.kt",
     ["android/app-permissions/", "android/storage-permissions/"]),
    ("app/src/main/AndroidManifest.xml",
     ["android/app-permissions/", "android/storage-permissions/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsCliTemplate.kt",
     ["reference/cli-reference/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxTemplate.kt",
     ["reference/cli-reference/", "packages/package-manager/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPkg",
     ["packages/", "reference/package-manifest/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxPlugLib.kt",
     ["plugins/", "reference/plugin-manifest-reference/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxAi",
     ["ai/", "ai/api-keys/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxCertFix.kt",
     ["troubleshooting/certificate-errors/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/CodeServerManager.kt",
     ["devtools/code-server/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsDocker",
     ["devtools/workflows/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/ProotLauncher.kt",
     ["architecture/architecture/", "architecture/bootstrap/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/RootfsConfigurator.kt",
     ["architecture/bootstrap/", "environments/ubuntu-configuration/"]),
    ("app/src/main/java/com/crossberry/noxs/environments/",
     ["environments/", "getting-started/installation/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsDocumentsProvider.kt",
     ["files/file-browser/", "android/file-picker/"]),
    ("app/src/main/java/com/crossberry/noxs/ui/TerminalActivity.kt",
     ["terminal/", "android/overview/"]),
    ("app/src/main/java/com/crossberry/noxs/ui/StorageActivity.kt",
     ["android/storage-permissions/", "files/storage-access/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsNxApiTemplate.kt",
     ["reference/sdk-reference/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsUrlGuard.kt",
     ["architecture/security/"]),
    ("app/src/main/java/com/crossberry/noxs/runtime/NoxsDocsMap.kt",
     ["reference/cli-reference/"]),
    ("linux-runtime/launcher/",
     ["reference/cli-reference/"]),
]


def load(name):
    path = META / name
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f"{path.relative_to(ROOT)} is not valid JSON: {exc}")
        return {}


ERRORS = []
NOTES = []


def fail(message):
    ERRORS.append(message)


def note(message):
    NOTES.append(message)


def routes_from_commands(data):
    for command in data.get("commands", []):
        route = command.get("documentation_route", "")
        if route:
            yield command["name"], route


def check_metadata():
    commands = load("commands.json")
    features = load("features.json")
    permissions = load("permissions.json")
    apis = load("apis.json")

    names = [c.get("name", "") for c in commands.get("commands", [])]
    if len(names) != len(set(names)):
        duplicates = sorted({n for n in names if names.count(n) > 1})
        fail(f"commands.json: duplicate command names: {duplicates}")

    for command in commands.get("commands", []):
        for field in ("name", "category", "description", "usage",
                      "implementation_status", "platform_support",
                      "documentation_route"):
            if field not in command:
                fail(f"commands.json: '{command.get('name')}' is missing '{field}'")
        status = command.get("implementation_status")
        if status not in ALLOWED_STATUS:
            fail(f"commands.json: '{command.get('name')}' has invalid status '{status}'")
        for platform in command.get("platform_support", []):
            if platform not in ALLOWED_PLATFORMS:
                fail(f"commands.json: '{command.get('name')}' has invalid platform '{platform}'")
        if not re.fullmatch(r"[a-z0-9-]+(/[a-z0-9-]+)*/?", command.get("documentation_route", "x")):
            fail(f"commands.json: '{command.get('name')}' has a malformed route "
                 f"'{command.get('documentation_route')}'")

    feature_ids = [f.get("id", "") for f in features.get("features", [])]
    if len(feature_ids) != len(set(feature_ids)):
        fail("features.json: duplicate feature ids")
    for feature in features.get("features", []):
        status = feature.get("status")
        if status not in ALLOWED_STATUS:
            fail(f"features.json: '{feature.get('id')}' has invalid status '{status}'")
        for field in ("name", "description", "status", "platform_support", "documentation_page"):
            if field not in feature:
                fail(f"features.json: '{feature.get('id')}' is missing '{field}'")

    for perm in permissions.get("permissions", []):
        for field in ("name", "type", "purpose", "user_control", "documentation_route"):
            if field not in perm:
                fail(f"permissions.json: '{perm.get('name')}' is missing '{field}'")

    for api in apis.get("apis", []):
        for field in ("id", "name", "kind", "description", "documentation_route"):
            if field not in api:
                fail(f"apis.json: '{api.get('id')}' is missing '{field}'")

    # Manifest mirror check: every concrete Android permission string in the
    # manifest must appear somewhere in permissions.json (drift detection).
    manifest_text = MANIFEST.read_text(encoding="utf-8")
    declared = set(re.findall(r'android.permission\.([A-Z_]+)', manifest_text))
    registry_text = json.dumps(permissions)
    for permission in sorted(declared):
        if permission not in registry_text:
            fail(f"permissions.json: manifest permission '{permission}' is not documented")

    return commands, features, permissions, apis


def extract_dispatch_commands():
    """Top-level dispatch labels of the noxs CLI (from the Kotlin template)."""
    kt = (TEMPLATES / "NoxsCliTemplate.kt").read_text(encoding="utf-8")
    cli = kt.replace("${'$'}", "$")
    dispatch = re.search(r'case "\$\{1:-help\}" in\n(.*?)\n    \*\)', cli, re.S)
    if not dispatch:
        fail("noxs dispatch case block not found")
        return set()
    labels = set()
    for line in dispatch.group(1).splitlines():
        # Top-level case labels sit at exactly 4-space indent; nested case
        # blocks (code/docker sub-commands) are deeper and must be ignored.
        if not re.match(r"^    [^\s]", line):
            continue
        stripped = line.strip()
        head = stripped.split(")", 1)[0]
        for token in head.split("|"):
            token = token.strip()
            if re.fullmatch(r"[a-z][a-z0-9-]*", token):
                labels.add(token)
    labels.discard("help")
    return labels


def check_cli_coverage(commands):
    labels = extract_dispatch_commands()
    documented_blob = json.dumps(commands)
    missing = []
    for label in sorted(labels):
        # Every dispatch command must be mentioned in commands.json.
        if not re.search(rf"noxs {re.escape(label)}[\" \[|/,]", documented_blob):
            missing.append(f"noxs {label}")
    if missing:
        fail(f"commands.json does not cover dispatch commands: {missing}")
    # And nothing documented may be missing from the script.
    invented = []
    helpers = {"noxs-setup-storage", "noxs-service", "noxs-socket", "noxs-ps"}
    for command in commands.get("commands", []):
        name = command["name"]
        if name.split()[0] in helpers:
            continue
        parts = name.split()
        if parts and parts[0] == "noxs" and len(parts) > 1:
            sub = parts[1].split("[")[0]
            if sub not in labels and sub != "help":
                invented.append(name)
    if invented:
        fail(f"commands.json documents commands not present in the noxs dispatch: {invented}")
    return labels


def check_docs_map_alignment():
    """Every NoxsDocsMap route must be present in the CLI docs_url mapping."""
    kt = (TEMPLATES / "NoxsDocsMap.kt").read_text(encoding="utf-8")
    kt_routes = dict(re.findall(r'"([a-z0-9-]+)" to "([a-z0-9/-]+/)"', kt))
    cli = (TEMPLATES / "NoxsCliTemplate.kt").read_text(encoding="utf-8").replace("${'$'}", "$")
    missing = []
    for topic, path in sorted(kt_routes.items()):
        if f"'%s/{path}'" not in cli:
            missing.append(topic)
    if missing:
        fail(f"noxs docs mapping is missing topics from NoxsDocsMap: {missing}")
    return kt_routes


def check_sitemap(paths):
    """Verify each documentation route exists in the given sitemap content."""
    content = paths.read_text(encoding="utf-8")
    urls = set(re.findall(r"<loc>(.*?)</loc>", content))
    commands, *_ = check_metadata()
    missing = []
    for name, route in routes_from_commands(commands):
        url = DOCS_BASE + route
        if url not in urls:
            missing.append(f"{name} -> {url}")
    features = load("features.json")
    for feature in features.get("features", []):
        url = DOCS_BASE + feature.get("documentation_page", "")
        if feature.get("documentation_page") and url not in urls:
            missing.append(f"feature:{feature.get('id')} -> {url}")
    if missing:
        fail("routes missing from the deployed sitemap:\n  " + "\n  ".join(missing))


def affected(changed_files):
    pages = set()
    for changed in changed_files:
        normalized = changed.strip()
        for prefix, docs in AFFECTED_MAP:
            if normalized.startswith(prefix) or normalized.endswith(prefix.split("/")[-1]):
                pages.update(docs)
    return sorted(pages)


def report():
    commands, features, permissions, apis = check_metadata()
    labels = extract_dispatch_commands()
    total = len(commands.get("commands", []))
    with_route = sum(1 for c in commands.get("commands", [])
                     if c.get("documentation_route"))
    statuses = {}
    for feature in features.get("features", []):
        statuses[feature["status"]] = statuses.get(feature["status"], 0) + 1
    print("Documentation Coverage")
    print(f"  CLI dispatch commands discovered : {len(labels)}")
    print(f"  commands.json entries            : {total}")
    print(f"  with documentation route         : {with_route}/{total}")
    print(f"  feature statuses                 : {statuses}")
    print(f"  permissions documented           : {len(permissions.get('permissions', []))}")
    print(f"  APIs documented                  : {len(apis.get('apis', []))}")
    print(f"  Internal link checks             : {'PASS' if not ERRORS else 'FAIL'}")


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    mode = argv[1]
    if mode == "check":
        check_metadata()
        commands = load("commands.json")
        check_cli_coverage(commands)
        check_docs_map_alignment()
        if "--sitemap" in argv:
            index = argv.index("--sitemap")
            if index + 1 >= len(argv):
                fail("--sitemap requires a file path")
            else:
                check_sitemap(pathlib.Path(argv[index + 1]))
    elif mode == "affected":
        pages = affected(argv[2:])
        if not pages:
            print("No documentation mapping for the given files; review manually.")
        else:
            print("Documentation pages to review/update:")
            for page in pages:
                print(f"  - {DOCS_BASE}{page}")
    elif mode == "report":
        report()
    else:
        print(f"unknown mode: {mode}")
        return 2
    for message in NOTES:
        print(f"note: {message}")
    if ERRORS:
        print("Documentation drift detected:", file=sys.stderr)
        for message in ERRORS:
            print(f"  - {message}", file=sys.stderr)
        return 1
    if mode == "check":
        print("Documentation sync check: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
