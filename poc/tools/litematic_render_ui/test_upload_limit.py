import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from flask import Flask

from tools.litematic_render_ui import app as render_app


class UploadLimitTest(unittest.TestCase):
    def test_dimension_limit_examples(self):
        cases = (
            ((11, 7, 48), False),
            ((250, 232, 185), True),
            ((99, 141, 211), True),
            ((16, 16, 16), False),
            ((80, 80, 80), False),
        )
        for dimensions, rejected in cases:
            metadata = {"dimensions": " × ".join(map(str, dimensions))}
            parsed = render_app._metadata_dimensions(metadata)
            self.assertEqual(max(parsed) > render_app.MAX_RENDER_DIMENSION, rejected)

    def test_oversized_upload_returns_413_and_leaves_no_file_or_task(self):
        flask_app = Flask(__name__)
        flask_app.register_blueprint(render_app.bp)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_dir = root / "raw"
            tasks_file = root / "tasks.json"
            metadata = {
                "dimensions": "250 × 232 × 185",
                "metadata_error": None,
            }
            with mock.patch.multiple(render_app, RAW_DIR=raw_dir,
                                     LITEMATIC_DIR=root, TASKS_FILE=tasks_file), \
                    mock.patch.object(render_app, "_read_litematic_metadata",
                                      return_value=metadata):
                response = flask_app.test_client().post(
                    "/tools/litematic_render/upload",
                    data={"litematic": (io.BytesIO(b"fixture"), "cathedral.litematic")},
                )

            self.assertEqual(response.status_code, 413)
            self.assertEqual(response.get_json(), {
                "error": "投影过大无法渲染 (最大边 250 > 80 块)",
                "dimensions": "250 × 232 × 185",
                "limit": 80,
            })
            self.assertFalse(tasks_file.exists())
            self.assertEqual(list(raw_dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
