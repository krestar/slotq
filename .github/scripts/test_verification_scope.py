#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from verification_scope import changed_paths, verification_scopes


class VerificationScopeTests(unittest.TestCase):
    def test_docs_only_stays_build_free(self) -> None:
        self.assertEqual(
            verification_scopes(["docs/architecture.md", "README.md"]),
            (False, False),
        )

    def test_regular_backend_frontend_and_workflow_paths(self) -> None:
        self.assertEqual(verification_scopes(["backend/src/App.java"]), (True, False))
        self.assertEqual(verification_scopes(["frontend/src/app.ts"]), (False, True))
        self.assertEqual(
            verification_scopes([".github/workflows/backend-ci.yml"]),
            (True, False),
        )
        self.assertEqual(
            verification_scopes([".github/workflows/frontend-ci.yml"]),
            (False, True),
        )
        self.assertEqual(
            verification_scopes([".github/workflows/pr-policy.yml"]),
            (True, True),
        )
        self.assertEqual(
            verification_scopes([".github/scripts/verification_scope.py"]),
            (True, True),
        )

    def test_backend_delete_requires_backend(self) -> None:
        paths = self._changed_paths_after_delete("backend/src/App.java")
        self.assertIn("backend/src/App.java", paths)
        self.assertEqual(verification_scopes(paths), (True, False))

    def test_cross_scope_renames_keep_old_and_new_scopes(self) -> None:
        cases = [
            ("backend/src/App.java", "docs/App.java", (True, False)),
            ("frontend/src/app.ts", "docs/app.ts", (False, True)),
            ("docs/App.java", "backend/src/App.java", (True, False)),
            ("docs/app.ts", "frontend/src/app.ts", (False, True)),
            ("backend/src/App.java", "frontend/src/App.ts", (True, True)),
            ("docs/a.md", "docs/b.md", (False, False)),
        ]
        for source, target, expected in cases:
            with self.subTest(source=source, target=target):
                paths = self._changed_paths_after_move(source, target)
                self.assertIn(source, paths)
                self.assertIn(target, paths)
                self.assertEqual(verification_scopes(paths), expected)

    def _changed_paths_after_move(self, source: str, target: str) -> list[str]:
        with tempfile.TemporaryDirectory() as temp:
            repo = pathlib.Path(temp)
            self._init_repo(repo, source)
            base = self._git(repo, "rev-parse", "HEAD")
            (repo / target).parent.mkdir(parents=True, exist_ok=True)
            self._run(repo, "git", "mv", source, target)
            self._run(repo, "git", "commit", "-m", "move")
            head = self._git(repo, "rev-parse", "HEAD")
            return changed_paths(base, head, str(repo))

    def _changed_paths_after_delete(self, path: str) -> list[str]:
        with tempfile.TemporaryDirectory() as temp:
            repo = pathlib.Path(temp)
            self._init_repo(repo, path)
            base = self._git(repo, "rev-parse", "HEAD")
            self._run(repo, "git", "rm", path)
            self._run(repo, "git", "commit", "-m", "delete")
            head = self._git(repo, "rev-parse", "HEAD")
            return changed_paths(base, head, str(repo))

    def _init_repo(self, repo: pathlib.Path, path: str) -> None:
        self._run(repo, "git", "init", "-q")
        self._run(repo, "git", "config", "user.email", "ci@example.invalid")
        self._run(repo, "git", "config", "user.name", "SlotQ CI")
        target = repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("fixture\n", encoding="utf-8")
        self._run(repo, "git", "add", ".")
        self._run(repo, "git", "commit", "-q", "-m", "base")

    @staticmethod
    def _run(repo: pathlib.Path, *args: str) -> None:
        subprocess.run(args, cwd=repo, check=True, capture_output=True)

    @staticmethod
    def _git(repo: pathlib.Path, *args: str) -> str:
        return subprocess.check_output(
            ["git", *args],
            cwd=repo,
            text=True,
        ).strip()


if __name__ == "__main__":
    unittest.main()
