#!/usr/bin/env python3
"""Fixture tests for check-adr-numbering.py.

Every rule is exercised in both directions — a violation it must report and a
near-miss it must not — against a synthetic docs/decisions/ tree in a temp
directory, so the tests say nothing about the real corpus and keep passing as
ADRs are added.

The cases that earned their place: R5's wrapped and hyphenated qualifiers, and
the namespace resolution of a wrapped one. Those were all wrong at some point
during review and none of the hand-run probes caught them.

Run:  python3 -m unittest discover -s tools -p 'test_*.py'
"""

from __future__ import annotations

import importlib.util
import os
import subprocess
import tempfile
import unittest


def load_checker():
    """Import the checker by path; its filename is not a valid module name."""
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location(
        "check_adr_numbering", os.path.join(here, "check-adr-numbering.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECKER = load_checker()

ADR = "# {qualifier} ADR-{number}: {title}\n\n**Status**: accepted\n\n**Date**: 2026-01-01\n"


class CheckerFixture(unittest.TestCase):
    """A throwaway git repo with a controlled docs/decisions/ tree."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = self._tmp.name
        self.addCleanup(self._tmp.cleanup)
        for namespace in ("product", "technical"):
            os.makedirs(os.path.join(self.root, "docs", "decisions", namespace))
        self._original_root = CHECKER.REPO_ROOT
        CHECKER.REPO_ROOT = self.root
        self.addCleanup(setattr, CHECKER, "REPO_ROOT", self._original_root)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    # -- fixture helpers ---------------------------------------------------

    def add_adr(self, namespace, number, title="A decision", heading=None,
                body=""):
        name = f"{number}-{title.lower().replace(' ', '-')}.md"
        text = heading if heading is not None else ADR.format(
            qualifier=namespace.capitalize(), number=number, title=title
        )
        self.write(f"docs/decisions/{namespace}/{name}", text + body)
        return name

    def write(self, relative_path, text):
        full = os.path.join(self.root, relative_path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(text)

    def run_checker(self):
        subprocess.run(["git", "add", "-A"], cwd=self.root, check=True,
                       capture_output=True)
        violations = []
        numbers = CHECKER.check_files(violations)
        CHECKER.check_citations(violations, numbers)
        return violations

    def assert_rule(self, rule, violations, *, expected=True):
        hit = [v for v in violations if f"[{rule}]" in v]
        if expected:
            self.assertTrue(hit, f"expected {rule}, got: {violations}")
        else:
            self.assertFalse(hit, f"unexpected {rule}: {hit}")
        return hit

    def both_namespaces(self, number="0006"):
        """The same number in both trees — the precondition R5 exists for."""
        self.add_adr("product", number, "Product side")
        self.add_adr("technical", number, "Technical side")


class TestFileRules(CheckerFixture):

    def test_r1_reports_duplicate_number_in_one_namespace(self):
        self.add_adr("product", "0001", "First")
        self.add_adr("product", "0001", "Second")
        self.assert_rule("R1", self.run_checker())

    def test_r1_allows_same_number_across_namespaces(self):
        self.both_namespaces("0001")
        self.assert_rule("R1", self.run_checker(), expected=False)

    def test_r2_reports_heading_number_not_matching_filename(self):
        self.add_adr("product", "0001",
                     heading="# Product ADR-0009: Mismatched\n")
        self.assert_rule("R2", self.run_checker())

    def test_r3_reports_wrong_namespace_in_heading(self):
        self.add_adr("product", "0001",
                     heading="# Technical ADR-0001: Wrong tree\n")
        self.assert_rule("R3", self.run_checker())

    def test_r3_reports_missing_qualifier_in_heading(self):
        self.add_adr("product", "0001", heading="# ADR-0001: Bare\n")
        self.assert_rule("R3", self.run_checker())

    def test_clean_tree_produces_no_violations(self):
        self.add_adr("product", "0001")
        self.add_adr("technical", "0001")
        self.assertEqual(self.run_checker(), [])


class TestCitationRules(CheckerFixture):

    def test_r4_reports_citation_of_missing_adr(self):
        self.add_adr("product", "0001")
        self.write("notes.md", "See ADR-0099.\n")
        self.assert_rule("R4", self.run_checker())

    def test_r4_reports_qualifier_naming_the_wrong_namespace(self):
        self.add_adr("product", "0001")
        self.write("notes.md", "See technical ADR-0001.\n")
        self.assert_rule("R4", self.run_checker())

    def test_r5_reports_bare_citation_of_a_shared_number(self):
        self.both_namespaces()
        self.write("notes.md", "See ADR-0006.\n")
        self.assert_rule("R5", self.run_checker())

    def test_r5_allows_bare_citation_of_an_unshared_number(self):
        self.add_adr("product", "0006")
        self.write("notes.md", "See ADR-0006.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_r5_exempts_citations_inside_docs_decisions(self):
        self.both_namespaces()
        self.add_adr("technical", "0007", body="\nAmended by ADR-0006.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_r5_accepts_a_hyphenated_qualifier(self):
        self.both_namespaces()
        self.write("notes.md", "The product-ADR-0006 invariant guard.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_r5_rejects_a_hyphen_joined_word_posing_as_a_qualifier(self):
        self.both_namespaces()
        self.write("notes.md", "A By-product ADR-0006 of the change.\n")
        self.assert_rule("R5", self.run_checker())

    def test_r5_accepts_a_qualifier_carried_over_a_line_wrap(self):
        self.both_namespaces()
        self.write("notes.md", "As described in product\nADR-0006 above.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_r5_accepts_a_wrap_through_a_comment_marker(self):
        self.both_namespaces()
        self.write("Sample.kt", "// Reset to default (product\n"
                                "// ADR-0006) on teardown.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_wrapped_qualifier_is_validated_against_its_namespace(self):
        """A wrap naming the wrong tree must fail R4, not merely escape R5."""
        self.add_adr("product", "0006")
        self.write("notes.md", "As described in technical\nADR-0006 above.\n")
        violations = self.run_checker()
        self.assert_rule("R4", violations)

    def test_a_bare_citation_later_on_a_wrapped_line_is_still_reported(self):
        self.both_namespaces()
        self.both_namespaces("0008")
        self.write("notes.md", "See product\nADR-0006 and also ADR-0008.\n")
        hits = self.assert_rule("R5", self.run_checker())
        self.assertTrue(any("0008" in h for h in hits), hits)
        self.assertFalse(any("0006" in h for h in hits), hits)

    def test_changelog_history_below_the_first_release_is_frozen(self):
        self.both_namespaces()
        self.write("CHANGELOG.md",
                   "# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n"
                   "- Something citing ADR-0006.\n")
        self.assert_rule("R5", self.run_checker(), expected=False)

    def test_changelog_unreleased_section_is_still_checked(self):
        self.both_namespaces()
        self.write("CHANGELOG.md",
                   "# Changelog\n\n## [Unreleased]\n- Something citing ADR-0006.\n"
                   "\n## [1.0.0] - 2026-01-01\n")
        self.assert_rule("R5", self.run_checker())

    def test_binary_files_are_skipped(self):
        self.both_namespaces()
        full = os.path.join(self.root, "blob.bin")
        with open(full, "wb") as handle:
            handle.write(b"\x00\x01ADR-0006\x00")
        self.assertIsNone(CHECKER.read_text("blob.bin"))
        self.assert_rule("R5", self.run_checker(), expected=False)


if __name__ == "__main__":
    unittest.main()
