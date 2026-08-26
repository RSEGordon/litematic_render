#!/usr/bin/env python3
"""Replace 3dLitematica OBJ textures with real vanilla block PNGs.

3dLitematica flattens Minecraft texture identifiers when it writes map_Kd,
so the MTL file is the reliable list of textures required by a particular
OBJ export.  This script copies those files from an extracted resource pack
without resampling or otherwise changing the Mojang PNG data.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path, PurePosixPath


def material_textures(mtl_path: Path) -> list[str]:
    """Return unique map_Kd references in their MTL order."""
    textures: list[str] = []
    with mtl_path.open(encoding="utf-8-sig") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            keyword, separator, value = line.partition(" ")
            if keyword == "map_Kd" and separator:
                value = value.strip().replace("\\", "/")
                if value and value not in textures:
                    textures.append(value)
    return textures


def find_source(block_dir: Path, texture_ref: str) -> Path | None:
    """Resolve flattened and block-relative map_Kd references."""
    relative = PurePosixPath(texture_ref)
    parts = list(relative.parts)
    while parts and parts[0] in {"textures", "block"}:
        parts.pop(0)

    candidates = []
    if parts:
        candidates.append(block_dir.joinpath(*parts))
        candidates.append(block_dir / parts[-1])
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def replace_textures(
    mtl_path: Path, resource_pack: Path, output_dir: Path
) -> tuple[list[Path], list[str]]:
    block_dir = resource_pack / "assets" / "minecraft" / "textures" / "block"
    if not block_dir.is_dir():
        raise FileNotFoundError(f"vanilla block texture directory not found: {block_dir}")

    output_dir.mkdir(parents=True, exist_ok=True)
    copied: list[Path] = []
    missing: list[str] = []
    for texture_ref in material_textures(mtl_path):
        source = find_source(block_dir, texture_ref)
        if source is None:
            # 3dLitematica's own missing-texture image is not a vanilla block.
            if Path(texture_ref).name != "Minecraft_missing_texture_block.svg.png":
                missing.append(texture_ref)
            continue
        destination = output_dir / Path(texture_ref).name
        shutil.copyfile(source, destination)
        copied.append(destination)
    return copied, missing


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mtl", type=Path, help="3dLitematica .mtl file")
    parser.add_argument("resource_pack", type=Path, help="resource-pack root containing assets/")
    parser.add_argument(
        "--textures",
        type=Path,
        help="output texture directory (default: <mtl directory>/textures)",
    )
    args = parser.parse_args()

    mtl_path = args.mtl.resolve()
    output_dir = args.textures.resolve() if args.textures else mtl_path.parent / "textures"
    copied, missing = replace_textures(mtl_path, args.resource_pack.resolve(), output_dir)
    print(f"copied {len(copied)} vanilla textures to {output_dir}")
    if missing:
        print("missing vanilla textures:")
        for name in missing:
            print(f"  {name}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
