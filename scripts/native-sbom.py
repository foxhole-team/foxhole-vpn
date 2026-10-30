#!/usr/bin/env python3
"""Generate a selected FoxCore Cargo graph, never a whole-workspace/Guard SBOM.

Capture successful stdout separately from stderr, in the pinned Core checkout:
  cargo tree --locked --target TARGET -p foxcore-android --no-default-features \
    --features FEATURES -e normal,build --no-dedupe --prefix depth \
    --format '|{p}|' --color never > tree.txt
  cargo metadata --locked --format-version 1 \
    --manifest-path crates/foxcore-android/Cargo.toml --no-default-features \
    --features FEATURES > metadata.json

capture.json must record schema_version=1, cwd, core_revision, target, features
(a JSON array), cargo_version, cargo_lock_sha256, and tree/metadata objects with
argv (the exact command array), exit_code=0 and sha256 of the captured stdout.
See scripts/tests/fixtures/native-sbom/capture.json for a real Cargo fixture.
Record this receipt at successful capture time, from the same pinned checkout
and lockfile as the native build. Do not back-fill it for partial output.
The explicit android64 delivery also records androidDeliveryProfile, abis and
targets in the source manifest and each target capture. Select it with
--android-delivery-profile android64; missing profile retains the legacy path.
Metadata is used for identities/licenses and to confirm observed edges only;
an unfiltered metadata superset avoids losing host build-dependency edges.

Optional notice catalog: schema_version=1, entries=[{id, source, path, sha256,
owners:[{name, version, source}], require_all:false}]. source/path are relative
input/output file paths. Each owner source is Cargo's source string or null.
Only owners in the selected graph activate an entry. Staging requires coverage
of every selected Cargo package and refuses existing output directories.
Catalog ownership/content is reviewed input, not inferred legal compliance.

This is a Cargo-resolved normal/build graph, including build-time dependencies.
It does not claim a linked-binary inventory or enumerate bundled C/C++/NDK
components. cargo tree itself does not guarantee exact build equivalence:
https://doc.rust-lang.org/cargo/commands/cargo-tree.html
https://doc.rust-lang.org/cargo/commands/cargo-metadata.html
"""

import argparse
import importlib.util
import io
import stat
import tarfile
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import sys
import tempfile
from urllib.parse import quote, urlparse


ROOT = Path(__file__).resolve().parents[1]
CRATES_IO = {"registry+https://github.com/rust-lang/crates.io-index", "sparse+https://index.crates.io/"}
ANDROID_TARGETS = {"aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"}
ANDROID4_ABIS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
ANDROID4_TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi", "i686-linux-android", "x86_64-linux-android"]
ANDROID64_ABIS = ["arm64-v8a", "x86_64"]
ANDROID64_TARGETS = ["aarch64-linux-android", "x86_64-linux-android"]
ARMV7_ABIS = ["armeabi-v7a"]
ARMV7_TARGETS = ["armv7-linux-androideabi"]
ANDROID3_ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64"]
ANDROID3_TARGETS = ["aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android"]
FORBIDDEN = re.compile(r"(?:^|[-_/])(?:arti|tor|i2p|i2pd|yara)(?:$|[-_/])|(?:^|[-_/])libtor(?:$|[-_/])", re.I)
HASH = re.compile(r"[0-9a-f]{64}")


class InputError(ValueError):
    pass


class StrictParser(argparse.ArgumentParser):
    def error(self, message):
        raise InputError(message)


def require(condition, message):
    if not condition:
        raise InputError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_delivery_profile(record, delivery_profile):
    require(delivery_profile in ("legacy4", "android64", "android-armv7", "android3", "android4"), "Unknown Android delivery profile")
    require(record.get("androidDeliveryProfile", "legacy4") == delivery_profile,
            "Android delivery profile differs from the explicit selection")
    if delivery_profile == "android4":
        require(record.get("abis") == ANDROID4_ABIS and record.get("targets") == ANDROID4_TARGETS,
                "android4 requires its exact four ABI/target authorities")
    elif delivery_profile == "android64":
        require(record.get("abis") == ANDROID64_ABIS and record.get("targets") == ANDROID64_TARGETS,
                "android64 needs its exact two ABI/target authorities")
    elif delivery_profile == "android3":
        require(record.get("abis") == ANDROID3_ABIS and record.get("targets") == ANDROID3_TARGETS,
                "android3 requires its exact three ABI/target authorities")
    elif delivery_profile == "android-armv7":
        require(record.get("abis") == ARMV7_ABIS and record.get("targets") == ARMV7_TARGETS,
                "ARMv7 requires its exact ABI/target authority")


_FORK_MODULE = None

def fork_module():
    global _FORK_MODULE
    if _FORK_MODULE is None:
        spec = importlib.util.spec_from_file_location("rustls_fork_origin", ROOT / "scripts/rustls-fork-origin.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _FORK_MODULE = module
    return _FORK_MODULE

def load_fork_source(archive_bytes, manifest_bytes, capture, lock_bytes, delivery_profile="legacy4"):
    require(len(archive_bytes) <= 128 * 1024**2 and len(manifest_bytes) <= 4 * 1024**2,
            "Fork source capture exceeds byte bounds")
    manifest = read_json(manifest_bytes)
    validate_delivery_profile(manifest, delivery_profile)
    validate_delivery_profile(capture, delivery_profile)
    require(manifest.get("archiveSha256") == sha256(archive_bytes) ==
            capture.get("source_archive_sha256") and
            sha256(manifest_bytes) == capture.get("source_manifest_sha256"),
            "Fork source archive/manifest was not bound at successful capture")
    expected = manifest.get("files")
    require(isinstance(expected, dict) and expected and
            all(isinstance(n, str) and isinstance(h, str) and HASH.fullmatch(h) for n, h in expected.items()),
            "Expected the genuine producer source file digest map")
    files = {}
    seen = set()
    directories = {str(parent) for name in expected for parent in PurePosixPath(name).parents
                   if str(parent) != "."}
    with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:*") as archive:
        for member in archive:
            name = member.name.removesuffix("/") if member.isdir() else member.name
            path = PurePosixPath(name)
            require(str(path) == name and not path.is_absolute() and
                    not any(p in ("..", ".git", "AGENTS.md") for p in path.parts) and name not in seen,
                    "Invalid or duplicate fork source archive path")
            seen.add(name)
            if member.isdir():
                require(name in directories and member.mode == 0o755 and member.size == 0,
                        "Unexpected fork source archive directory")
                continue
            require(member.isfile() and str(path) == member.name and not path.is_absolute() and
                    not any(p in ("..", ".git", "AGENTS.md") for p in path.parts) and
                    member.name in expected and member.name not in files and 0 <= member.size <= 128 * 1024**2,
                    "Invalid fork source archive member")
            data = archive.extractfile(member).read()
            require(sha256(data) == expected[member.name], "Fork captured source bytes changed")
            files[member.name] = (data, member.mode)
    require(set(files) == set(expected) and files["Cargo.lock"][0] == lock_bytes,
            "Fork captured source inventory/lock differs")
    module = fork_module()
    origin = module.validate_origin(files.__getitem__, files)
    require(capture.get("source_fork_origin_sha256") == origin["provenanceSha256"],
            "Capture did not bind the exact frozen source fork origin")
    return origin


def unique_object(items):
    result = {}
    for key, value in items:
        require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise InputError(f"invalid JSON constant: {value}")


def read_json(data):
    try:
        return json.loads(data, object_pairs_hook=unique_object, parse_constant=reject_constant)
    except (ValueError, UnicodeError) as error:
        raise InputError(f"invalid JSON: {error}") from error


def features(value):
    tokens = re.split(r"[,\s]+", value.strip()) if isinstance(value, str) else value
    require(isinstance(tokens, list) and tokens, "features must be a non-empty list")
    require(all(isinstance(t, str) and re.fullmatch(r"[a-z][a-z0-9_-]*", t) for t in tokens), "invalid feature name")
    require(len(set(tokens)) == len(tokens), "duplicate feature")
    require(not any(t in {"default", "shipped", "onion-service"} or FORBIDDEN.search(t) for t in tokens), "forbidden feature")
    return sorted(tokens)


def one(value, label, required=True):
    require(isinstance(value, list) and len(value) == 1 or value is None and not required, f"expected one {label}")
    return value[0] if value is not None else None


def command_options(record, kind, target, selected_features):
    require(isinstance(record, dict) and type(record.get("exit_code")) is int and record["exit_code"] == 0,
            f"{kind} capture did not finish successfully")
    argv = record.get("argv")
    require(isinstance(argv, list) and len(argv) > 2 and all(isinstance(a, str) for a in argv), f"missing {kind} argv")
    require(PurePosixPath(argv[0]).name == "cargo" and argv[1] == kind, f"unexpected {kind} command")
    parser = StrictParser(add_help=False, allow_abbrev=False)
    for option in ("--manifest-path", "--features", "--color"):
        parser.add_argument(option, action="append")
    for option in ("--locked", "--offline", "--no-default-features"):
        parser.add_argument(option, action="count", default=0)
    if kind == "tree":
        parser.add_argument("-p", "--package", action="append")
        parser.add_argument("--target", action="append")
        parser.add_argument("-e", "--edges", action="append")
        parser.add_argument("--prefix", action="append")
        parser.add_argument("-f", "--format", action="append")
        parser.add_argument("--no-dedupe", action="count", default=0)
    else:
        parser.add_argument("--format-version", action="append")
        parser.add_argument("--filter-platform", action="append")
    args = parser.parse_args(argv[2:])
    require(args.locked == 1 and args.no_default_features == 1 and args.offline <= 1, f"{kind} needs --locked and --no-default-features")
    require(features(one(args.features, "--features")) == selected_features, f"{kind} feature mismatch")
    color = one(args.color, "--color", required=kind == "tree")
    require(color in (None, "never"), "ANSI color is not supported")
    if kind == "tree":
        require(one(args.package, "--package") == "foxcore-android", "tree must select only foxcore-android")
        require(one(args.target, "--target") == target, "tree target mismatch")
        require(one(args.edges, "--edges") in ("normal,build", "build,normal"), "tree must include normal and build edges only")
        require(args.no_dedupe == 1 and one(args.prefix, "--prefix") == "depth", "tree needs unpruned depth output with --no-dedupe")
        require(one(args.format, "--format") in ("{p}", "|{p}|"), "unsupported package format")
    else:
        require(one(args.format_version, "--format-version") == "1", "metadata format version must be 1")
        require(one(args.filter_platform, "--filter-platform", False) in (None, target), "metadata target mismatch")
    one(args.manifest_path, "--manifest-path", required=kind == "metadata")
    return args


def package_label(package):
    name, version = package.get("name"), package.get("version")
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_-]*", name), "invalid package name")
    require(isinstance(version, str) and re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)*", version), "invalid package version")
    targets = package.get("targets")
    require(isinstance(targets, list) and all(isinstance(t, dict) and isinstance(t.get("kind"), list) for t in targets),
            f"missing Cargo targets for {name}")
    proc_macro = any("proc-macro" in target.get("kind", []) for target in targets)
    label = f"{name} v{version}" + (" (proc-macro)" if proc_macro else "")
    source = package.get("source")
    require("source" in package and (source is None or isinstance(source, str)), "invalid Cargo source identity")
    if source is None:
        manifest = package.get("manifest_path")
        require(isinstance(manifest, str) and PurePosixPath(manifest).is_absolute(), f"invalid manifest path for {name}")
        return label + f" ({PurePosixPath(manifest).parent})"
    if source in CRATES_IO:
        return label
    return None


def parse_tree(text, packages, tree_format):
    labels = {}
    for identity, package in packages.items():
        label = package_label(package)
        if label is not None:
            labels.setdefault(label, []).append(identity)
    require(text and text.endswith("\n"), "tree must be complete newline-terminated stdout")
    require(not any(ord(c) < 32 and c != "\n" for c in text), "tree contains control/ANSI characters")
    lines = text.splitlines()
    require(len(lines) <= 2_000_000, "tree exceeds parser size limit")
    stack, edges, root = [], {}, None
    for line_number, line in enumerate(lines, 1):
        require(line and not line.endswith(" (*)"), f"tree line {line_number}: empty/deduplicated output")
        candidates = []
        if tree_format == "|{p}|":
            match = re.fullmatch(r"(0|[1-9][0-9]*)\|(.+)\|", line)
            if match:
                candidates = [(int(match[1]), identity) for identity in labels.get(match[2], [])]
        else:
            digits = re.match(r"[0-9]+", line)
            if digits:
                for split in range(1, len(digits[0]) + 1):
                    depth = int(line[:split])
                    if str(depth) == line[:split]:
                        candidates += [(depth, identity) for identity in labels.get(line[split:], [])]
        require(candidates, f"tree line {line_number}: unknown/unsupported package label or format")
        require(len(candidates) == 1, f"tree line {line_number}: ambiguous package identity/depth")
        depth, identity = candidates[0]
        require(depth <= 256 and depth <= len(stack), f"tree line {line_number}: invalid depth jump")
        if root is None:
            require(depth == 0, "tree must start at depth zero")
            root = identity
        else:
            require(depth > 0, "tree has more than one root")
        stack = stack[:depth]
        require(identity not in stack, "package cycle cannot be represented unambiguously")
        edges.setdefault(identity, set())
        if depth:
            edges[stack[-1]].add(identity)
        stack.append(identity)
    require(root is not None, "empty tree")
    return root, edges


@dataclass
class Inventory:
    bom: dict
    packages: dict
    edges: dict
    root_id: str
    references: dict
    fork_origin: dict | None = None
    delivery_profile: str = "legacy4"


def build_inventory(tree_bytes, metadata_bytes, capture, lock_bytes, expected_features, revision, target, fork_origin=None,
                    delivery_profile="legacy4"):
    selected_features = features(expected_features)
    require(isinstance(capture, dict) and type(capture.get("schema_version")) is int and capture["schema_version"] == 1,
            "unsupported capture schema")
    validate_delivery_profile(capture, delivery_profile)
    require(delivery_profile != "android64" or target in ANDROID64_TARGETS,
            "android64 target is outside its exact two ABI authorities")
    require(delivery_profile != "android4" or target in ANDROID4_TARGETS, "android4 target is outside its exact authorities")
    require(delivery_profile != "android3" or target in ANDROID3_TARGETS, "android3 target is outside its exact authorities")
    require(delivery_profile != "android-armv7" or target in ARMV7_TARGETS,
            "ARMv7 target is outside its exact authority")
    require(re.fullmatch(r"[0-9a-f]{40}", revision) is not None and capture.get("core_revision") == revision, "Core revision mismatch")
    require(target in ANDROID_TARGETS and capture.get("target") == target, "target must match one Android ABI")
    require(isinstance(capture.get("features"), list) and features(capture["features"]) == selected_features, "capture feature mismatch")
    require(isinstance(capture.get("cargo_version"), str) and capture["cargo_version"].startswith("cargo "), "missing cargo version")
    require(capture.get("cargo_lock_sha256") == sha256(lock_bytes), "Cargo.lock hash mismatch")
    options = {}
    for key, raw in (("tree", tree_bytes), ("metadata", metadata_bytes)):
        options[key] = command_options(capture.get(key), key, target, selected_features)
        require(capture[key].get("sha256") == sha256(raw), f"{key} capture hash mismatch")
    metadata = read_json(metadata_bytes)
    require(isinstance(metadata, dict) and type(metadata.get("version")) is int and metadata["version"] == 1,
            "unsupported metadata format")
    require(isinstance(metadata.get("packages"), list), "missing metadata packages")
    packages = {}
    for package in metadata.get("packages", []):
        require(isinstance(package, dict), "invalid metadata package")
        identity = package.get("id")
        require(isinstance(identity, str) and identity not in packages, "missing/duplicate Cargo package id")
        packages[identity] = package
    root_id, edges = parse_tree(tree_bytes.decode("utf-8"), packages, one(options["tree"].format, "--format"))
    require(packages[root_id]["name"] == "foxcore-android", "unexpected tree root")
    resolve = metadata.get("resolve")
    require(isinstance(resolve, dict) and resolve.get("root") in (None, root_id), "metadata root mismatch")
    require(isinstance(resolve.get("nodes"), list), "missing metadata resolve nodes")
    nodes = {}
    for node in resolve.get("nodes", []):
        require(isinstance(node, dict), "invalid metadata resolve node")
        require(node.get("id") in packages and node["id"] not in nodes, "unknown/duplicate metadata node")
        nodes[node["id"]] = node
    root_features = nodes.get(root_id, {}).get("features")
    require(isinstance(root_features, list) and all(isinstance(f, str) for f in root_features) and
            sorted(root_features) == selected_features, "resolved root features differ from the selected build")
    cwd = capture.get("cwd")
    require(isinstance(cwd, str) and PurePosixPath(cwd).is_absolute(), "capture cwd must be absolute")
    for kind, args in options.items():
        manifest = one(args.manifest_path, "--manifest-path", required=kind == "metadata")
        if manifest is not None:
            actual = posixpath.normpath(posixpath.join(cwd, manifest))
            require(actual == packages[root_id]["manifest_path"], f"{kind} selected manifest mismatch")
    selected = {identity: packages[identity] for identity in edges}
    local_rustls = [package for package in selected.values() if package.get("source") is None and package["name"] == "rustls"]
    if local_rustls:
        require(len(local_rustls) == 1 and local_rustls[0]["version"] == "0.23.46" and
                local_rustls[0]["id"] not in metadata.get("workspace_members", []) and
                fork_origin is not None and fork_origin.get("sourceBound") is True,
                "Selected modified rustls needs exact producer-bound source admission")
    else:
        require(fork_origin is None, "Fork admission is not in the actual selected graph")
    for identity, destinations in edges.items():
        require(not FORBIDDEN.search(packages[identity]["name"]), f"forbidden native package: {packages[identity]['name']}")
        require(identity in nodes, "selected package missing from resolve nodes")
        require(isinstance(nodes[identity].get("deps"), list), "missing resolved dependency edges")
        allowed = set()
        for dependency in nodes[identity].get("deps", []):
            require(isinstance(dependency, dict) and dependency.get("pkg") in packages, "invalid resolved dependency edge")
            kinds = dependency.get("dep_kinds")
            require(isinstance(kinds, list) and kinds, "missing dependency edge kinds")
            require(all(isinstance(k, dict) and "kind" in k and k["kind"] in (None, "build", "dev") for k in kinds),
                    "unknown Cargo dependency kind")
            if any(kind.get("kind") in (None, "build") for kind in kinds):
                allowed.add(dependency.get("pkg"))
        require(destinations <= allowed, f"tree edge missing from normal/build metadata: {packages[identity]['name']}")
        if identity == root_id:
            require(destinations == allowed, "root dependency edges are incomplete; capture an unpruned tree")
    refs = {identity: "urn:foxhole:cargo:" + sha256(identity.encode()) for identity in selected}
    components = {identity: component(package, refs[identity], metadata.get("workspace_root"), revision, fork_origin)
                  for identity, package in selected.items()}
    bom = {
        "bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
        "metadata": {"component": components[root_id], "properties": [
            {"name": "foxhole:native:inventory", "value": "cargo-selected-normal-build-closure"},
            {"name": "foxhole:native:target", "value": target},
            {"name": "foxhole:native:core-revision", "value": revision},
            {"name": "foxhole:native:features", "value": ",".join(selected_features)},
            {"name": "foxhole:native:cargo-version", "value": capture["cargo_version"]},
            {"name": "foxhole:native:tree-sha256", "value": sha256(tree_bytes)},
            {"name": "foxhole:native:metadata-sha256", "value": sha256(metadata_bytes)},
            {"name": "foxhole:native:cargo-lock-sha256", "value": sha256(lock_bytes)},
            {"name": "foxhole:native:limitations", "value": "Cargo packages including build tools; no linked-binary assertion; bundled C/C++ and toolchain components require separate provenance."},
        ]},
        "components": [components[i] for i in sorted(selected, key=lambda i: (selected[i]["name"], selected[i]["version"], i)) if i != root_id],
        "dependencies": [{"ref": refs[i], "dependsOn": sorted(refs[d] for d in edges[i])} for i in sorted(selected)],
    }
    if fork_origin is not None:
        bom["metadata"]["properties"].append({"name": "foxhole:native:vendored-fork-components", "value": "1"})
    if delivery_profile != "legacy4":
        bom["metadata"]["properties"].append({"name": "foxhole:native:android-delivery-profile", "value": delivery_profile})
    return Inventory(bom, selected, edges, root_id, refs, fork_origin, delivery_profile)


def component(package, reference, workspace_root, revision, fork_origin=None):
    result = {"type": "library", "bom-ref": reference, "name": package["name"], "version": package["version"]}
    source = package.get("source")
    properties = []
    if source is None and package["name"] == "rustls":
        require(fork_origin is not None and fork_origin.get("sourceBound") is True and
                package["version"] == "0.23.46" and
                PurePosixPath(package["manifest_path"]) == PurePosixPath(workspace_root) / "vendor/rustls/Cargo.toml",
                "Unadmitted modified rustls path source")
        module = fork_module()
        result["purl"] = module.identity_url(fork_origin, revision)
        properties = module.component_properties(fork_origin, revision)
    elif source in CRATES_IO:
        result["purl"] = f"pkg:cargo/{quote(package['name'], safe='-._~')}@{quote(package['version'], safe='-._~')}"
        properties.append({"name": "cargo:source", "value": source})
    else:
        require(source is None and isinstance(workspace_root, str) and PurePosixPath(workspace_root).is_absolute(),
                "unsupported selected package source")
        manifest = PurePosixPath(package["manifest_path"])
        require(manifest.is_relative_to(PurePosixPath(workspace_root)), "external path package lacks pinned source provenance")
        properties += [{"name": "cargo:workspace-manifest", "value": str(manifest.relative_to(workspace_root))},
                       {"name": "cargo:source-revision", "value": revision}]
    license_value = package.get("license")
    require(license_value is None or isinstance(license_value, str), "invalid declared license")
    require(package.get("license_file") is None or isinstance(package["license_file"], str), "invalid license-file")
    if license_value:
        result["licenses"] = [{"license": {"name": license_value}}]
    elif package.get("license_file"):
        result["licenses"] = [{"license": {"name": "Declared Cargo license-file"}}]
    repository = package.get("repository")
    if isinstance(repository, str):
        url = urlparse(repository)
        if url.scheme in ("http", "https") and url.netloc and not url.username:
            result["externalReferences"] = [{"type": "vcs", "url": repository}]
    result["properties"] = properties
    return result


@dataclass
class NoticeSelection:
    files: list
    uncovered: list
    sbom_sha256: str
    delivery_profile: str = "legacy4"


def safe_relative(value):
    require(isinstance(value, str) and value and "\\" not in value and all(ord(c) >= 32 for c in value), "invalid notice path")
    path = PurePosixPath(value)
    require(path.parts and not path.is_absolute() and all(p not in ("..", ".git", "AGENTS.md") for p in path.parts), "unsafe notice path")
    require(str(path) == value and value != "MANIFEST.json", "non-canonical/reserved notice path")
    return path


def select_notices(inventory, catalog, notice_root):
    require(isinstance(catalog, dict) and type(catalog.get("schema_version")) is int and catalog["schema_version"] == 1 and
            isinstance(catalog.get("entries"), list),
            "invalid notice catalog")
    files, covered, paths, entry_ids = [], set(), set(), set()
    root = notice_root.resolve(strict=True)
    require(root.is_dir(), "notice root must be a directory")
    for entry in catalog["entries"]:
        require(isinstance(entry, dict), "invalid notice entry")
        notice_id = entry.get("id")
        require(isinstance(notice_id, str) and notice_id and notice_id not in entry_ids, "duplicate/missing notice id")
        entry_ids.add(notice_id)
        owners = entry.get("owners")
        require(isinstance(owners, list) and owners, "notice needs explicit package owners")
        matches = []
        for owner in owners:
            require(isinstance(owner, dict) and set(owner) == {"name", "version", "source"}, "notice owner needs name, version and source")
            found = [i for i, p in inventory.packages.items() if all(p.get(k) == v for k, v in owner.items())]
            require(len(found) <= 1, "ambiguous notice package owner")
            matches += found
        require(type(entry.get("require_all", False)) is bool, "invalid require_all flag")
        if not matches or entry.get("require_all", False) and len(matches) != len(owners):
            continue
        relative = safe_relative(entry.get("path"))
        require(not FORBIDDEN.search(str(relative)), f"forbidden notice path: {relative}")
        require(str(relative) not in paths, "duplicate staged notice path")
        paths.add(str(relative))
        source = root / safe_relative(entry.get("source"))
        resolved = source.resolve(strict=True)
        require(resolved.is_relative_to(root) and resolved.is_file(), "notice source escapes its allowed root")
        require(resolved.stat().st_size <= 8 * 1024 * 1024, "notice file too large")
        data = resolved.read_bytes()
        require(data and isinstance(entry.get("sha256"), str) and HASH.fullmatch(entry["sha256"]) and sha256(data) == entry["sha256"], "notice hash mismatch")
        covered.update(matches)
        files.append({"path": str(relative), "sha256": sha256(data), "owners": sorted(inventory.references[i] for i in set(matches)), "data": data})
    if inventory.fork_origin is not None:
        owners = [i for i, package in inventory.packages.items() if package["name"] == "rustls" and
                  package["version"] == "0.23.46" and package.get("source") is None]
        require(len(owners) == 1, "Selected fork notice owner differs")
        for relative, data in inventory.fork_origin["noticeFiles"]:
            require(str(safe_relative(relative)) == relative and relative not in paths,
                    "Duplicate or invalid generated fork notice")
            paths.add(relative)
            files.append({"path": relative, "sha256": sha256(data),
                          "owners": [inventory.references[owners[0]]], "data": data})
        covered.update(owners)
    uncovered = [{"name": inventory.packages[i]["name"], "version": inventory.packages[i]["version"],
                  "source": inventory.packages[i].get("source"), "bom-ref": inventory.references[i],
                  "declared_license": inventory.packages[i].get("license"),
                  "declared_license_file": inventory.packages[i].get("license_file"),
                  "manifest_path": inventory.packages[i].get("manifest_path")}
                 for i in sorted(set(inventory.packages) - covered)]
    bom_bytes = (json.dumps(inventory.bom, indent=2, sort_keys=True) + "\n").encode()
    return NoticeSelection(sorted(files, key=lambda f: f["path"]), uncovered, sha256(bom_bytes), inventory.delivery_profile)


def notice_report(selection):
    result = {"schema_version": 1, "sbom_sha256": selection.sbom_sha256,
              "files": [{k: v for k, v in f.items() if k != "data"} for f in selection.files],
              "uncovered": selection.uncovered, "coverage": "complete" if not selection.uncovered else "incomplete"}
    if selection.delivery_profile == "android4":
        result.update(androidDeliveryProfile="android4", abis=ANDROID4_ABIS, targets=ANDROID4_TARGETS)
    elif selection.delivery_profile == "android64":
        result.update(androidDeliveryProfile="android64", abis=ANDROID64_ABIS, targets=ANDROID64_TARGETS)
    elif selection.delivery_profile == "android3":
        result.update(androidDeliveryProfile="android3", abis=ANDROID3_ABIS, targets=ANDROID3_TARGETS)
    elif selection.delivery_profile == "android-armv7":
        result.update(androidDeliveryProfile="android-armv7", abis=ARMV7_ABIS, targets=ARMV7_TARGETS)
    return result


def stage_notices(selection, output):
    require(not selection.uncovered, "notice catalog does not cover every selected Cargo package")
    require(not output.exists(), "notice output must not exist; refusing to merge stale/Guard notices")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".native-notices-", dir=output.parent))
    try:
        for item in selection.files:
            target = temporary / item["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(item["data"])
        (temporary / "MANIFEST.json").write_text(json.dumps(notice_report(selection), indent=2, sort_keys=True) + "\n")
        temporary.rename(output)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def write_json(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(document, stream, indent=2, sort_keys=True)
        stream.write("\n")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ("tree", "metadata", "capture", "cargo-lock", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--target", required=True)
    parser.add_argument("--features-file", type=Path, default=ROOT / "config/foxcore-features.txt")
    parser.add_argument("--revision-file", type=Path, default=ROOT / "config/foxcore-revision.txt")
    parser.add_argument("--notice-catalog", type=Path)
    parser.add_argument("--notice-root", type=Path)
    parser.add_argument("--notice-selection", type=Path)
    parser.add_argument("--notices-output", type=Path)
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--source-manifest", type=Path)
    parser.add_argument("--android-delivery-profile", choices=("legacy4", "android64", "android-armv7", "android3", "android4"), default="legacy4")
    args = parser.parse_args()
    try:
        capture = read_json(args.capture.read_bytes())
        require(bool(args.source_archive) == bool(args.source_manifest), "Fork source archive/manifest are a pair")
        origin = (load_fork_source(args.source_archive.read_bytes(), args.source_manifest.read_bytes(),
                                   capture, args.cargo_lock.read_bytes(), args.android_delivery_profile) if args.source_archive else None)
        inventory = build_inventory(args.tree.read_bytes(), args.metadata.read_bytes(), capture,
                                    args.cargo_lock.read_bytes(), features(args.features_file.read_text()),
                                    args.revision_file.read_text().strip(), args.target, fork_origin=origin,
                                    delivery_profile=args.android_delivery_profile)
        if any((args.notice_catalog, args.notice_root, args.notice_selection, args.notices_output)):
            require(args.notice_catalog is not None and args.notice_root is not None, "notice options need catalog and source root")
            selection = select_notices(inventory, read_json(args.notice_catalog.read_bytes()), args.notice_root)
            if args.notices_output:
                stage_notices(selection, args.notices_output)
            if args.notice_selection:
                write_json(args.notice_selection, notice_report(selection))
        write_json(args.output, inventory.bom)
        print(f"Selected {len(inventory.packages)} Cargo packages and {sum(map(len, inventory.edges.values()))} observed edges.")
        return 0
    except (InputError, OSError, UnicodeError) as error:
        print(f"native-sbom: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
