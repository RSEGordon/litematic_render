from __future__ import annotations

import json
import hashlib
import logging
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

import nbtlib
from flask import Blueprint, abort, jsonify, redirect, render_template, request, send_file, url_for
POC_ROOT = Path(__file__).resolve().parents[2]
LITEMATIC_DIR = Path("/home/rsegordon/桌面/OpenClawFile/FileShare/工具/combined/litematic")
RAW_DIR = LITEMATIC_DIR / "raw"
TASKS_FILE = LITEMATIC_DIR / "tasks.json"
JAVA_HOME = Path(os.environ.get("LITEMATIC_RENDER_JAVA_HOME", "/opt/java/jdk-25.0.1"))
_lock = threading.RLock()
_render_lock = threading.Lock()
bp = Blueprint("litematic_render", __name__, template_folder="templates")
MAX_RENDER_DIMENSION = 80
UPDATE_RETRIES = 3
UPDATE_RETRY_DELAY = 0.5

LOG_TRANSLATIONS = {
    "WROTE COMPOSITE": "已生成合成图", "WROTE MATERIAL WORKBOOK": "已生成材料清单",
    "WROTE OUTPUT ARCHIVE": "已打包输出", "WROTE": "已写出",
    "LITEMATIC_RENDER_DONE": "渲染完成", "BUILD SUCCESSFUL": "构建成功",
    "BUILD FAILED": "构建失败", "Stopping server": "停止服务",
    "Saving players": "保存玩家数据", "Saving chunks": "保存区块",
    "Saving worlds": "保存世界", "Saving dimensions": "保存维度",
    "RenderBot lost connection": "渲染机器人断开连接", "Loading mods": "加载模组",
    "loaded litematic": "已读取投影", "rendering overview": "生成概览图",
    "rendering paper": "生成彩图", "Starting Minecraft": "启动 Minecraft",
    "Litematica loaded": "Litematica 模组已加载", "Applying mixin": "应用 mixin",
    "Initializing": "初始化中", "TOTAL_BLOCKS": "总方块数", "REGION": "区域",
    "STEP": "步骤", "elapsed": "已用时", "rendering": "渲染中",
    "Loading": "加载中", "loaded": "已加载", "done": "完成", "failed": "失败",
}


def _log_status(level, message, *args):
    """Write render state transitions to the dedicated Flask log."""
    try:
        LITEMATIC_DIR.mkdir(parents=True, exist_ok=True)
        logger = logging.getLogger("litematic_render")
        logger.setLevel(logging.DEBUG)
        logger.propagate = False
        log_path = LITEMATIC_DIR / "flask.log"
        handler = next((item for item in logger.handlers
                        if isinstance(item, logging.FileHandler)
                        and Path(item.baseFilename) == log_path), None)
        if handler is None:
            for old_handler in list(logger.handlers):
                old_handler.close()
                logger.removeHandler(old_handler)
            handler = logging.FileHandler(log_path, encoding="utf-8")
            handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
            logger.addHandler(handler)
        logger.log(level, message, *args)
    except Exception:
        print("litematic_render: failed to write flask.log", file=sys.stderr)
        traceback.print_exc()


def _page_nav(page, extra_right=""):
    """Wrap the shared combined_app5.page_nav() so the Blueprint gets the same global nav template bili_summary uses.

    Falls back to a local mirror if the import fails (keeps the Blueprint self-contained for tests).
    After page_nav returns, we re-highlight the nav item whose path is a prefix of `page` — page_nav
    only does exact matching, so /tools/litematic_render/ never gets the "🛠️ 工具" highlight.
    """
    try:
        from combined_app5 import page_nav as _combined_page_nav  # type: ignore
        nav = _combined_page_nav(page, extra_right=extra_right)
    except Exception:
        nav = _render_local(page)
    # Re-highlight: any link whose href is a prefix of the current page wins.
    import re as _re
    def _patch(match):
        href = match.group(1)
        if href.startswith(("http://", "https://")):
            return match.group(0)
        is_active = (page == href or (href != "/" and page.startswith(href.rstrip("/") + "/"))) or \
                    (href != "/" and page.rstrip("/") == href.rstrip("/"))
        if is_active:
            return f'<a href="{href}" class="active">'
        return match.group(0)
    return _re.sub(r'<a href="([^"]+)"(?:\s+class="active")?>', _patch, nav)


def _render_local(page):
    items = [("/", "📊 监控"), ("/files", "📁 共享"), ("/voice", "🎙️ 语音"), ("/tools", "🛠️ 工具"), ("https://clawblog.rseg.club/", "📝 博客")]
    nav = '''<button class="mobile-nav-toggle" type="button" aria-label="打开导航" aria-controls="siteNav" aria-expanded="false"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" style="transform: rotate(180deg);"><polyline points="9 18 15 12 9 6"/></svg></button>
<nav class="nav" id="siteNav" aria-label="主导航">'''
    for u, t in items:
        active = ' class="active"' if (page == u or (u != "/" and page.startswith(u))) else ''
        nav += f'<a href="{u}"{active}>{t}</a>'
    nav += '''</nav>
<button class="nav-backdrop" type="button" aria-label="关闭导航" tabindex="-1"></button>'''
    return nav


def _nav_style_block():
    """Mirror of the @media/CSS chunk page_nav injects. Kept in sync with combined_app5."""
    return '''<style>
.nav{display:flex;gap:8px;align-items:center;margin-left:auto;padding:8px 4px}
.nav a{color:var(--blue);font-size:12px;text-decoration:none;padding:7px 9px;border-radius:7px}
.nav a:hover{background:var(--user-bubble);color:var(--accent)}
.nav a.active{background:var(--blue);color:#fff;font-weight:700}
.mobile-nav-toggle,.nav-backdrop{display:none}
@media (min-width:640px) and (max-width:1024px){
  .container{max-width:760px!important;margin:14px auto!important;padding:14px!important}
}
@media (max-width:639px){
  body.nav-open{overflow:hidden}
  .mobile-nav-toggle{display:flex;position:fixed;top:50%;left:0;width:18px;height:56px;align-items:center;justify-content:center;border:none;border-radius:0 8px 8px 0;background:#8BA5B5;color:#fff;cursor:pointer;box-shadow:2px 2px 8px rgba(0,0,0,.15);transform:translateY(-50%);transition:left .24s ease,background .2s ease;z-index:1002}
  .mobile-nav-toggle:hover{background:#7A95A6}
  .mobile-nav-toggle svg{transition:transform .24s ease;display:block}
  body.nav-open .mobile-nav-toggle{left:min(280px,82vw)}
  body.nav-open .mobile-nav-toggle svg{transform:rotate(0deg)}
  .nav{position:fixed!important;inset:0 auto 0 0!important;z-index:1001!important;width:min(280px,82vw)!important;height:100vh!important;height:100dvh!important;padding:calc(16px + env(safe-area-inset-top)) 16px calc(20px + env(safe-area-inset-bottom))!important;display:flex!important;flex-direction:column!important;justify-content:flex-start!important;align-items:stretch!important;gap:8px!important;transform:translateX(-105%);transition:transform .24s ease;box-shadow:8px 0 24px rgba(75,67,56,.14);overflow-y:auto;-webkit-overflow-scrolling:touch}
  .nav a{min-height:44px!important;width:100%;padding:10px 16px!important;border-radius:10px!important;font-size:14px!important}
  .nav-open .nav{transform:translateX(0)}
  .nav-backdrop{display:block;position:fixed;inset:0;z-index:1000;border:0;background:rgba(50,50,50,.32);opacity:0;visibility:hidden;transition:opacity .24s ease,visibility .24s ease}
  .nav-open .nav-backdrop{opacity:1;visibility:visible}
  .container{width:calc(100% - 16px)!important;margin:8px!important;padding:12px!important;border-radius:10px!important}
  h1{font-size:1.25rem!important}
  .upload-row{align-items:stretch!important}
  .upload-btn,.upload-submit,.btn,.modal-copy,.modal-close{min-height:44px;display:inline-flex;align-items:center;justify-content:center}
  .upload-btn,.upload-submit{padding:10px 14px!important}
  .sort-bar{max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch;white-space:nowrap}
  .sort-link{min-width:44px;min-height:44px;display:inline-flex;align-items:center;justify-content:center}
  li{min-height:52px!important;padding:6px 4px!important}
  .dira a{width:100%;min-height:44px;display:flex;align-items:center}
  .file-info{min-width:1;width:100%;min-height:44px;display:flex;align-items:center}
  .file-name{max-width:none!important;font-size:13px!important}
  .file-actions{gap:8px!important}
  .btn{min-width:44px;padding:8px!important}
  .modal{padding:12px}
  .modal-content{width:100%!important;padding:18px!important;max-height:calc(100dvh - 24px);overflow-y:auto;-webkit-overflow-scrolling:touch}
  .modal-url-box{flex-direction:column}
  .modal-url{width:100%;min-height:44px}
  .footer{font-size:12px;padding-bottom:calc(12px + env(safe-area-inset-bottom))}
}
@media (prefers-reduced-motion:reduce){.nav,.nav-backdrop{transition-duration:.01ms}}
</style>'''


def _nav_script_block():
    """Mirror of the JS chunk page_nav injects. Kept in sync with combined_app5."""
    return '''<script>
(function(){
  var toggle=document.querySelector('.mobile-nav-toggle');
  var backdrop=document.querySelector('.nav-backdrop');
  var nav=document.getElementById('siteNav');
  if(!toggle||!backdrop||!nav)return;
  function setNav(open){
    document.body.classList.toggle('nav-open',open);
    toggle.setAttribute('aria-expanded',String(open));
    toggle.setAttribute('aria-label',open?'关闭导航':'打开导航');
    toggle.classList.toggle('open',open);
  }
  toggle.addEventListener('click',function(){setNav(!document.body.classList.contains('nav-open'))});
  backdrop.addEventListener('click',function(){setNav(false)});
  nav.querySelectorAll('a').forEach(function(link){link.addEventListener('click',function(){setNav(false)})});
  document.addEventListener('keydown',function(event){if(event.key==='Escape')setNav(false)});
  var media=window.matchMedia('(min-width:640px)');
  if(media.addEventListener)media.addEventListener('change',function(event){if(event.matches)setNav(false)});
})();
</script>'''


def _render_with_nav(page, body_html):
    """Wrap a body fragment with the same global nav template bili_summary uses.

    combined_app5.page_nav returns the nav HTML already bundled with the matching CSS + JS, so we
    just inject it as-is into the body — no separate _nav_style_block/_nav_script_block needed.
    """
    nav = _page_nav(page)
    return nav + body_html


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
    for attempt in range(1, UPDATE_RETRIES + 1):
        try:
            with _lock:
                tasks = _read_tasks()
                for item in tasks:
                    if item["id"] == task_id:
                        item.update(changes)
                        break
                else:
                    raise KeyError(f"task not found: {task_id}")
                _write_tasks(tasks)
            _log_status(logging.INFO, "[%s] status change: %s", task_id, changes)
            return
        except Exception:
            _log_status(logging.ERROR, "[%s] _update attempt %d/%d failed\n%s",
                        task_id, attempt, UPDATE_RETRIES, traceback.format_exc())
            traceback.print_exc()
            if attempt < UPDATE_RETRIES:
                time.sleep(UPDATE_RETRY_DELAY)
    raise RuntimeError(f"failed to update task {task_id} after {UPDATE_RETRIES} attempts")


def _force_update(task_id, **changes):
    """Last-resort tasks.json rewrite used after normal state updates fail."""
    tasks = json.loads(TASKS_FILE.read_text(encoding="utf-8"))
    for item in tasks:
        if item["id"] == task_id:
            item.update(changes)
            _write_tasks(tasks)
            _log_status(logging.WARNING, "[%s] force-updated status: %s", task_id, changes)
            return
    raise KeyError(f"task not found: {task_id}")


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


def _translate_log_line(line):
    line = re.sub(r'^\[(\d{2}:\d{2}:\d{2})\]\s*\[Render thread/(\w+)\]\s*\(Minecraft\)\s*(?:\[STDOUT\]:\s*)?', r'\1 [\2] ', line)
    line = re.sub(r'^\[(\d{2}:\d{2}:\d{2})\]\s*\[([^]/]+)/(\w+)\]\s*\(Minecraft\)\s*', r'\1 [\3] ', line)
    line = line.replace("(Minecraft)", "")
    for english, chinese in LOG_TRANSLATIONS.items():
        line = line.replace(english, chinese)
    return line.strip()


def _progress_from_log(lines, status=None):
    if status == "complete":
        return 100
    steps = [int(value) for line in lines for value in re.findall(r"\[STEP\s+([1-9])(?:/9)?\]", line)]
    return round(max(steps) * 100 / 9) if steps else 0


def _current_step(lines, status=None):
    if status == "complete":
        return "渲染完成"
    if status == "failed":
        return "渲染失败"
    for line in reversed(lines):
        translated = _translate_log_line(line)
        if any(marker in translated for marker in ("步骤", "生成", "构建", "加载", "渲染")):
            return translated[-80:]
    return "排队中" if status == "queued" else "渲染中"


def _read_nbt(path):
    """Load a gzip-compressed Litematica NBT document."""
    return nbtlib.load(path)


def _read_litematic_metadata(path):
    path = Path(path)
    metadata = {
        "name": path.stem, "dimensions": "—", "author": "—", "version": "—",
        "metadata_error": None,
    }
    try:
        file_size = path.stat().st_size
        if file_size == 0:
            raise ValueError("file is empty")
        if file_size > 100 * 1024 * 1024:
            raise ValueError(f"file too large: {file_size} bytes")
        root = _read_nbt(path)
        metadata_field = root.get("Metadata", {})
        size = metadata_field.get("EnclosingSize", {})
        version_int = root.get("MinecraftDataVersion")
        if version_int is None:
            raise ValueError("MinecraftDataVersion missing")
        if not size or all(size.get(axis, 0) == 0 for axis in ("x", "y", "z")):
            raise ValueError("Size missing or zero")
        metadata.update({
            "name": str(metadata_field.get("Name") or metadata["name"]),
            "dimensions": f'{abs(int(size.get("x", 0)))} × {abs(int(size.get("y", 0)))} × {abs(int(size.get("z", 0)))}',
            "author": str(metadata_field.get("Author") or "—"),
            "version": str(int(version_int)),
        })
    except (OSError, EOFError, KeyError, ValueError, TypeError) as error:
        metadata["metadata_error"] = f"{type(error).__name__}: {error}"
        print(f"[V122 NBT read] {path}: {metadata['metadata_error']}", file=sys.stderr)
    return metadata


def _metadata_dimensions(metadata):
    """Return the three integer dimensions from display metadata, if valid."""
    dimensions = re.fullmatch(r"\s*(\d+)\s*×\s*(\d+)\s*×\s*(\d+)\s*",
                              str(metadata.get("dimensions", "")))
    return tuple(map(int, dimensions.groups())) if dimensions else None


def _test_metadata():
    """Smoke-test all reported schematics, including the former EOF case."""
    samples = (
        (Path("/tmp/【MCOO客运】Final 停靠站.litematic"), (11, 7, 48), "3955", "停靠站"),
        (Path("/tmp/地毯机.litematic"), (16, 16, 16), "4790", "地毯机"),
        (RAW_DIR / "【MCOO客运】Final 售票机.litematic", (5, 8, 6), "3955", "售票机"),
    )
    for path, dimensions, version, label in samples:
        metadata = _read_litematic_metadata(path)
        assert metadata["metadata_error"] is None, f"{label}应解析成功: {metadata}"
        expected = " × ".join(str(value) for value in dimensions)
        assert metadata["dimensions"] == expected, f"{label} dimensions: {metadata['dimensions']}"
        assert metadata["version"] == version, f"{label} version: {metadata['version']}"

    bad_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".litematic", delete=False) as bad_file:
            bad_file.write(b"not a gzip file")
            bad_path = Path(bad_file.name)
        metadata = _read_litematic_metadata(bad_path)
        assert metadata["metadata_error"] is not None, "坏文件应触发 metadata_error"
    finally:
        if bad_path is not None:
            bad_path.unlink(missing_ok=True)
    print("ALL OK")


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
            _log_status(logging.INFO, "[%s] rendering started", task_id)
            result = subprocess.run(command, cwd=POC_ROOT, env=env, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=1800, check=False)
        _log_status(logging.INFO, "[%s] subprocess returned %s", task_id, result.returncode)
        workbook = next(output.glob("*_备货清单.xlsx"), None)
        output_prefix = re.sub(r"(?i)\.litematic$", "", task["filename"])
        output_prefix = re.sub(r'[\\/:*?"<>|]', "_", output_prefix).strip() or "litematic"
        outputs = {
            "paper": f"{output_prefix}_overview_paper.png" if (output / f"{output_prefix}_overview_paper.png").is_file() else None,
            "paper_no_materials": f"{output_prefix}_overview_paper_no_materials.png" if (output / f"{output_prefix}_overview_paper_no_materials.png").is_file() else None,
            "blueprint": f"{output_prefix}_overview.png" if (output / f"{output_prefix}_overview.png").is_file() else None,
            "blueprint_no_materials": f"{output_prefix}_overview_no_materials.png" if (output / f"{output_prefix}_overview_no_materials.png").is_file() else None,
            "thumbnail": "mcoo_axon_x_pos_z_pos_paper.png" if (output / "mcoo_axon_x_pos_z_pos_paper.png").is_file() else None,
            "workbook": workbook.name if workbook else None,
            "log": log_file.name,
        }
        complete = result.returncode == 0 and all(outputs[k] for k in ("paper", "blueprint", "workbook"))
        final_changes = {
            "status": "complete" if complete else "failed", "outputs": outputs,
            "error": None if complete else f"渲染退出码 {result.returncode}，请查看日志",
            "finished_at": int(time.time()),
        }
        try:
            _update(task_id, **final_changes)
        except Exception:
            _log_status(logging.ERROR, "[%s] normal final update failed; forcing rewrite\n%s",
                        task_id, traceback.format_exc())
            if complete:
                _force_update(task_id, **final_changes)
            else:
                raise
    except Exception as error:
        traceback.print_exc()
        _log_status(logging.ERROR, "[%s] _run exception\n%s", task_id, traceback.format_exc())
        failure = {"status": "failed", "error": str(error), "finished_at": int(time.time()),
                   "outputs": {"log": log_file.name if log_file.exists() else None}}
        try:
            _update(task_id, **failure)
        except Exception:
            traceback.print_exc()
            _log_status(logging.ERROR, "[%s] final failure update failed\n%s",
                        task_id, traceback.format_exc())
            try:
                _force_update(task_id, **failure)
            except Exception:
                traceback.print_exc()
                _log_status(logging.CRITICAL, "[%s] force failure update failed\n%s",
                            task_id, traceback.format_exc())


@bp.get("/tools/litematic_render/")
def task_list():
    tasks = sorted((_with_progress(item) for item in _read_tasks()),
                   key=lambda item: item.get("created_at", 0), reverse=True)
    # V102 cards use the colour-paper +X +Z axonometric render as their cover.
    for task in tasks:
        thumbnail = Path(task.get("output_dir", "")) / "mcoo_axon_x_pos_z_pos_paper.png"
        if thumbnail.is_file():
            task.setdefault("outputs", {})["thumbnail"] = thumbnail.name
        # V126: 卡片显示作者 (跟详情页一致) + 友好大小
        try:
            meta = _read_litematic_metadata(task.get("source_path", ""))
            task["metadata"] = meta
        except Exception:
            task["metadata"] = {"name": task.get("filename", ""), "dimensions": "—", "author": "—", "version": "—", "metadata_error": None}
    body = render_template("litematic_tasks_body.html", tasks=tasks, nav=_page_nav("/tools/litematic_render/"))
    return body


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
    metadata = _read_litematic_metadata(temporary)
    dimensions = _metadata_dimensions(metadata)
    if dimensions and max(dimensions) > MAX_RENDER_DIMENSION:
        temporary.unlink(missing_ok=True)
        largest = max(dimensions)
        return jsonify({
            "error": f"投影过大无法渲染 (最大边 {largest} > {MAX_RENDER_DIMENSION} 块)",
            "dimensions": metadata["dimensions"],
            "limit": MAX_RENDER_DIMENSION,
        }), 413
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
    body = render_template("litematic_detail_body.html", task=_with_progress(task), materials=materials,
                           metadata=metadata, nav=_page_nav("/tools/litematic_render/"))
    return body


@bp.get("/api/tools/litematic_render/<task_id>")
@bp.get("/tools/litematic_render/<task_id>/status")
def task_status(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    return jsonify(_with_progress(task))


@bp.get("/tools/litematic_render/<task_id>/log_tail")
def log_tail(task_id):
    task = _task(task_id)
    if not task:
        abort(404)
    log_path = Path(task["output_dir"]) / "render.log"
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-100:]
    except FileNotFoundError:
        lines = []
    except OSError:
        _log_status(logging.ERROR, "[%s] failed reading render.log\n%s",
                    task_id, traceback.format_exc())
        return jsonify({"status": task.get("status"), "lines": [], "progress": 0,
                        "step": "读取日志失败"})
    return jsonify({"status": task.get("status"),
                    "lines": [_translate_log_line(line) for line in lines[-20:]],
                    "progress": _progress_from_log(lines, task.get("status")),
                    "step": _current_step(lines, task.get("status"))})


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


@bp.get("/tools/litematic_render/<task_id>/download/zip")
def download_zip(task_id):
    task = _task(task_id)
    if not task:
        abort(404, description="任务不存在")
    path = Path(task["output_dir"]) / "outputs.zip"
    if not path.is_file():
        abort(404, description="该任务暂无可下载的压缩包")
    return send_file(path, as_attachment=True, download_name="outputs.zip")


@bp.get("/tools/litematic_render/<task_id>/download/<kind>")
def download_output(task_id, kind):
    task = _task(task_id)
    if not task or kind not in {
        "paper", "paper_no_materials", "blueprint", "blueprint_no_materials",
        "thumbnail", "workbook", "log",
    }:
        abort(404)
    filename = task.get("outputs", {}).get(kind)
    if kind == "thumbnail" and not filename:
        filename = "mcoo_axon_x_pos_z_pos_paper.png"
    if not filename:
        abort(404)
    path = (Path(task["output_dir"]) / filename).resolve()
    if path.parent != Path(task["output_dir"]).resolve() or not path.is_file():
        abort(404)
    inline = kind in {"paper", "paper_no_materials", "blueprint", "blueprint_no_materials", "thumbnail"}
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
