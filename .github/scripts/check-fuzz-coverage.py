# SPDX-FileCopyrightText: 2026 VectoDE
# SPDX-License-Identifier: GPL-3.0-or-later
"""Every parser of untrusted input needs a fuzz test.

A parser here is a non-private method of production code (src/main) whose name starts with
parse, decode, deserialize, unpack, fromJson or read and whose first parameter is text or bytes
(String, CharSequence, byte[], ByteBuffer, InputStream, Reader, JsonNode). Each one must be
called from a Jazzer fuzz test (a file named *FuzzTest.java), or be listed with a reason in
.github/fuzz-exempt.txt. A new parser without either fails this check, so "a fuzz test for every
new parser" is enforced and not left to memory.

Usage: python3 -I .github/scripts/check-fuzz-coverage.py   (from the repository root)
Only the Python standard library is used. Exit code 0 means every parser is covered.
"""

import pathlib
import re
import sys

NAME = r"(?:parse|decode|deserialize|unpack|fromJson|read)[A-Za-z0-9_]*"
PARAM = r"(?:final\s+)?(?:String|CharSequence|byte\s*\[\]|ByteBuffer|InputStream|Reader|JsonNode)\b"
METHOD = re.compile(
    r"^(?P<indent>[ \t]*)(?P<mods>(?:(?:public|protected|static|final|synchronized|abstract|default)\s+)*)"
    r"(?:<[^>\n]+>\s+)?[A-Za-z_][\w<>,.?\[\]\s]*?\s+(?P<name>" + NAME + r")\s*\(\s*" + PARAM,
    re.MULTILINE,
)
CLASS = re.compile(r"^\s*(?:public\s+|final\s+|abstract\s+|sealed\s+)*(?:class|interface|enum|record)\s+(\w+)", re.MULTILINE)
SKIP_DIRS = {"build", ".gradle", ".git", "node_modules", ".claude"}


def source_files(root, kind):
    for path in sorted(root.rglob("*.java")):
        parts = path.relative_to(root).parts
        if any(p in SKIP_DIRS for p in parts):
            continue
        if kind == "main" and "src/main/" in path.as_posix():
            yield path
        elif kind == "fuzz" and path.name.endswith("FuzzTest.java"):
            yield path


def parsers(root):
    found = []
    for path in source_files(root, "main"):
        text = path.read_text(encoding="utf-8")
        for match in METHOD.finditer(text):
            mods = match.group("mods")
            line_start = text.rfind("\n", 0, match.start()) + 1
            line = text[line_start:text.find("\n", match.start())]
            if "private" in line.split(match.group("name"))[0]:
                continue
            if match.group("name") == "return" or line.strip().startswith(("return", "//", "*", "new ")):
                continue
            # The parameter list: methods that also take a file or folder read from disk and are
            # covered through the parser they hand the content to.
            rest = text[match.end():]
            end = rest.find(")")
            params = match.group(0)[match.group(0).rfind("(") + 1:] + rest[:end]
            if re.search(r"(?:Path|File)", params):
                continue
            # Declarations without a body (interface methods) are covered by their implementations.
            after = rest[end + 1:].lstrip()
            after = re.sub(r"^throws\s+[\w.,\s]+", "", after).lstrip()
            if after.startswith(";") or "abstract" in mods:
                continue
            owner = path.stem
            found.append((owner, match.group("name"), path.relative_to(root).as_posix()))
    return found


def exemptions(root):
    path = root / ".github" / "fuzz-exempt.txt"
    result = {}
    if not path.exists():
        return result
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        entry, _, reason = line.partition("#")
        if not reason.strip():
            print(f"{path}:{number}: an exemption needs a reason after '#'", file=sys.stderr)
            sys.exit(1)
        result[entry.strip()] = reason.strip()
    return result


def main():
    root = pathlib.Path(".").resolve()
    fuzz_text = "\n".join(p.read_text(encoding="utf-8") for p in source_files(root, "fuzz"))
    exempt = exemptions(root)
    missing = []
    seen = set()
    for owner, name, where in parsers(root):
        key = f"{owner}.{name}"
        if key in seen:
            continue
        seen.add(key)
        if re.search(r"\b" + re.escape(owner) + r"\s*\.\s*" + re.escape(name) + r"\s*\(", fuzz_text) or key in exempt:
            continue
        # A fuzz test that calls the parser through an instance or a shorter alias is accepted
        # when it names both the class and the method.
        if re.search(r"\b" + re.escape(owner) + r"\b", fuzz_text) and re.search(r"\b" + re.escape(name) + r"\s*\(", fuzz_text):
            continue
        missing.append((key, where))
    stale = [k for k in exempt if k not in seen]
    for key, where in missing:
        print(f"::error file={where}::{key} parses untrusted input and has no fuzz test (add a @FuzzTest, "
              f"or a reasoned entry in .github/fuzz-exempt.txt)")
    for key in stale:
        print(f"::error file=.github/fuzz-exempt.txt::{key} is listed but no such parser exists any more")
    print(f"{len(seen)} parsers checked, {len(missing)} without fuzz test, {len(exempt)} exempt, {len(stale)} stale exemptions.")
    return 1 if missing or stale else 0


if __name__ == "__main__":
    sys.exit(main())
