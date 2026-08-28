import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from flask import Flask

from tools.litematic_render_ui import app as render_app


class RenderStateRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.output = self.root / "output"
        self.output.mkdir()
        self.task = {
            "id": "v128", "filename": "demo.litematic",
            "source_path": str(self.root / "demo.litematic"),
            "output_dir": str(self.output), "status": "queued", "outputs": {},
        }
        (self.root / "demo.litematic").write_bytes(b"fixture")
        self.tasks_file = self.root / "tasks.json"
        self.tasks_file.write_text(json.dumps([self.task]), encoding="utf-8")
        self.paths = mock.patch.multiple(
            render_app, LITEMATIC_DIR=self.root, RAW_DIR=self.root / "raw",
            TASKS_FILE=self.tasks_file, UPDATE_RETRY_DELAY=0,
        )
        self.paths.start()

    def tearDown(self):
        self.paths.stop()
        self.temporary.cleanup()

    def _write_outputs(self):
        for name in ("demo_overview_paper.png", "demo_overview.png", "demo_备货清单.xlsx"):
            (self.output / name).write_bytes(b"output")

    @staticmethod
    def _completed_process(returncode=0):
        process = mock.Mock()
        process.wait.return_value = returncode
        return process

    def test_update_retries_then_succeeds_and_logs_traceback(self):
        original = render_app._write_tasks
        calls = 0

        def flaky_write(tasks):
            nonlocal calls
            calls += 1
            if calls < 3:
                raise OSError("temporary write failure")
            original(tasks)

        with mock.patch.object(render_app, "_write_tasks", side_effect=flaky_write):
            render_app._update("v128", status="rendering")

        self.assertEqual(calls, 3)
        self.assertEqual(render_app._task("v128")["status"], "rendering")
        flask_log = (self.root / "flask.log").read_text(encoding="utf-8")
        self.assertIn("Traceback", flask_log)
        self.assertIn("temporary write failure", flask_log)

    def test_complete_outputs_force_final_state_when_update_fails(self):
        self._write_outputs()
        real_update = render_app._update

        def fail_final_update(task_id, **changes):
            if changes.get("status") == "rendering":
                return real_update(task_id, **changes)
            raise RuntimeError("simulated final update failure")

        with mock.patch.object(render_app, "_update", side_effect=fail_final_update), \
                mock.patch.object(render_app.subprocess, "Popen",
                                  return_value=self._completed_process()):
            render_app._run("v128")

        saved = render_app._task("v128")
        self.assertEqual(saved["status"], "complete")
        self.assertIsNotNone(saved["finished_at"])
        self.assertEqual(saved["outputs"]["paper"], "demo_overview_paper.png")

    def test_run_exception_marks_task_failed(self):
        with mock.patch.object(render_app.subprocess, "Popen",
                              side_effect=RuntimeError("renderer crashed")):
            render_app._run("v128")

        saved = render_app._task("v128")
        self.assertEqual(saved["status"], "failed")
        self.assertIn("renderer crashed", saved["error"])
        self.assertIsNotNone(saved["finished_at"])


class LiveStatusEndpointTest(unittest.TestCase):
    def test_log_tail_is_localized_and_reports_progress(self):
        flask_app = Flask(__name__)
        flask_app.register_blueprint(render_app.bp)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "output"
            output.mkdir()
            (output / "render.log").write_text(
                "[01:18:04] [Render thread/INFO] (Minecraft) [STDOUT]: [STEP 4/9] WROTE COMPOSITE\n",
                encoding="utf-8",
            )
            task = {"id": "tail", "output_dir": str(output), "status": "rendering", "outputs": {}}
            with mock.patch.object(render_app, "_task", return_value=task):
                response = flask_app.test_client().get("/tools/litematic_render/tail/log_tail")

        payload = response.get_json()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(payload["progress"], 44)
        self.assertIn("01:18:04 [INFO]", payload["lines"][0])
        self.assertIn("已生成合成图", payload["lines"][0])
        self.assertNotIn("Minecraft", payload["lines"][0])


if __name__ == "__main__":
    unittest.main()
