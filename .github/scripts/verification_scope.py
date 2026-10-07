#!/usr/bin/env python3
import os
import subprocess


COMMON_SCOPE_PATHS = {
    ".github/workflows/pr-policy.yml",
    ".github/scripts/verification_scope.py",
    ".github/scripts/test_verification_scope.py",
}


def changed_paths(base: str, head: str, cwd: str | None = None) -> list[str]:
    raw = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", "-z", base, head],
        cwd=cwd,
    )
    return [os.fsdecode(path) for path in raw.split(b"\0") if path]


def verification_scopes(paths: list[str]) -> tuple[bool, bool]:
    backend = any(
        path.startswith("backend/")
        or path == ".github/workflows/backend-ci.yml"
        or path in COMMON_SCOPE_PATHS
        for path in paths
    )
    frontend = any(
        path.startswith("frontend/")
        or path == ".github/workflows/frontend-ci.yml"
        or path in COMMON_SCOPE_PATHS
        for path in paths
    )
    return backend, frontend


def main() -> None:
    base = os.environ["BASE_SHA"]
    head = os.environ["HEAD_SHA"]
    paths = changed_paths(base, head)
    backend, frontend = verification_scopes(paths)

    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"backend={str(backend).lower()}\n")
        output.write(f"frontend={str(frontend).lower()}\n")

    print("Changed files:")
    for path in paths:
        print(f"- {path}")
    print(f"backend={backend}, frontend={frontend}")


if __name__ == "__main__":
    main()
