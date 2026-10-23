#!/usr/bin/env python3
"""Resolve this toolkit and print task-specific reading paths. No device/network I/O."""
import argparse
import json
import os
from pathlib import Path
import sys

SKILL_DIR = Path(__file__).resolve().parents[1]
MANIFEST = SKILL_DIR / "references/workflows.json"
MARKERS = (
    "profiles/rg-glasses-glass15-android12.json",
    "tools/rokid.py",
    "app/AndroidManifest.xml",
    "evidence/validated-results.json",
)


def repository_at(path):
    path = Path(path).expanduser().resolve()
    return path.is_dir() and all((path / name).is_file() for name in MARKERS)


def resolve_repository(explicit=None, *, cwd=None, skill_dir=None, environment=None):
    environment = os.environ if environment is None else environment
    configured = explicit if explicit is not None else environment.get("ROKID_TOOLKIT_ROOT")
    if configured is not None:
        path = Path(configured).expanduser().resolve()
        if not repository_at(path):
            raise ValueError("Configured path is not a complete Rokid toolkit checkout; fix --repo or ROKID_TOOLKIT_ROOT.")
        return path
    candidates = [Path.cwd() if cwd is None else Path(cwd),
                  SKILL_DIR if skill_dir is None else Path(skill_dir)]
    for start in candidates:
        start = start.expanduser().resolve()
        for parent in [start, *start.parents]:
            if repository_at(parent):
                return parent
    raise ValueError("Toolkit checkout not found. Obtain the full repository and pass --repo /path/to/checkout.")


def repo_file(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or not path.is_file():
        raise ValueError("Missing or out-of-repository route resource: " + relative)
    return path


def build_context(root, task, manifest=None):
    root = Path(root).resolve()
    routes = json.loads(MANIFEST.read_text()) if manifest is None else manifest
    route = routes["tasks"][task]
    reads = list(dict.fromkeys(routes["common_reads"] + route["read_files"]))
    source_files = [str(repo_file(root, name)) for name in route["source_files"]]
    read_files = [str(repo_file(root, name)) for name in reads]
    profile = json.loads(repo_file(root, routes["profile"]).read_text())
    historical = json.loads(repo_file(root, "evidence/validated-results.json").read_text())
    references = [SKILL_DIR / "references/diagnosis.md", SKILL_DIR / "references/acceptance.md"]
    if not all(path.is_file() for path in references):
        raise ValueError("Skill reference files are missing; restore the complete skill folder.")
    return {
        "schema": 1, "repository": str(root), "task": task, "goal": route["goal"],
        "read_files": read_files, "source_files_if_needed": source_files,
        "skill_references_if_needed": [str(path) for path in references],
        "profile_id": profile["id"],
        "historical_observation_date": historical["observation_date"],
        "historically_tested_device_count": historical["physical_device_count"],
        "tested_engineering_fingerprint": profile["ota"]["post_build"],
        "device_effects_only_when_authorized": route["device_effects"],
        "acceptance": route["acceptance"],
        "device_contacted": False,
        "current_device_qualified": False,
        "execution_authorized_by_skill": False,
        "next": "Read the listed files; inspect current device only within the user's scope. No device actions were executed.",
    }


def main():
    routes = json.loads(MANIFEST.read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path)
    parser.add_argument("--task", choices=list(routes["tasks"]), default="overview")
    args = parser.parse_args()
    try:
        root = resolve_repository(args.repo)
        print(json.dumps(build_context(root, args.task, routes), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError) as error:
        print("Context unavailable: " + (str(error) if isinstance(error, ValueError)
                                        else type(error).__name__), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
