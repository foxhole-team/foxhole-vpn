import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "native-sbom.py"
FIXTURE = Path(__file__).resolve().parent / "fixtures/native-sbom"
SPEC = importlib.util.spec_from_file_location("native_sbom", SCRIPT)
SBOM = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SBOM
SPEC.loader.exec_module(SBOM)


class NativeSbomTest(unittest.TestCase):
    def setUp(self):
        self.tree = (FIXTURE / "tree.txt").read_text()
        self.metadata = json.loads((FIXTURE / "metadata.json").read_text())
        self.capture = json.loads((FIXTURE / "capture.json").read_text())
        self.lock = (FIXTURE / "Cargo.lock").read_bytes()
        self.revision = (FIXTURE / "revision.txt").read_text().strip()

    def inventory(self):
        tree = self.tree.encode()
        metadata = json.dumps(self.metadata).encode()
        capture = copy.deepcopy(self.capture)
        capture["tree"]["sha256"] = hashlib.sha256(tree).hexdigest()
        capture["metadata"]["sha256"] = hashlib.sha256(metadata).hexdigest()
        return SBOM.build_inventory(tree, metadata, capture, self.lock, ["vless"], self.revision, "aarch64-linux-android")

    def add_registry(self, name, linked=True):
        source = "registry+https://github.com/rust-lang/crates.io-index"
        identity = f"{source}#{name}@1.2.3"
        package = {
            "id": identity, "name": name, "version": "1.2.3", "source": source,
            "manifest_path": f"/fixture/cargo/{name}/Cargo.toml", "license": "MIT OR Apache-2.0",
            "license_file": None, "targets": [{"kind": ["lib"]}], "repository": None,
        }
        self.metadata["packages"].append(package)
        self.metadata["resolve"]["nodes"].append({"id": identity, "deps": [], "features": []})
        if linked:
            root_id = self.metadata["resolve"]["root"]
            root = next(n for n in self.metadata["resolve"]["nodes"] if n["id"] == root_id)
            root["deps"].append({"name": name, "pkg": identity, "dep_kinds": [{"kind": None, "target": None}]})
            self.tree += f"1|{name} v1.2.3|\n"
        return package

    def test_real_cargo_fixture_retains_direct_edges_and_build_dependencies(self):
        inventory = self.inventory()
        names = {p["name"] for p in inventory.packages.values()}
        self.assertEqual({"foxcore-android", "derive-tools", "normal-crate", "build-helper"}, names)
        ids = {p["name"]: p["id"] for p in inventory.packages.values()}
        self.assertEqual({ids["derive-tools"], ids["normal-crate"], ids["build-helper"]}, inventory.edges[ids["foxcore-android"]])
        self.assertEqual({ids["build-helper"]}, inventory.edges[ids["normal-crate"]])
        references = {c["bom-ref"] for c in inventory.bom["components"]}
        references.add(inventory.bom["metadata"]["component"]["bom-ref"])
        self.assertEqual(references, {d["ref"] for d in inventory.bom["dependencies"]})
        for dependency in inventory.bom["dependencies"]:
            self.assertLessEqual(set(dependency["dependsOn"]), references)

    def test_registry_purl_supports_osv_without_importing_unused_workspace_packages(self):
        self.add_registry("libc")
        self.add_registry("arti-client", linked=False)
        inventory = self.inventory()
        names = {p["name"] for p in inventory.packages.values()}
        self.assertNotIn("arti-client", names)
        libc = next(c for c in inventory.bom["components"] if c["name"] == "libc")
        self.assertEqual("pkg:cargo/libc@1.2.3", libc["purl"])

    def test_original_bare_depth_format_is_supported_when_unambiguous(self):
        self.tree = self.tree.replace("|", "")
        index = self.capture["tree"]["argv"].index("--format") + 1
        self.capture["tree"]["argv"][index] = "{p}"
        self.assertEqual(4, len(self.inventory().packages))

    def test_malformed_depth_dedup_color_headers_and_extra_roots_are_rejected(self):
        original = self.tree
        variants = [original.replace("1|derive", "3|derive"), original + original.splitlines()[0] + "\n",
                    original.replace("derive)|", "derive)| (*)"), "\x1b[32m" + original,
                    original + "[build-dependencies]\n", original.replace("derive-tools v0.1.0", "unknown v0.1.0")]
        for value in variants:
            with self.subTest(value=value):
                self.tree = value
                with self.assertRaises(SBOM.InputError):
                    self.inventory()

    def test_ambiguous_package_labels_are_rejected(self):
        package = self.add_registry("same-name")
        duplicate = copy.deepcopy(package)
        duplicate["id"] = "opaque-second-cargo-id"
        self.metadata["packages"].append(duplicate)
        with self.assertRaisesRegex(SBOM.InputError, "ambiguous"):
            self.inventory()

    def test_an_edge_not_confirmed_by_metadata_is_rejected(self):
        root = next(n for n in self.metadata["resolve"]["nodes"] if n["id"] == self.metadata["resolve"]["root"])
        root["deps"] = []
        with self.assertRaisesRegex(SBOM.InputError, "edge"):
            self.inventory()

    def test_truncated_root_dependency_set_is_rejected(self):
        self.tree = self.tree.splitlines()[0] + "\n"
        with self.assertRaisesRegex(SBOM.InputError, "incomplete"):
            self.inventory()

    def test_duplicate_json_keys_and_altered_capture_hash_fail_closed(self):
        with self.assertRaises(SBOM.InputError):
            SBOM.read_json('{"version":1,"version":2}')
        capture = copy.deepcopy(self.capture)
        capture["tree"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(SBOM.InputError, "hash"):
            SBOM.build_inventory(self.tree.encode(), (FIXTURE / "metadata.json").read_bytes(), capture,
                                 self.lock, ["vless"], self.revision, "aarch64-linux-android")

    def test_all_targets_cannot_be_mislabeled_as_one_native_abi(self):
        with self.assertRaisesRegex(SBOM.InputError, "Android ABI"):
            SBOM.build_inventory(self.tree.encode(), (FIXTURE / "metadata.json").read_bytes(), self.capture,
                                 self.lock, ["vless"], self.revision, "all")

    def test_a_dev_only_edge_is_not_accepted_as_a_native_dependency(self):
        root = next(n for n in self.metadata["resolve"]["nodes"] if n["id"] == self.metadata["resolve"]["root"])
        root["deps"][0]["dep_kinds"] = [{"kind": "dev", "target": None}]
        with self.assertRaisesRegex(SBOM.InputError, "edge"):
            self.inventory()

    def test_forbidden_components_are_checked_only_on_the_selected_closure(self):
        for name in ("arti-client", "tor-proto", "proto-i2p", "i2pd", "yara-x", "libtor"):
            with self.subTest(name=name):
                self.setUp()
                self.add_registry(name)
                with self.assertRaisesRegex(SBOM.InputError, "forbidden"):
                    self.inventory()

    def test_failed_capture_pruning_wrong_revision_and_wrong_features_are_rejected(self):
        for field, value in (("core_revision", "0" * 40), ("features", ["vless", "tor"])):
            with self.subTest(field=field):
                self.setUp()
                self.capture[field] = value
                with self.assertRaises(SBOM.InputError):
                    self.inventory()
        self.setUp()
        self.capture["tree"]["exit_code"] = 101
        with self.assertRaises(SBOM.InputError):
            self.inventory()
        self.setUp()
        self.capture["tree"]["argv"] += ["--depth", "2"]
        with self.assertRaises(SBOM.InputError):
            self.inventory()

    def test_changed_lockfile_and_default_root_features_are_rejected(self):
        self.lock += b"changed"
        with self.assertRaises(SBOM.InputError):
            self.inventory()
        self.setUp()
        root = next(n for n in self.metadata["resolve"]["nodes"] if n["id"] == self.metadata["resolve"]["root"])
        root["features"].append("default")
        with self.assertRaises(SBOM.InputError):
            self.inventory()

    def test_malformed_schema_types_cannot_pass_as_valid_metadata(self):
        self.capture["schema_version"] = True
        with self.assertRaises(SBOM.InputError):
            self.inventory()
        self.setUp()
        root = next(n for n in self.metadata["resolve"]["nodes"] if n["id"] == self.metadata["resolve"]["root"])
        root["features"] = {"vless": True}
        with self.assertRaises(SBOM.InputError):
            self.inventory()

    def catalog(self, root, inventory):
        (root / "LICENSE.txt").write_text("Fixture notice, not a product license.\n")
        owners = [{k: p.get(k) for k in ("name", "version", "source")} for p in inventory.packages.values()]
        return {"schema_version": 1, "entries": [{
            "id": "active-notice", "owners": owners, "source": "LICENSE.txt", "path": "native/LICENSE.txt",
            "sha256": hashlib.sha256((root / "LICENSE.txt").read_bytes()).hexdigest(),
        }]}

    def test_notice_selection_excludes_inactive_tor_assets_without_reading_them(self):
        inventory = self.inventory()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = self.catalog(root, inventory)
            catalog["entries"].append({"id": "unused-tor", "owners": [{"name": "arti-client", "version": "1", "source": None}],
                                       "source": "missing", "path": "tor/LICENSE", "sha256": "0" * 64})
            selection = SBOM.select_notices(inventory, catalog, root)
            self.assertEqual(["native/LICENSE.txt"], [f["path"] for f in selection.files])
            self.assertEqual([], selection.uncovered)
            output = root / "staged"
            SBOM.stage_notices(selection, output)
            self.assertTrue((output / "MANIFEST.json").is_file())
            self.assertFalse((output / "tor").exists())

    def test_notice_hash_traversal_and_forbidden_active_path_are_rejected(self):
        inventory = self.inventory()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.catalog(root, inventory)
            for key, value in (("sha256", "0" * 64), ("path", "../escape"), ("path", "tor/LICENSE")):
                catalog = copy.deepcopy(original)
                catalog["entries"][0][key] = value
                with self.subTest(key=key, value=value), self.assertRaises(SBOM.InputError):
                    SBOM.select_notices(inventory, catalog, root)

    def test_notice_identifiers_are_required_and_unique_even_for_inactive_packages(self):
        inventory = self.inventory()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = self.catalog(root, inventory)
            for invalid_id in (None, "", 42, True):
                catalog = copy.deepcopy(original)
                catalog["entries"][0]["id"] = invalid_id
                with self.subTest(identifier_type=type(invalid_id).__name__), self.assertRaisesRegex(SBOM.InputError, "duplicate/missing notice id"):
                    SBOM.select_notices(inventory, catalog, root)
            catalog = copy.deepcopy(original)
            duplicate = copy.deepcopy(catalog["entries"][0])
            duplicate["owners"] = [{"name": "inactive-fixture-package", "version": "1", "source": None}]
            duplicate["path"] = "native/other-LICENSE.txt"
            catalog["entries"].append(duplicate)
            with self.assertRaisesRegex(SBOM.InputError, "duplicate/missing notice id"):
                SBOM.select_notices(inventory, catalog, root)

    def test_notice_staging_refuses_uncovered_components_or_existing_directories(self):
        inventory = self.inventory()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            incomplete = SBOM.select_notices(inventory, {"schema_version": 1, "entries": []}, root)
            with self.assertRaises(SBOM.InputError):
                SBOM.stage_notices(incomplete, root / "new")
            complete = SBOM.select_notices(inventory, self.catalog(root, inventory), root)
            with self.assertRaises(SBOM.InputError):
                SBOM.stage_notices(complete, root)

    def test_notice_symlinks_cannot_escape_the_approved_source_root(self):
        inventory = self.inventory()
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / "allowed"
            root.mkdir()
            catalog = self.catalog(root, inventory)
            outside = parent / "outside.txt"
            outside.write_text("outside")
            (root / "LICENSE.txt").unlink()
            (root / "LICENSE.txt").symlink_to(outside)
            catalog["entries"][0]["sha256"] = hashlib.sha256(outside.read_bytes()).hexdigest()
            with self.assertRaisesRegex(SBOM.InputError, "escapes"):
                SBOM.select_notices(inventory, catalog, root)

    def test_output_is_deterministic(self):
        self.assertEqual(self.inventory().bom, self.inventory().bom)

    def test_cli_failure_keeps_an_existing_output_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "old.json"
            output.write_text("previous output\n")
            capture = copy.deepcopy(self.capture)
            capture["tree"]["exit_code"] = 101
            receipt = root / "capture.json"
            receipt.write_text(json.dumps(capture))
            result = subprocess.run([
                sys.executable, str(SCRIPT), "--tree", str(FIXTURE / "tree.txt"),
                "--metadata", str(FIXTURE / "metadata.json"), "--capture", str(receipt),
                "--cargo-lock", str(FIXTURE / "Cargo.lock"), "--features-file", str(FIXTURE / "features.txt"),
                "--revision-file", str(FIXTURE / "revision.txt"), "--target", "aarch64-linux-android",
                "--output", str(output),
            ], capture_output=True, text=True, check=False)
            self.assertEqual(1, result.returncode)
            self.assertIn("did not finish successfully", result.stderr)
            self.assertEqual("previous output\n", output.read_text())


if __name__ == "__main__":
    unittest.main()
