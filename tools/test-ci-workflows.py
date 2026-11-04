"""Static contracts for the node-based GitHub Actions graph; text-only, no YAML dependency."""
from pathlib import Path
import json
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"
ACTIONS = ROOT / ".github/actions"


def text(name):
    return (WORKFLOWS / name).read_text(encoding="utf-8")


def jobs(source):
    """Top-level job blocks keyed by id (two-space indentation under `jobs:`)."""
    body = source.split("\njobs:\n", 1)[1]
    result, current = {}, None
    for line in body.splitlines(keepends=True):
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            current = match.group(1)
            result[current] = ""
        elif current is not None:
            if line.strip() and not line.startswith("  "):
                break
            result[current] += line
    return result


def needs(block):
    match = re.search(r"^    needs: (.+)$", block, re.M)
    if not match:
        return set()
    value = match.group(1).strip()
    return {item.strip() for item in value.strip("[]").split(",")}


class WorkflowGraph(unittest.TestCase):
    def test_private_repository_workflows_are_gone(self):
        names = {path.name for path in WORKFLOWS.glob("*.yml")}
        for retired in ("sunset-3090-q.yml", "sunset-release.yml", "terminal-regression.yml"):
            self.assertNotIn(retired, names)
        for path in WORKFLOWS.glob("*.yml"):
            self.assertNotIn("DSHA-Sunset-mod-3090-Q", path.read_text(encoding="utf-8"), path.name)

    def test_every_local_reference_exists_and_external_actions_are_pinned(self):
        files = [*WORKFLOWS.glob("*.yml"), *ACTIONS.glob("*/action.yml")]
        for path in files:
            for selected in re.findall(r"uses:\s*([^\s#]+)", path.read_text(encoding="utf-8")):
                with self.subTest(file=path.name, uses=selected):
                    if selected.startswith("./"):
                        self.assertTrue((ROOT / selected[2:]).exists())
                    else:
                        self.assertRegex(selected, r"^[\w.-]+/[\w.-]+@[0-9a-f]{40}$")

    def test_reusable_nodes_are_call_only_and_read_only(self):
        for path in WORKFLOWS.glob("_node-*.yml"):
            source = path.read_text(encoding="utf-8")
            with self.subTest(node=path.name):
                trigger = source.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
                self.assertRegex(trigger, r"^  workflow_call:")
                self.assertEqual(re.findall(r"(?m)^\s+contents:\s*(\w+)", source), ["read"])
                self.assertNotIn("secrets.", source)
                self.assertNotIn("concurrency:", source)
                self.assertIn("persist-credentials: false", source)

    def test_standard_scope_only(self):
        for path in WORKFLOWS.glob("*.yml"):
            source = path.read_text(encoding="utf-8")
            with self.subTest(file=path.name):
                self.assertNotRegex(source, r":app:(?:test|lint|assemble)Low")
                self.assertNotIn("tools/remote", source)
                self.assertNotIn("dsha-connect", source)
        self.assertIn(":app:testStandardDebugUnitTest", text("_node-unit-tests.yml"))
        self.assertIn("--stage package --flavors standard", text("ci-package.yml"))
        result = subprocess.run([sys.executable, str(ROOT / "tools/verify-stability.py"),
                                 "--stage", "software", "--flavors", "both"],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("invalid choice", result.stderr)

    def test_ci_package_keeps_run_path_for_receipt_gate(self):
        gate = (ROOT / "tools/verify-ci-package.py").read_text(encoding="utf-8")
        self.assertIn("'.github/workflows/ci-package.yml'", gate)
        self.assertTrue((WORKFLOWS / "ci-package.yml").is_file())


class ReleaseGraph(unittest.TestCase):
    """Mod 身份正式发布；ReleaseUpstreamGraph 用同一组门禁检查上游身份工作流。"""
    workflow = "release-production.yml"
    identity = "mod"

    def setUp(self):
        self.source = text(self.workflow)
        self.jobs = jobs(self.source)
        self.expected = json.loads((ROOT / f"ci/release-identity.{self.identity}.json").read_text(encoding="utf-8"))

    def test_repository_guard_skips_in_the_other_repository(self):
        bind = self.jobs["bind"]
        self.assertIn("github.event_name == 'workflow_dispatch'", bind)
        self.assertIn(f"github.repository == '{self.expected['repository']}'", bind)
        self.assertIn(f"github.ref == '{self.expected['ref']}'", bind)
        self.assertEqual("github.event.repository.private" in bind, self.expected["privateRepository"])
        other = json.loads((ROOT / f"ci/release-identity.{'upstream' if self.identity == 'mod' else 'mod'}.json")
                           .read_text(encoding="utf-8"))
        self.assertNotIn(other["repository"], bind)
        # 其余节点都直接或间接依赖 bind：bind 被跳过时整条发布链跳过。
        for name, block in self.jobs.items():
            if name != "bind":
                self.assertTrue(needs(block), name)

    def test_build_and_verification_use_only_the_selected_identity(self):
        signed = self.jobs["build-signed"]
        self.assertIn(f":app:assembleStandardRelease -Pdsha.identity={self.identity} ", signed)
        self.assertIn(f"open('ci/release-identity.{self.identity}.json'", signed)
        self.assertIn("${KEY_ALIAS:-" + self.expected["keyAlias"] + "}", signed)
        self.assertIn(f"application-label:'{self.expected['appName']}'", signed)
        self.assertIn("match.group(1) != identity['certificateSha256']", signed)
        self.assertIn("if 'application-debuggable' in badging:", signed)
        self.assertIn("version_name.endswith(('-test', '-SunsetTest', '-island'))", signed)
        tag = self.expected["tagPrefix"]
        self.assertIn(f"re.fullmatch(r'{tag}[0-9]+-[0-9]{{8}}-[0-9]{{6}}', tag)", self.jobs["bind"])
        self.assertIn(f"output.write('apk_name={self.expected['apkPrefix']}-'", self.jobs["bind"])
        other = "upstream" if self.identity == "mod" else "mod"
        self.assertNotIn(f"-Pdsha.identity={other}", self.source)
        self.assertNotIn(f"release-identity.{other}.json", self.source)

    def test_node_set_and_gates_before_signing(self):
        self.assertEqual(set(self.jobs), {"bind", "contracts", "java-format", "host-regression",
                                          "inputs", "unit", "lint", "build-signed", "publish"})
        self.assertEqual(needs(self.jobs["build-signed"]),
                         {"bind", "contracts", "java-format", "host-regression", "inputs", "unit", "lint"})
        self.assertEqual(needs(self.jobs["publish"]), {"bind", "build-signed"})
        for name in ("contracts", "java-format", "host-regression", "inputs"):
            self.assertEqual(needs(self.jobs[name]), {"bind"}, name)

    def test_every_node_checks_out_the_exact_requested_commit(self):
        for name, block in self.jobs.items():
            if name == "publish":
                continue
            with self.subTest(job=name):
                self.assertIn("${{ inputs.expected_sha }}", block)

    def test_secrets_and_write_permission_are_isolated(self):
        for name, block in self.jobs.items():
            with self.subTest(job=name):
                if name == "build-signed":
                    self.assertIn("secrets.DSHA_KEYSTORE_B64", block)
                    self.assertNotIn("contents: write", block)
                    self.assertIn("gradle-cache: 'false'", block)
                elif name == "publish":
                    self.assertIn("contents: write", block)
                    self.assertNotIn("secrets.", block)
                    self.assertNotIn("actions/checkout@", block)
                    self.assertIn("sha256sum -c SHA256SUMS", block)
                else:
                    self.assertNotIn("secrets.", block)
                    self.assertNotIn("contents: write", block)
        self.assertEqual(re.findall(r"(?m)^\s+contents:\s*(\w+)", self.source), ["read", "write"])

    def test_full_unit_tests_require_fixtures_with_one_fork(self):
        unit = self.jobs["unit"]
        self.assertIn("mode: full", unit)
        self.assertIn("require_fixtures: true", unit)
        self.assertIn("forks: 1", unit)
        self.assertIn("verify-unit-results.py --mode full --require-fixtures", self.jobs["build-signed"])
        self.assertIn("sunset_test: false", self.jobs["lint"])

    def test_key_cleanup_always_runs_and_no_key_is_uploaded(self):
        block = self.jobs["build-signed"]
        cleanup = block.split("- name: Remove release private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: always()", cleanup)
        self.assertIn("key.unlink(missing_ok=True)", cleanup)
        self.assertIn("::add-mask::", block)
        upload = block.split("- name: Hand verified release bundle to publish", 1)[1]
        self.assertIn("retention-days: 1", upload)
        self.assertNotRegex(upload, r"(?i)\.(?:jks|keystore|p12|pfx)\b|RUNNER_TEMP")

    def test_existing_tag_is_refused_before_building_and_before_publishing(self):
        self.assertIn("gh release view", self.jobs["bind"])
        self.assertIn("gh release view", self.jobs["publish"])


class ReleaseUpstreamGraph(ReleaseGraph):
    workflow = "release-upstream.yml"
    identity = "upstream"

    def test_upstream_guard_is_private_prerelease_branch(self):
        bind = self.jobs["bind"]
        self.assertIn("github.repository == 'ym2025szz/DSHA-prerelase'", bind)
        self.assertIn("github.event.repository.private", bind)
        self.assertIn("github.ref == 'refs/heads/codex/prerelease-190'", bind)
        self.assertIn("identity = resolver.check()['upstream']", bind)
        self.assertNotIn("dsha-a-su", self.source)


class ModReleaseIdentity(unittest.TestCase):
    def test_mod_release_keeps_public_main_guard(self):
        bind = jobs(text("release-production.yml"))["bind"]
        self.assertIn("github.repository == 'SunsetRNE/DSHA-Sunset-mod'", bind)
        self.assertIn("github.ref == 'refs/heads/main'", bind)
        self.assertIn("if identity['packageName'] != 'io.github.sunsetrne.dsha':", bind)
        self.assertIn("selected = resolver.check()['mod']", bind)


class SourceAndServices(unittest.TestCase):
    def test_source_checks_run_contracts_format_unit_and_host_as_nodes(self):
        graph = jobs(text("ci-fast.yml"))
        self.assertEqual(set(graph), {"contracts", "java-format", "inputs", "unit-source", "host-fast"})
        self.assertEqual(needs(graph["unit-source"]), {"inputs"})
        self.assertEqual(needs(graph["host-fast"]), {"inputs"})
        self.assertIn("mode: source-only", graph["unit-source"])
        self.assertIn("stage: fast", graph["host-fast"])

    def test_only_test_package_and_release_sign(self):
        for path in WORKFLOWS.glob("*.yml"):
            source = path.read_text(encoding="utf-8")
            if path.name in ("release-production.yml", "release-upstream.yml", "test-package.yml"):
                continue
            with self.subTest(file=path.name):
                self.assertNotIn("apksigner", source)
                self.assertNotIn("secrets.", source)
                self.assertNotIn("contents: write", source)

    def test_test_package_identity_is_never_the_production_certificate(self):
        source = text("test-package.yml")
        self.assertIn("-Pdsha.sunsetTest=true -Pdsha.unsignedPackage=true", source)
        self.assertIn('"-Pdsha.identity=$RELEASE_IDENTITY"', source)
        self.assertIn("options: [mod, upstream]", source)
        self.assertIn("default: mod", source)
        self.assertIn('test "$package" = "$expected"', source)
        self.assertIn('--field packageName)$(python3 tools/release-identity.py --identity "$RELEASE_IDENTITY" --field testSuffix)', source)
        self.assertIn('test "$cert" != "$production"', source)
        self.assertIn('test "$cert" != "$upstream"', source)
        self.assertNotIn("gh release", source)
        cleanup = source.split("- name: Remove test private key", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("if: always()", cleanup)
        for step in source.split("\n      - "):
            if "actions/upload-artifact@" in step:
                self.assertNotRegex(step, r"(?i)\.(?:jks|keystore|p12|pfx)\b|RUNNER_TEMP")

    def test_handoff_is_short_lived_and_uncompressed(self):
        source = text("_node-inputs.yml")
        self.assertIn("tools/ci-handoff.py pack", source)
        self.assertIn("retention-days: 1", source)
        self.assertIn("compression-level: 0", source)
        restore = (ACTIONS / "restore-handoff/action.yml").read_text(encoding="utf-8")
        self.assertIn("tools/ci-handoff.py unpack", restore)
        self.assertIn("verify-runtime-descriptor-source.py", restore)

    def test_scheduled_inputs_health_only_reads(self):
        source = text("inputs-health.yml")
        self.assertIn("schedule:", source)
        self.assertIn("tools/ci-assets.py", source)
        self.assertNotIn("upload-artifact", source)


if __name__ == "__main__":
    unittest.main()
