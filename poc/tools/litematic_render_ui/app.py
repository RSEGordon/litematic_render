from __future__ import annotations

import json
import gzip
import hashlib
import os
import re
import shlex
import shutil
import struct
import subprocess
import threading
import time
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from flask import Blueprint, abort, jsonify, redirect, render_template, request, send_file, url_for
POC_ROOT = Path(__file__).resolve().parents[2]
LITEMATIC_DIR = Path("/home/rsegordon/桌面/OpenClawFile/FileShare/工具/combined/litematic")
RAW_DIR = LITEMATIC_DIR / "raw"
TASKS_FILE = LITEMATIC_DIR / "tasks.json"
JAVA_HOME = Path(os.environ.get("LITEMATIC_RENDER_JAVA_HOME", "/opt/java/jdk-25.0.1"))
_lock = threading.RLock()
_render_lock = threading.Lock()
bp = Blueprint("litematic_render", __name__, template_folder="templates")


def register(app):
    """Register the three-level UI and upload API on combined_app5's port."""
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    if bp.name not in app.blueprints:
        app.register_blueprint(bp)


def _read_tasks():
    with _lock:
        try:
            return json.loads(TASKS_FILE.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return []


def _write_tasks(tasks):
    LITEMATIC_DIR.mkdir(parents=True, exist_ok=True)
    temporary = TASKS_FILE.with_suffix(".tmp")
    temporary.write_text(json.dumps(tasks, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(TASKS_FILE)


def _task(task_id):
    return next((item for item in _read_tasks() if item["id"] == task_id), None)


def _update(task_id, **changes):
    with _lock:
        tasks = _read_tasks()
        for item in tasks:
            if item["id"] == task_id:
                item.update(changes)
                break
        _write_tasks(tasks)


def _progress(task):
    """Return the last completed renderer STEP (1..9) as a percentage."""
    if task.get("status") == "complete":
        return 100
    log_path = Path(task.get("output_dir", "")) / "render.log"
    try:
        steps = [int(value) for value in re.findall(r"\[STEP ([1-9])\]", log_path.read_text(
            encoding="utf-8", errors="ignore"))]
    except OSError:
        steps = []
    return round(max(steps, default=0) * 100 / 9)


def _with_progress(task):
    result = dict(task)
    result["progress"] = _progress(task)
    return result


def _read_nbt(path):
    """Read the small subset of binary NBT needed by Litematica metadata."""
    with gzip.open(path, "rb") as source:
        data = source.read()
    position = 0

    def take(fmt):
        nonlocal position
        size = struct.calcsize(fmt)
        value = struct.unpack_from(fmt, data, position)
        position += size
        return value[0] if len(value) == 1 else value

    def text_value():
        nonlocal position
        size = take(">H")
        value = data[position:position + size].decode("utf-8", errors="replace")
        position += size
        return value

    def payload(kind):
        nonlocal position
        if kind == 1:
            return take(">b")
        if kind == 2:
            return take(">h")
        if kind == 3:
            return take(">i")
        if kind == 4:
            return take(">q")
        if kind == 5:
            return take(">f")
        if kind == 6:
            return take(">d")
        if kind == 7:
            size = take(">i"); position += size; return None
        if kind == 8:
            return text_value()
        if kind == 9:
            child, size = take(">b"), take(">i")
            return [payload(child) for _ in range(size)]
        if kind == 10:
            value = {}
            while True:
                child = take(">b")
                if child == 0:
                    return value
                name = text_value()
                value[name] = payload(child)
        if kind in {11, 12}:
            size = take(">i"); position += size * (4 if kind == 11 else 8); return None
        raise ValueError(f"unsupported NBT tag {kind}")

    root_kind = take(">b")
    if root_kind != 10:
        raise ValueError("NBT root is not a compound")
    text_value()
    return payload(root_kind)


def _read_litematic_metadata(path):
    metadata = {"name": Path(path).stem, "dimensions": "—", "author": "—", "version": "—"}
    try:
        root = _read_nbt(path)
        source = root.get("Metadata", {})
        size = source.get("EnclosingSize", {})
        metadata.update({
            "name": str(source.get("Name") or metadata["name"]),
            "dimensions": f'{abs(int(size.get("x", 0)))} × {abs(int(size.get("y", 0)))} × {abs(int(size.get("z", 0)))}',
            "author": str(source.get("Author") or "—"),
            "version": str(root.get("Version", "—")),
        })
    except (OSError, EOFError, ValueError, TypeError, struct.error):
        pass
    return metadata


def _run(task_id):
    task = _task(task_id)
    if not task:
        return
    output = Path(task["output_dir"])
    log_file = output / "render.log"
    args = shlex.join([
        task["source_path"], str(output),
        "--title", task["filename"],
        "--width", "1024", "--height", "1024",
        "--username", "RenderBot", "--accessToken", "0",
        "--uuid", "00000000-0000-0000-0000-000000000001",
    ])
    command = [
        "systemd-run", "--user", "--scope", "--quiet",
        "-p", "MemoryHigh=12G", "-p", "MemoryMax=14G", "-p", "OOMPolicy=continue",
        "xvfb-run", "-a", str(POC_ROOT / "gradlew"), "runClient", f"--args={args}",
    ]
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["PATH"] = f'{JAVA_HOME / "bin"}:{env.get("PATH", "")}'
    env["LITEMATIC_RENDER_STYLE"] = "both"
    try:
        # The Fabric client shares its run directory/world, so render jobs must
        # be serialized even if several browser uploads arrive together.
        with _render_lock, log_file.open("w", encoding="utf-8") as log:
            _update(task_id, status="rendering", started_at=int(time.time()))
            result = subprocess.run(command, cwd=POC_ROOT, env=env, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=1800, check=False)
        workbook = next(output.glob("*_备货清单.xlsx"), None)
        outputs = {
            "paper": "mcoo_overview_paper.png" if (output / "mcoo_overview_paper.png").is_file() else None,
            "blueprint": "mcoo_overview.png" if (output / "mcoo_overview.png").is_file() else None,
            "thumbnail": "mcoo_axon_x_pos_z_pos_paper.png" if (output / "mcoo_axon_x_pos_z_pos_paper.png").is_file() else None,
            "workbook": workbook.name if workbook else None,
            "log": log_file.name,
        }
        complete = result.returncode == 0 and all(outputs[k] for k in ("paper", "blueprint", "workbook"))
        _update(task_id, status="complete" if complete else "failed", outputs=outputs,
                error=None if complete else f"渲染退出码 {result.returncode}，请查看日志",
                finished_at=int(time.time()))
    except Exception as error:
        _update(task_id, status="failed", error=str(error), finished_at=int(time.time()),
                outputs={"log": log_file.name if log_file.exists() else None})


@bp.get("/tools/litematic_render/")
def task_list():
    tasks = sorted((_with_progress(item) for item in _read_tasks()),
                   key=lambda item: item.get("created_at", 0), reverse=True)
    # V102 cards use the colour-paper +X +Z axonometric render as their cover.
    for task in tasks:
        thumbnail = Path(task.get("output_dir", "")) / "mcoo_axon_x_pos_z_pos_paper.png"
        if thumbnail.is_file():
            task.setdefault("outputs", {})["thumbnail"] = thumbnail.name
    return render_template("litematic_tasks.html", tasks=tasks)


@bp.post("/tools/litematic_render/upload")
def upload():
    uploaded = request.files.get("litematic")
    if not uploaded or not uploaded.filename:
        return jsonify({"error": "请选择 .litematic 文件"}), 400
    if not uploaded.filename.lower().endswith(".litematic"):
        return jsonify({"error": "只支持 .litematic 文件"}), 400
    task_id = uuid.uuid4().hex[:12]
    original_name = Path(uploaded.filename).name
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    temporary = RAW_DIR / f".{task_id}.upload"
    uploaded.save(temporary)
    with temporary.open("rb") as upload_data:
        digest = hashlib.file_digest(upload_data, "sha256").hexdigest()[:8]
    with _lock:
        source = RAW_DIR / original_name
        output = LITEMATIC_DIR / source.stem
        if source.exists() or output.exists():
            source = RAW_DIR / f"{Path(original_name).stem}_{digest}.litematic"
            output = LITEMATIC_DIR / source.stem
        if source.exists() or output.exists():
            source = RAW_DIR / f"{Path(original_name).stem}_{digest}_{task_id}.litematic"
            output = LITEMATIC_DIR / source.stem
        temporary.replace(source)
        output.mkdir(parents=True, exist_ok=False)
    stored_name = source.name
    task = {
        "id": task_id, "filename": original_name, "stored_name": stored_name,
        "source_path": str(source), "output_dir": str(output), "size": source.stat().st_size,
        "status": "queued", "created_at": int(time.time()), "outputs": {},
    }
    with _lock:
        tasks = _read_tasks()
        tasks.append(task)
        _write_tasks(tasks)
    threading.Thread(target=_run, args=(task_id,), daemon=True, name=f"litematic-{task_id}").start()
    return redirect(url_for("litematic_render.task_detail", task_id=task_id), code=303)


@bp.get("/tools/litematic_render/<task_id>/")
def task_detail(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    materials = _workbook_materials(task)
    metadata = _read_litematic_metadata(task["source_path"])
    return render_template("litematic_detail.html", task=_with_progress(task), materials=materials,
                           metadata=metadata)


@bp.get("/api/tools/litematic_render/<task_id>")
def task_status(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    return jsonify(_with_progress(task))


@bp.post("/api/tools/litematic_render/<task_id>/retry")
def retry_task(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    if task.get("status") != "failed":
        return jsonify({"ok": False, "error": "只有失败任务可以重试"}), 409
    output = Path(task["output_dir"])
    for path in output.iterdir():
        if path != Path(task["source_path"]):
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
    _update(task_id, status="queued", outputs={}, error=None, started_at=None, finished_at=None)
    threading.Thread(target=_run, args=(task_id,), daemon=True,
                     name=f"litematic-retry-{task_id}").start()
    return jsonify({"ok": True, "task_id": task_id})


@bp.delete("/api/tools/litematic_render/<task_id>")
def delete_task(task_id):
    with _lock:
        tasks = _read_tasks()
        task = next((item for item in tasks if item["id"] == task_id), None)
        if not task:
            abort(404)
        if task.get("status") in {"queued", "rendering"}:
            return jsonify({"ok": False, "error": "渲染中的任务不能删除"}), 409
        _write_tasks([item for item in tasks if item["id"] != task_id])
    shutil.rmtree(task["output_dir"], ignore_errors=True)
    return jsonify({"ok": True, "task_id": task_id})


@bp.get("/tools/litematic_render/<task_id>/source")
def download_source(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    return send_file(task["source_path"], as_attachment=True, download_name=task["filename"])


@bp.get("/tools/litematic_render/<task_id>/download/<kind>")
def download_output(task_id, kind):
    task = _task(task_id)
    if not task or kind not in {"paper", "blueprint", "thumbnail", "workbook", "log"}:
        abort(404)
    filename = task.get("outputs", {}).get(kind)
    if kind == "thumbnail" and not filename:
        filename = "mcoo_axon_x_pos_z_pos_paper.png"
    if not filename:
        abort(404)
    path = (Path(task["output_dir"]) / filename).resolve()
    if path.parent != Path(task["output_dir"]).resolve() or not path.is_file():
        abort(404)
    inline = kind in {"paper", "blueprint", "thumbnail"}
    return send_file(path, as_attachment=not inline, download_name=path.name)


def _workbook_materials(task):
    name = task.get("outputs", {}).get("workbook")
    if not name:
        return []
    path = Path(task["output_dir"]) / name
    try:
        with zipfile.ZipFile(path) as archive:
            root = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
        ns = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
        result = []
        for row in root.findall(".//x:sheetData/x:row", ns)[1:]:
            cells = {cell.attrib.get("r", "")[0]: cell for cell in row.findall("x:c", ns)}
            if "A" not in cells or "B" not in cells:
                continue
            text = "".join(node.text or "" for node in cells["A"].findall(".//x:t", ns))
            value = cells["B"].find("x:v", ns)
            if text and value is not None:
                result.append({"name": text, "count": int(float(value.text or 0)), "stocked": 0})
        return result
    except (OSError, zipfile.BadZipFile, ET.ParseError, ValueError):
        return []
