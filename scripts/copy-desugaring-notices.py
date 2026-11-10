"""Add exact NIO desugaring notices without relabelling the retained SDK inventory."""
import hashlib
import json
from pathlib import Path
import shutil


def copy_notices(repo: Path, output: Path, resolved: str):
    config = json.loads((repo / "config/desugaring-notices.json").read_text())
    actual = dict(row.rsplit("=", 1) for row in resolved.split(",") if row)
    if actual != config["artifacts"]:
        raise ValueError("Resolved NIO desugaring artifacts differ from reviewed notices")
    source = repo / "third_party/desugaring-nio"
    if {p.name for p in source.iterdir()} != set(config["files"]):
        raise ValueError("Unexpected NIO notice inventory")
    target = output / "desugaring-nio"
    target.mkdir()
    for name, expected in config["files"].items():
        path = source / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("NIO notice is not a regular file")
        raw = path.read_bytes()
        if len(raw) != expected["bytes"] or hashlib.sha256(raw).hexdigest() != expected["sha256"]:
            raise ValueError("NIO notice digest differs")
        shutil.copyfile(path, target / name)
    (target / "INPUTS.json").write_text(json.dumps(config, indent=2) + "\n")
    (target / "README.txt").write_text(
        "This consumer uses desugar_jdk_libs_nio 2.1.5 and configuration_nio 2.1.5. "
        "INPUTS.json binds both resolved archives and the full source/publisher notices. "
        "The retained android/maven-runtime.cdx.json is the original version 1.0.0 inventory; "
        "its default desugaring coordinates are superseded by this consumer supplement. "
        "The NIO library jar is byte-identical to the default library; its configuration differs. "
        "Source association does not claim a reproduced publisher binary or post-R8/L8 membership.\n"
    )
