import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.litematic_render_ui import app as render_app


class TemporaryRenderWorldTest(unittest.TestCase):
    def test_tasks_receive_distinct_world_names(self):
        self.assertEqual(render_app._render_world_name("aaaaaaaaaaaa"),
                         "LitematicRender_aaaaaaaaaaaa")
        self.assertNotEqual(render_app._render_world_name("aaaaaaaaaaaa"),
                            render_app._render_world_name("bbbbbbbbbbbb"))

    def test_cleanup_removes_only_the_requested_render_world(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "run" / "saves" / "LitematicRender_aaaaaaaaaaaa"
            other = root / "run" / "saves" / "LitematicRender_bbbbbbbbbbbb"
            target.mkdir(parents=True)
            other.mkdir()
            (target / "level.dat").write_bytes(b"A")
            (other / "level.dat").write_bytes(b"B")

            with mock.patch.object(render_app, "POC_ROOT", root):
                self.assertTrue(render_app._cleanup_render_world(
                    "LitematicRender_aaaaaaaaaaaa", retry_delay=0))

            self.assertFalse(target.exists())
            self.assertTrue(other.exists())

    def test_cleanup_rejects_non_render_paths(self):
        with self.assertRaises(ValueError):
            render_app._cleanup_render_world("../saves", retry_delay=0)


if __name__ == "__main__":
    unittest.main()
