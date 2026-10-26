# AI-Assisted Development

This document exists for transparency: it explains how AI tooling is used
to build Sheet. It is the only place that does: the commit history
deliberately does not (see below).

## Summary

Sheet is developed primarily with [Claude Code](https://claude.com/claude-code),
an AI coding agent by Anthropic, directed and reviewed throughout by a
single human developer. This is substantive involvement, not
autocomplete-level assistance: the agent designs and writes most of the
implementation, tests, build tooling and documentation (including this
file), from requirements and direction the developer provides.

## The history does not show it

This repository starts without its development history, and its commits
do not show the AI involvement: no `Co-Authored-By` trailers, and commit
messages written by the developer so that they do not reveal which parts
the agent wrote. This is deliberate. It is part of the experiment
described in the README, which tests whether anyone can tell from the
app, the code or the history how the work was done.

The code is published without its comments for the same reason:
comments are where an agent's style shows most. Sheet is developed in a
separate, private repository with comments and the full history; this
repository receives a copy of it from which `scripts/strip_comments.py`
has removed the comments of Sheet's own sources (Kotlin, Java, Python,
shell, XML, build and CI files). Only the copyright/SPDX lines at the top
of each file stay. Much of the rationale is in `DEVELOPMENT.md`.
Removing the comments changes no behaviour: on the first run the build,
all tests, lint and both evaluations (corners and OCR) gave
byte-identical results before and after.

The concealment is disclosed here, so this file, not the commit log, is
the record of how Sheet is made. For the record: the first version was
built on 25 and 26 October 2026 in 29 commits, every one of them with
the agent as co-author. That history was not carried over.

## What the AI does

- Implements features, refactors and bug fixes the developer specifies
- Writes and maintains the unit tests
- Writes build and CI configuration and the documentation

## What the developer does

- Decides what the app is and is not, and what gets built in which order
- Directs every change and checks its result, on the phone where it
  matters; not every diff is read line by line
- Writes the commits; the agent does not commit
- Runs the app and tests on real hardware with real paper and real phones,
  which the agent cannot do
- Owns the licensing and release decisions

## What this means for you

Sheet's own code is GPL-3.0-or-later regardless of who or what wrote it.
The models it ships have their own licences: the corner model and the
PaddleOCR models are Apache-2.0 (see THIRD-PARTY-NOTICES.md). Bug reports
and contributions are handled by the developer. The agent never acts on
its own: it commits only in the private repository and only when the
developer tells it to; this repository is committed to by the developer
alone.
