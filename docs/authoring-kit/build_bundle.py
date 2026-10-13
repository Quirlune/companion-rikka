"""把 velune companion.config.v1 配置与已引用的附件封装为 ZIP。

用法：python build_bundle.py examples/lore-package.config.json lore.bundle.zip
      python build_bundle.py examples/lore-attachment.config.json lore-attachment.bundle.zip

只检查 ZIP 结构、JSON 语法与附件；最终资源语义须由 velune 导入预览验证。
仅使用 Python 标准库；拒绝覆盖现有输出文件。
"""

from __future__ import annotations

import argparse
import json
import unicodedata
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT_KEYS = {"format", "label", "resources", "commands"}
KINDS = {"character", "mainCall", "package", "group", "persona", "tag", "profile", "connection", "extension"}
ARRAYS = {"characters", "mainCalls", "packages", "groups", "personas", "tags", "profiles", "connections", "extensions"}
MAX_JSON = 8 * 1024 * 1024
MAX_TEXT = 32 * 1024 * 1024
MAX_MEMBERS = 1024
MAX_EXPANDED = 256 * 1024 * 1024


def load_json(data: bytes, where: str):
    def reject_duplicates(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError(f"{where}: 重复 JSON 键 {key!r}")
            obj[key] = value
        return obj

    return json.loads(data.decode("utf-8-sig"), object_pairs_hook=reject_duplicates)


def package_path(name: str) -> str:
    if not isinstance(name, str) or not name or len(name) > 1024:
        raise ValueError("包内路径不能为空或超过 1024 字符")
    if unicodedata.normalize("NFC", name) != name:
        raise ValueError(f"包内路径需用 NFC Unicode：{name!r}")
    if name.startswith("/") or "\\" in name or ":" in name or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise ValueError(f"只允许 ZIP 内正斜杠相对路径：{name!r}")
    if any(not part or part in (".", "..") or part.endswith((" ", ".")) for part in name.split("/")):
        raise ValueError(f"包内路径含空层、点路径或尾随空格/点：{name!r}")
    return name


def collect_markers(value, assets: dict[str, str]) -> None:
    if isinstance(value, list):
        for child in value:
            collect_markers(child, assets)
    elif isinstance(value, dict):
        markers = {"$text", "$avatar"} & value.keys()
        if markers:
            if len(markers) != 1 or len(value) != 1:
                raise ValueError("附件标记对象必须恰好含 $text 或 $avatar 一个键")
            marker = next(iter(markers))
            path = package_path(value[marker])
            kind = "text" if marker == "$text" else "avatar"
            if path in assets and assets[path] != kind:
                raise ValueError(f"同一附件的类型冲突：{path}")
            assets[path] = kind
        else:
            for child in value.values():
                collect_markers(child, assets)


def asset_bytes(root: Path, name: str, kind: str) -> bytes:
    path = root.joinpath(*name.split("/"))
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"附件不存在、是符号链接或越出配置目录：{name}")
    data = path.read_bytes()
    if kind in ("text", "documentation"):
        if len(data) > MAX_TEXT:
            raise ValueError(f"文本附件超过 32 MiB：{name}")
        data.decode("utf-8")
    elif kind == "avatar":
        if len(data) > 32 * 1024:
            raise ValueError(f"此离线示例要求头像不超过 32 KiB：{name}")
        png = data.startswith(b"\x89PNG\r\n\x1a\n")
        jpeg = data.startswith(b"\xff\xd8\xff")
        if not ((name.lower().endswith(".png") and png) or (name.lower().endswith((".jpg", ".jpeg")) and jpeg)):
            raise ValueError(f"头像扩展名与 PNG/JPEG 文件头不符：{name}")
    return data


def write_member(archive: ZipFile, name: str, data: bytes) -> None:
    info = ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
    info.compress_type = ZIP_DEFLATED
    archive.writestr(info, data)


def build(config_path: Path, output_path: Path, readme_path: Path | None) -> None:
    config_path = config_path.resolve()
    source = config_path.read_bytes()
    if len(source) > MAX_JSON:
        raise ValueError("配置 JSON 超过 8 MiB")
    config = load_json(source, str(config_path))
    if not isinstance(config, dict) or set(config) - ROOT_KEYS or config.get("format") != "companion.config.v1":
        raise ValueError("配置根必须为 companion.config.v1，且只含 format/label/resources/commands")
    if "label" in config and not isinstance(config["label"], str):
        raise ValueError("配置 label 必须是字符串")
    if "resources" in config and (not isinstance(config["resources"], dict) or set(config["resources"]) - ARRAYS):
        raise ValueError("resources 必须是已知资源数组映射")
    if "resources" in config and any(not isinstance(value, list) for value in config["resources"].values()):
        raise ValueError("resources 各成员必须是数组")
    if "commands" in config and not isinstance(config["commands"], list):
        raise ValueError("commands 必须是数组")
    assets: dict[str, str] = {}
    collect_markers(config, assets)
    payloads = {name: asset_bytes(config_path.parent, name, kind) for name, kind in assets.items()}
    if readme_path is not None:
        if "README.txt" in assets:
            raise ValueError("README.txt 已被资源引用，不能同时作为说明文档")
        payloads["README.txt"] = readme_path.read_bytes()
        if len(payloads["README.txt"]) > MAX_TEXT:
            raise ValueError("说明文档超过 32 MiB")
        payloads["README.txt"].decode("utf-8")
        assets["README.txt"] = "documentation"
    names = ["manifest.json", "payload.config.json", *payloads]
    folded_names = [name.casefold() for name in names]
    if len(names) > MAX_MEMBERS or len(set(folded_names)) != len(names):
        raise ValueError("成员超限或有大小写折叠后的重复路径")
    if any(a.startswith(b + "/") for a in folded_names for b in folded_names if a != b):
        raise ValueError("包内文件与目录路径冲突")
    if sum(map(len, payloads.values())) + len(source) > MAX_EXPANDED:
        raise ValueError("ZIP 展开内容超过 256 MiB")
    manifest = {
        "format": "companion.bundle.v1",
        "label": config.get("label", "velune 内容包"),
        "files": [{"path": "payload.config.json", "kind": "config"}]
        + [{"path": name, "kind": kind} for name, kind in sorted(assets.items())],
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output_path, "x") as archive:
        write_member(archive, "manifest.json", manifest_bytes)
        write_member(archive, "payload.config.json", source)
        for name, data in sorted(payloads.items()):
            write_member(archive, name, data)
    print(output_path.resolve())
    print("已检查 ZIP 结构、JSON 语法及附件；未执行 velune 资源解码/预览/应用。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path, help="companion.config.v1 JSON 文件")
    parser.add_argument("output", type=Path, help="新 ZIP 路径，已存在时拒绝覆盖")
    parser.add_argument("--readme", type=Path, help="可选 UTF-8 说明，ZIP 内记为 documentation")
    args = parser.parse_args()
    build(args.config, args.output, args.readme)


if __name__ == "__main__":
    main()
