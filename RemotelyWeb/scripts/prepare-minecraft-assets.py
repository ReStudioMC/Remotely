#!/usr/bin/env python3

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


ASSET_PATH = re.compile(r"assets/[a-z0-9_.-]+/textures/[a-z0-9_./-]+\.png")


def read_assets(archives):
    assets = {}
    for archive in archives:
        with zipfile.ZipFile(archive) as source:
            for entry in source.infolist():
                name = entry.filename.lower()
                if not ASSET_PATH.fullmatch(name) or any(part in ("", ".", "..") for part in name.split("/")):
                    continue
                if entry.file_size < 24 or entry.file_size > 8 * 1024 * 1024:
                    raise ValueError(f"Invalid Texture Size: {entry.filename}")
                data = source.read(entry)
                if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
                    raise ValueError(f"Invalid PNG: {entry.filename}")
                width = int.from_bytes(data[16:20], "big")
                height = int.from_bytes(data[20:24], "big")
                if not (0 < width <= 8192 and 0 < height <= 8192):
                    raise ValueError(f"Invalid PNG Dimensions: {entry.filename}")
                assets[name[7:]] = (data, width, height)
    if not assets or "minecraft/textures/gui/container/generic_54.png" not in assets:
        raise ValueError("Minecraft Client Textures Are Missing")
    return assets


def catalog_script(identity, version, rows):
    payload = json.dumps(rows, separators=(",", ":"))
    return """(() => {
    const index = new Map(ROWS);
    const root = new URL('assets/minecraft/IDENTITY/', document.baseURI).href;
    const key = path => String(path || '').toLowerCase();
    window.__reScreenMinecraftAssets = Object.freeze({
        identity: 'IDENTITY',
        version: 'VERSION',
        contains: path => index.has(key(path)),
        source: path => index.has(key(path)) ? root + key(path) : '',
        pixelated: url => String(url || '').startsWith(root),
        width: path => index.get(key(path))?.[0] || 0,
        height: path => index.get(key(path))?.[1] || 0,
        list: (namespace, prefix, suffix) => {
            const start = key(namespace || 'minecraft') + '/' + key(prefix);
            const end = key(suffix);
            return Array.from(index.keys()).filter(path => path.startsWith(start) && path.endsWith(end)).join('\\n');
        }
    });
})();
""".replace("ROWS", payload).replace("IDENTITY", identity).replace("VERSION", version)


def prepare(destination, version, client, mods):
    if not re.fullmatch(r"[a-zA-Z0-9_.-]{1,64}", version):
        raise ValueError("Unsafe Minecraft Version")
    archives = [client, *mods]
    assets = read_assets(archives)
    digest = hashlib.sha256()
    digest.update(version.encode())
    records = []
    rows = []
    for path, (data, width, height) in sorted(assets.items()):
        asset_hash = hashlib.sha256(data).hexdigest()
        digest.update(path.encode())
        digest.update(bytes.fromhex(asset_hash))
        records.append({"path": path, "sha256": asset_hash, "size": len(data), "width": width, "height": height})
        rows.append([path, [width, height]])
    identity = digest.hexdigest()
    asset_root = destination / "assets" / "minecraft"
    version_root = asset_root / identity
    for path, (data, _, _) in assets.items():
        target = version_root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    catalog = catalog_script(identity, version, rows)
    asset_root.mkdir(parents=True, exist_ok=True)
    (asset_root / "catalog.js").write_text(catalog, encoding="utf-8")
    (asset_root / "manifest.json").write_text(json.dumps({"schema": 1, "identity": identity, "version": version, "assets": records}, separators=(",", ":")), encoding="utf-8")
    verify(destination)
    print(json.dumps({"version": version, "identity": identity, "textures": len(records), "bytes": sum(record["size"] for record in records)}))


def verify(destination):
    root = destination / "assets" / "minecraft"
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    identity = manifest["identity"]
    if not re.fullmatch(r"[0-9a-f]{64}", identity) or manifest["schema"] != 1:
        raise ValueError("Invalid Minecraft Asset Manifest")
    catalog = (root / "catalog.js").read_text(encoding="utf-8")
    if f"identity: '{identity}'" not in catalog or f"assets/minecraft/{identity}/" not in catalog:
        raise ValueError("Minecraft Asset Catalog Identity Mismatch")
    digest = hashlib.sha256()
    digest.update(manifest["version"].encode())
    previous = ""
    for record in manifest["assets"]:
        path = record["path"]
        if not ASSET_PATH.fullmatch("assets/" + path) or path <= previous:
            raise ValueError("Invalid Minecraft Asset Path Order")
        data = (root / identity / path).read_bytes()
        asset_hash = hashlib.sha256(data).hexdigest()
        if len(data) != record["size"] or asset_hash != record["sha256"]:
            raise ValueError(f"Minecraft Asset Changed: {path}")
        digest.update(path.encode())
        digest.update(bytes.fromhex(asset_hash))
        previous = path
    if digest.hexdigest() != identity:
        raise ValueError("Minecraft Asset Set Changed")
    return manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version")
    parser.add_argument("--client-jar", type=Path)
    parser.add_argument("--mod-jar", type=Path, action="append", default=[])
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        print(json.dumps({"identity": verify(args.output)["identity"]}))
    elif args.version and args.client_jar:
        prepare(args.output, args.version, args.client_jar, args.mod_jar)
    else:
        parser.error("--version and --client-jar are required unless --verify is used")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
