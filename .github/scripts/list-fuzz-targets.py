"""Lists every Jazzer @FuzzTest method in the repository as a GitHub Actions matrix.

Output (stdout): JSON list of {"project", "test", "name"}:
  project  Gradle path of the module, e.g. ":Vectora-Protocol"
  test     test filter for --tests, e.g. "net.vectora.protocol.VarIntsFuzzTest.roundTrip"
  name     short label for the job name

Only reads source files; the build is not run.
"""

import json
import pathlib
import re
import sys

FUZZ_TEST = re.compile(r"@FuzzTest\b")
METHOD = re.compile(r"\bvoid\s+(\w+)\s*\(")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)


def main() -> int:
    root = pathlib.Path(".").resolve()
    targets = []
    for source in sorted(root.glob("**/src/test/java/**/*.java")):
        rel = source.relative_to(root)
        if any(part in ("build", ".gradle", ".claude", "runtime") for part in rel.parts):
            continue
        text = source.read_text(encoding="utf-8")
        if not FUZZ_TEST.search(text):
            continue
        package = PACKAGE.search(text)
        module_dir = rel.parts[: rel.parts.index("src")]
        project = ":" + ":".join(module_dir)
        cls = (package.group(1) + "." if package else "") + source.stem
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if not FUZZ_TEST.search(line):
                continue
            for following in lines[i : i + 6]:
                match = METHOD.search(following)
                if match:
                    method = match.group(1)
                    targets.append({
                        "project": project,
                        "test": f"{cls}.{method}",
                        "name": f"{source.stem}.{method}",
                    })
                    break
            else:
                print(f"no method found after @FuzzTest in {rel}:{i + 1}", file=sys.stderr)
                return 1
    if not targets:
        print("no fuzz targets found", file=sys.stderr)
        return 1
    print(json.dumps(targets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
