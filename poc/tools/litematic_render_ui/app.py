from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from flask import Blueprint, abort, jsonify, redirect, render_template, request, send_file, url_for
from werkzeug.utils import secure_filename

POC_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_ROOT = Path(os.environ.get("LITEMATIC_RENDER_OUTPUT", "/tmp/poc_v96"))
TASKS_FILE = OUTPUT_ROOT / "tasks.json"
JAVA_HOME = Path(os.environ.get("LITEMATIC_RENDER_JAVA_HOME", "/opt/java/jdk-25.0.1"))
_lock = threading.RLock()
_render_lock = threading.Lock()
bp = Blueprint("litematic_render", __name__, template_folder="templates")


def register(app):
    """Register the three-level UI and upload API on combined_app5's port."""
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    if bp.name not in app.blueprints:
        app.register_blueprint(bp)


def _read_tasks():
    with _lock:
        try:
            return json.loads(TASKS_FILE.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return []


def _write_tasks(tasks):
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
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


def _run(task_id):
    task = _task(task_id)
    if not task:
        return
    output = Path(task["output_dir"])
    log_file = output / "render.log"
    args = f'{task["source_path"]} {output} --width 1024 --height 1024 --username RenderBot --accessToken 0 --uuid 00000000-0000-0000-0000-000000000001'
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
    tasks = sorted(_read_tasks(), key=lambda item: item.get("created_at", 0), reverse=True)
    return render_template("litematic_tasks.html", tasks=tasks)


@bp.post("/tools/litematic_render/upload")
def upload():
    uploaded = request.files.get("litematic")
    if not uploaded or not uploaded.filename:
        return jsonify({"error": "请选择 .litematic 文件"}), 400
    if not uploaded.filename.lower().endswith(".litematic"):
        return jsonify({"error": "只支持 .litematic 文件"}), 400
    task_id = uuid.uuid4().hex[:12]
    output = OUTPUT_ROOT / task_id
    output.mkdir(parents=True)
    original_name = Path(uploaded.filename).name
    stored_name = secure_filename(original_name) or "upload.litematic"
    if not stored_name.lower().endswith(".litematic"):
        stored_name += ".litematic"
    source = output / stored_name
    uploaded.save(source)
    task = {
        "id": task_id, "filename": original_name, "stored_name": stored_name,
        "source_path": str(source), "output_dir": str(output), "size": source.stat().st_size,
        "version": "Litematica", "status": "queued", "created_at": int(time.time()), "outputs": {},
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
    return render_template("litematic_detail.html", task=task, materials=materials)


@bp.get("/api/tools/litematic_render/<task_id>")
def task_status(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    return jsonify(task)


@bp.get("/tools/litematic_render/<task_id>/source")
def download_source(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    return send_file(task["source_path"], as_attachment=True, download_name=task["filename"])


@bp.get("/tools/litematic_render/<task_id>/download/<kind>")
def download_output(task_id, kind):
    task = _task(task_id)
    if not task or kind not in {"paper", "blueprint", "workbook", "log"}:
        abort(404)
    filename = task.get("outputs", {}).get(kind)
    if not filename:
        abort(404)
    path = (Path(task["output_dir"]) / filename).resolve()
    if path.parent != Path(task["output_dir"]).resolve() or not path.is_file():
        abort(404)
    inline = kind in {"paper", "blueprint"}
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
