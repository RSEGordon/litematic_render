# Localization Checklist / 本地化清单

This repository is currently hardwired to author `RSEGordon`'s personal
Linux + Minecraft setup. Before publishing publicly, forking for another
author, or running on a different machine, walk through this list.

本仓库当前硬编码到作者 `RSEGordon` 的个人 Linux + Minecraft 配置。
切公开仓、为他人 fork、或换机器运行前,请按本清单逐项过一遍。

The items are grouped by urgency. **Bucket A blocks public-publish**;
**Bucket B is needed when forking for a new author**; **Bucket C is
aesthetic**.

按紧急程度分三档。**A 档切公开仓前必改**、**B 档换作者 fork 时改**、
**C 档审美**。

---

## 🚨 Bucket A — must change before public-publish / 切公开仓前必须改

### A-1 `LITEMATIC_RENDER_JAVA_HOME` default value in `app.py` / 默认 JDK 路径

- **File / 文件**: `poc/tools/litematic_render_ui/app.py`, around L25.
- **Current / 当前**:

  ```python
  JAVA_HOME = Path(os.environ.get("LITEMATIC_RENDER_JAVA_HOME", "/opt/java/jdk-25.0.1"))
  ```

- **Action / 改法**: either set the env var on every host, or change the
  default fallback to the install path of the new owner's JDK. The env
  var approach is preferred; document the new default if changed.

### A-2 `LITEMATIC_DIR` absolute path in `app.py` / 硬编码绝对路径

- **File / 文件**: `poc/tools/litematic_render_ui/app.py`, top of file.
- **Current / 当前**:

  ```python
  LITEMATIC_DIR = Path("/home/rsegordon/桌面/OpenClawFile/FileShare/工具/combined/litematic")
  ```

- **Action / 改法**: replace with a `LITEMATIC_DIR` environment variable
  with a sensible per-user fallback (e.g. `~/litematic_render_tasks/`).
  Update the runtime deploy script and systemd unit on the production
  host correspondingly.

### A-3 LOG_TRANSLATIONS Chinese-only / 日志翻译只有中文

- **File / 文件**: `poc/tools/litematic_render_ui/app.py`,
  `LOG_TRANSLATIONS` dict.
- **Current / 当前**: maps vanilla MC English log lines to Chinese
  strings (`"WROTE COMPOSITE" -> "已生成合成图"`).
- **Action / 改法**:
  - If the UI is intended for an English audience, swap the Chinese
    strings with English (the keys are already English).
  - Or, ideally, replace the dict with i18n: key becomes the i18n
    identifier, value is loaded from `translations/<lang>.json`.

### A-4 `gradle.properties` — group + (optionally) artifact name / Gradle 坐标

- **File / 文件**: `poc/build.gradle` and `poc/settings.gradle`.
- **Current / 当前**:

  ```gradle
  group = 'com.rsegordon'
  // rootProject.name = 'litematic-render-poc'
  ```

- **Action / 改法**: change `group` to your own reverse-DNS, and rename
  the project via `rootProject.name`. Note that the Gradle `group` does
  not need to match the Java package (and currently doesn't — Java is
  in `com.rsegordon.poc`, Gradle group is `com.rsegordon`); they should
  be aligned if you fix one.

---

## 🔧 Bucket B — needed for new-author fork / 换作者 fork 时改

### B-1 Java package `com.rsegordon.poc` / Java 包名

- **Files**: every `.java` under `poc/src/`. 13 source files declare
  `package com.rsegordon.poc;` (7 in `src/main/java/`, 11 in
  `src/test/java/`, plus 7 `mixin/*.java` declaring the
  `com.rsegordon.poc.mixin` sub-package). Plus:

  - `poc/src/main/resources/fabric.mod.json:7` — entrypoint string
    `"com.rsegordon.poc.LitematicRenderMod"`. **Must** be renamed
    alongside the Java package, or Fabric Loader will fail to find the
    mod entry.
  - `poc/src/main/resources/litematic_render_poc.mixins.json:3` —
    `"package": "com.rsegordon.poc.mixin"`. Same: rename
    together with B-1, or the mixin framework will not discover the
    mixin classes.

- **Reflection call site** (renamed-package step must update this too):

  - `OffscreenRendererChunkCoverageTest.java:57`:
    `Class.forName("com.rsegordon.poc.OffscreenRenderer$View")`

- **Action / 改法**: pick a new package and rename across the tree:

  ```bash
  # 假设新包 com.example.litematic
  grep -rl 'package com.rsegordon.poc' poc/src | xargs sed -i \
      's|package com.rsegordon.poc|package com.example.litematic|'
  find poc/src/main/java/com/rsegordon -type f -exec \
      bash -c 'mkdir -p "${1/\/com\/rsegordon/\/com\/example\/litematic}" && \
               mv "$1" "${1/\/com\/rsegordon/\/com\/example\/litematic}"' _ {} \;
  ```

  Then fix `OffscreenRendererChunkCoverageTest.java:57` and
  `OffscreenRendererWorldCreationTest.java:12`'s `Class.forName`
  call sites. Re-run `./gradlew test`.

### B-2 Fixture XLSX paths / 测试样例绝对路径

- **Files**: `poc/src/main/java/com/rsegordon/poc/MaterialWorkbookWriter.java:20`,
  `poc/src/test/java/com/rsegordon/poc/MaterialWorkbookWriterTest.java:17`,
  possibly others that reference `~/.hermes/cache/documents/doc_*`.
- **Current / 当前**:

  ```java
  "/home/rsegordon/.hermes/cache/documents/doc_eebd8c239945_刷怪塔材料清单.xlsx"
  ```

- **Action / 改法**: move fixtures into `poc/src/test/resources/` and
  reference them via classpath. Need a regen step for
  `doc_eebd8c239945_*` (the fixture is the output of a previous run of
  `MaterialWorkbookWriter` against `刷怪塔材料清单.litematic`).

### B-3 V1 historical utility scripts contain hardcoded author paths / V1 早期脚本含硬编码路径

- **File / 文件**: `convert_entity_models.py:21`.
- **Current / 当前**:

  ```python
  DST_DIR = Path('/home/rsegordon/.hermes/scripts/litematic_render/client_assets/assets/minecraft/models/entity')
  ```

- **Action / 改法**: replace with a path relative to the repo root:

  ```python
  from pathlib import Path
  DST_DIR = Path(__file__).resolve().parent / "client_assets/assets/minecraft/models/entity"
  ```

  Or just delete this file — it was a one-time V1 debug helper; the
  modern renderer (`OffscreenRenderer`) extracts its own blockstate data
  at runtime from `client.jar`.

### B-4 `OffscreenRendererWorldCreationTest` source-relative path / src 相对路径

- **File / 文件**: `poc/src/test/java/com/rsegordon/poc/OffscreenRendererWorldCreationTest.java:12`.
- **Current / 当前**: hardcoded `"src/main/java/com/rsegordon/poc/OffscreenRenderer.java"`.
- **Action / 改法**: replace with a path relative to `Path.of("..", "..", "main", "java", "com", "rsegordon", "poc", "OffscreenRenderer.java")` or, after renaming the package (B-1), to the new package path.

---

## 🎨 Bucket C — aesthetic / 审美

### C-1 Repository URL / 仓库 URL

- **Current / 当前**: `git@github.com:RSEGordon/litematic_render.git`
  (after the round of pushes). Renaming the GitHub repo, or moving to a
  different org, would cascade into the local `origin` URL — re-point
  with `git remote set-url origin <new-url>`.

### C-3 Historical V-plan markdown files reference author paths / 历史 V 字头 md 含作者绝对路径

- **Files**: `V53_POC_plan.md`, `V54_POC_RESURRECT_REPORT.md`,
  `V55_Z_FLIP_FIX.md`, `V62_DYNAMIC_CAMERA.md`,
  and other `V*.md` files in repo root.
- **What they're for / 作用**: keep-alive historical investigation
  notes from the V53-V68 era. They are committed snapshots, not
  working documents.
- **Decision / 取舍**: **Leave them in git history unchanged.** Author
  paths here are historical fact, not actionable code. If publishing
  privately raises concerns, prune these specific files in a single
  git-filter-repo pass — but understand that rewrites commit history
  and forces every collaborator to re-clone.

### C-2 Group + package-name parity / group 与包名一致

- After B-1 rename, you may also want to align `group = 'com.rsegordon'`
  (in `build.gradle`) with the new package. Not strictly required by
  Gradle but tidier.

---

## 🛠 Helper commands / 辅助命令

```bash
# 列所有含本地化关键字的行 / enumerate every localization touch-point
git grep -n "rsegordon\|/home/rsegordon"

# 仅看 Java 文件 / Java-only
git grep -n "rsegordon\|/home/rsegordon" -- 'poc/**/*.java'

# 列出 Java 包声明 / list package declarations
git grep -n "^package " -- 'poc/src/main/java/**/*.java' | sort -u

# 查所有 absolute-pathed 测试 fixture / list absolute-path test fixtures
git grep -nE '"(/home/|/Users/|C:\\)" -- 'poc/src/test/**/*.java'
```

---

## ☑️ Pre-public-publish checklist / 切公开仓 checklist

- [ ] A-1: env-var path or new JDK default confirmed.
- [ ] A-2: `LITEMATIC_DIR` replaced with env-var, deploy docs updated.
- [ ] A-3: log translations aligned to target audience.
- [ ] A-4: `gradle.properties` group updated.
- [ ] B-1: Java package renamed across `poc/src/**`.
- [ ] B-2: test fixtures moved to `poc/src/test/resources/` (and re-record
       the `刷怪塔材料清单` XLSX as a baseline).
- [ ] B-3: source-relative test paths point at the renamed package.
- [ ] C-1: GitHub repo renamed + `origin` re-pointed (if applicable).
- [ ] C-2: gradle group & package aligned (if desired).
- [ ] B-1 mixin sub-files: `fabric.mod.json` entrypoint + `litematic_render_poc.mixins.json` package string updated.
- [ ] Reflection call sites: `OffscreenRendererChunkCoverageTest.java:57` `Class.forName` updated.
- [ ] C-3 plan: decide whether to leave V53/V54/V55/V62 etc. as-is or rewrite history.
- [ ] `./gradlew test` passes on the new owner's hardware.
- [ ] A real litematic is uploaded through the new UI endpoint and
       produces all four artifacts end-to-end.
