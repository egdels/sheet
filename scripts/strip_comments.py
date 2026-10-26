#!/usr/bin/env python3
# Copyright (C) 2026 Christian Kierdorf
# SPDX-License-Identifier: GPL-3.0-or-later
"""Removes the comments from Sheet's own sources, in place.

    scripts/strip_comments.py <repository> [--dry-run]

Part of the experiment described in README.md and AI_POLICY.md: comments
are where the style of an AI agent shows most, so the published code goes
without them. The copyright/SPDX lines at the top of a file stay; they are
the licence notice, not commentary. Third-party code (external/,
third_party_licenses/, the Gradle wrapper) and documentation (*.md) are
left alone, and so is this script.

Per language:
- Kotlin, Kotlin scripts, Java: a lexer that knows strings, raw strings,
  string templates, character literals and (in Kotlin) nested block
  comments, so "//" in a URL or a string is never taken for a comment.
- Python: comments via the tokenizer, docstrings via the AST.
- Shell: '#' at the start of a line or after whitespace, outside quotes;
  the shebang stays.
- XML: <!-- ... -->.
- ProGuard, properties, TOML, YAML, ONNX Runtime operator lists,
  .gitignore: whole-line '#' comments.

A line left empty by a removed comment is dropped, runs of blank lines
become one. Run the tests afterwards; the script changes no code, but it
is the check that matters.
"""

import ast
import io
import os
import re
import sys
import tokenize

SKIP_DIRS = {".git", ".gradle", ".kotlin", ".idea", "build", "external", "third_party_licenses", "jniLibs", "libs", "__pycache__", "node_modules"}
SKIP_FILES = {"gradlew", "gradlew.bat", "strip_comments.py"}
HASH_LINE = {".pro", ".properties", ".toml", ".yml", ".yaml", ".config", ".gitignore"}
LICENCE = re.compile(r"Copyright \(C\)|SPDX-License-Identifier")


def keep(comment: str) -> bool:
    return bool(LICENCE.search(comment))


def tidy(original: str, stripped_lines: list[str], touched: set[int]) -> str:
    """Drops lines that only held a comment, trims what a trailing comment left, collapses blank runs."""
    out = []
    old = original.split("\n")
    for i, line in enumerate(stripped_lines):
        if i in touched:
            line = line.rstrip()
            if not line.strip() and old[i].strip():
                continue
        if not line.strip() and out and not out[-1].strip():
            continue
        out.append(line)
    while out and not out[0].strip():
        out.pop(0)
    text = "\n".join(out)
    return text if text.endswith("\n") else text + "\n"


def strip_c_like(src: str, nested: bool, templates: bool) -> str:
    """Kotlin (nested block comments, string templates) and Java."""
    out = []
    touched = set()
    line = 0
    i = 0
    n = len(src)

    def emit(s):
        nonlocal line
        out.append(s)
        line += s.count("\n")

    def comment(start, end):
        """Drops src[start:end] unless it is a licence line; keeps its newlines."""
        nonlocal line
        text = src[start:end]
        if keep(text):
            emit(text)
            return
        first = line
        newlines = text.count("\n")
        out.append("\n" * newlines)
        for k in range(first, first + newlines + 1):
            touched.add(k)
        line += newlines

    def code(i, stop_at_brace):
        """Copies code from i until EOF (or the '}' closing a template); returns the index after."""
        depth = 0
        while i < n:
            c = src[i]
            two = src[i:i + 2]
            if two == "//":
                j = src.find("\n", i)
                j = n if j < 0 else j
                comment(i, j)
                i = j
            elif two == "/*":
                j, d = i + 2, 1
                while j < n and d > 0:
                    if nested and src.startswith("/*", j):
                        d += 1
                        j += 2
                    elif src.startswith("*/", j):
                        d -= 1
                        j += 2
                    else:
                        j += 1
                comment(i, j)
                i = j
            elif src.startswith('"""', i):
                i = string(i, raw=True)
            elif c == '"':
                i = string(i, raw=False)
            elif c == "'":
                j = i + 1
                while j < n and src[j] != "'":
                    j += 2 if src[j] == "\\" else 1
                emit(src[i:j + 1])
                i = j + 1
            elif stop_at_brace and c == "{":
                depth += 1
                emit(c)
                i += 1
            elif stop_at_brace and c == "}":
                if depth == 0:
                    emit(c)
                    return i + 1
                depth -= 1
                emit(c)
                i += 1
            else:
                emit(c)
                i += 1
        return i

    def string(i, raw):
        quote = '"""' if raw else '"'
        emit(quote)
        i += len(quote)
        while i < n:
            if src.startswith(quote, i):
                if raw:
                    # A raw string may end in more quotes than three: the last three close it.
                    j = i
                    while src.startswith('"', j + 3):
                        j += 1
                    emit(src[i:j + 3])
                    return j + 3
                emit(quote)
                return i + 1
            c = src[i]
            if not raw and c == "\\":
                emit(src[i:i + 2])
                i += 2
            elif templates and src.startswith("${", i):
                emit("${")
                i = code(i + 2, stop_at_brace=True)
            else:
                emit(c)
                i += 1
        return i

    code(0, stop_at_brace=False)
    return tidy(src, "".join(out).split("\n"), touched)


def strip_python(src: str) -> str:
    lines = src.split("\n")
    drop = {}  # line index -> column where a comment starts
    for tok in tokenize.generate_tokens(io.StringIO(src).readline):
        if tok.type == tokenize.COMMENT and not keep(tok.string) and not (tok.start[0] == 1 and tok.string.startswith("#!")):
            drop[tok.start[0] - 1] = tok.start[1]
    docstrings = []
    for node in ast.walk(ast.parse(src)):
        if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
            first = node.body[0]
            if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                docstrings.append((first.lineno - 1, first.end_lineno - 1, first.col_offset, len(node.body) == 1))
    touched = set()
    for row, col in drop.items():
        lines[row] = lines[row][:col]
        touched.add(row)
    for start, end, col, only in sorted(docstrings, reverse=True):
        # Same number of lines, so the indices in touched stay right.
        lines[start:end + 1] = ([" " * col + "pass"] if only else [""]) + [""] * (end - start)
        touched.update(range(start + (1 if only else 0), end + 1))
    return tidy(src, lines, touched)


def strip_shell(src: str) -> str:
    lines = src.split("\n")
    touched = set()
    for row, line in enumerate(lines):
        if row == 0 and line.startswith("#!"):
            continue
        quote = None
        for col, c in enumerate(line):
            if quote:
                if c == quote and (quote == "'" or line[col - 1] != "\\"):
                    quote = None
            elif c in "'\"":
                quote = c
            elif c == "#" and (col == 0 or line[col - 1] in " \t"):
                if not keep(line[col:]):
                    lines[row] = line[:col]
                    touched.add(row)
                break
    return tidy(src, lines, touched)


def strip_xml(src: str) -> str:
    touched = set()

    def drop(m):
        if keep(m.group(0)):
            return m.group(0)
        first = src.count("\n", 0, m.start())
        touched.update(range(first, first + m.group(0).count("\n") + 1))
        return "\n" * m.group(0).count("\n")

    return tidy(src, re.sub(r"<!--.*?-->", drop, src, flags=re.S).split("\n"), touched)


def strip_hash_lines(src: str) -> str:
    lines = src.split("\n")
    touched = {i for i, l in enumerate(lines) if l.lstrip().startswith("#") and not keep(l)}
    for i in touched:
        lines[i] = ""
    return tidy(src, lines, touched)


def stripper(name: str):
    ext = os.path.splitext(name)[1] if name != ".gitignore" else ".gitignore"
    if ext in (".kt", ".kts"):
        return lambda s: strip_c_like(s, nested=True, templates=True)
    if ext == ".java":
        return lambda s: strip_c_like(s, nested=False, templates=False)
    if ext == ".py":
        return strip_python
    if ext == ".sh":
        return strip_shell
    if ext == ".xml":
        return strip_xml
    if ext in HASH_LINE:
        return strip_hash_lines
    return None


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    if len(args) != 1 or not os.path.isdir(args[0]):
        print(__doc__.strip().split("\n\n")[1], file=sys.stderr)
        return 2
    changed = 0
    for root, dirs, files in os.walk(args[0]):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in sorted(files):
            strip = stripper(name)
            if name in SKIP_FILES or strip is None:
                continue
            path = os.path.join(root, name)
            with open(path, encoding="utf-8") as f:
                src = f.read()
            out = strip(src)
            if out != src:
                changed += 1
                print(os.path.relpath(path, args[0]))
                if not dry:
                    with open(path, "w", encoding="utf-8") as f:
                        f.write(out)
    print(f"{changed} files {'would change' if dry else 'changed'}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
