import io
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from flask import Flask

from tools.litematic_render_ui import app as render_app


class UploadLimitTest(unittest.TestCase):
    def test_render_dimensions_use_first_region_size_and_absolute_values(self):
        root = {
            "Metadata": {"EnclosingSize": {"x": 999, "y": 999, "z": 999}},
            "Regions": {
                "first": {"Size": {"x": -11, "y": 7, "z": -48}},
                "second": {"Size": {"x": 1, "y": 2, "z": 3}},
            },
        }
        with mock.patch.object(render_app, "_read_nbt", return_value=root):
            self.assertEqual(render_app._read_render_dimensions("fixture.litematic"), (11, 7, 48))

    def test_default_safe_distance_examples(self):
        cases = (
            ((11, 7, 48), False),
            ((16, 16, 16), False),
            ((80, 80, 80), False),
            ((270, 270, 270), False),
            ((288, 288, 288), True),
            ((289, 289, 289), True),
            ((550, 10, 10), True),
            ((250, 232, 185), False),
            ((99, 141, 211), False),
        )
        for dimensions, rejected in cases:
            with self.subTest(dimensions=dimensions):
                distance = render_app._axon_farthest_corner_distance(dimensions)
                self.assertEqual(distance > render_app._safe_render_distance_blocks(), rejected)

    def test_zero_safety_chunk_examples(self):
        with mock.patch.object(render_app, "RENDER_DISTANCE_SAFETY_CHUNKS", 0):
            for dimensions, rejected in (
                ((288, 288, 288), False),
                ((289, 289, 289), True),
                ((550, 10, 10), False),
            ):
                with self.subTest(dimensions=dimensions):
                    distance = render_app._axon_farthest_corner_distance(dimensions)
                    self.assertEqual(distance > render_app._safe_render_distance_blocks(), rejected)

    def test_python_distance_matches_java_camera_for_geometry(self):
        dimensions = (99, 141, 211)
        a, b, c = (value / 2.0 for value in dimensions)
        radius = (a * a + b * b + c * c) ** 0.5
        camera_distance = max(radius * 1.05, radius + 0.5)
        forward = (-(6.0 ** 0.5) / 4.0, -0.5, (6.0 ** 0.5) / 4.0)
        java_distance = max(
            sum((corner[index] + forward[index] * camera_distance) ** 2
                for index in range(3)) ** 0.5
            for corner in ((x, y, z) for x in (-a, a) for y in (-b, b) for z in (-c, c))
        )
        self.assertLess(abs(render_app._axon_farthest_corner_distance(dimensions) - java_distance), 0.01)

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
                                      return_value=metadata), \
                    mock.patch.object(render_app, "_read_render_dimensions",
                                      return_value=(550, 10, 10)):
                response = flask_app.test_client().post(
                    "/tools/litematic_render/upload",
                    data={"litematic": (io.BytesIO(b"fixture"), "cathedral.litematic")},
                )

            self.assertEqual(response.status_code, 413)
            self.assertEqual(response.get_json(), {
                "error": "投影超出安全渲染视距",
                "dimensions": "550 × 10 × 10",
                "farthest_distance": 509.51,
                "safe_limit": 480,
                "hard_limit": 512,
                "render_distance_chunks": 32,
                "safety_chunks": 2,
            })
            self.assertFalse(tasks_file.exists())
            self.assertEqual(list(raw_dir.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
