"""HTTP-level checks for the media fixture server."""
import importlib.util
from functools import partial
from http.client import HTTPConnection
from io import BytesIO
from pathlib import Path
import tempfile
from threading import Thread
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SCRIPT = Path(__file__).resolve().parents[1] / "media-test-server.py"


def load_server():
    spec = importlib.util.spec_from_file_location("media_test_server", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MediaTestServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "test.mp4").write_bytes(bytes(range(64)))
        (self.root / "test-headers.mp4").write_bytes(b"headers")
        (self.root / "hls").mkdir()
        (self.root / "dash").mkdir()
        (self.root / "hls/manifest").write_text("#EXTM3U\n")
        (self.root / "dash/manifest").write_text("<MPD/>\n")
        ffmpeg = self.root / "fake-ffmpeg"
        ffmpeg.write_text(
            "#!/usr/bin/env python3\n"
            "import os, time\n"
            "try:\n"
            "    while True:\n"
            "        os.write(1, b'x' * 4096)\n"
            "        time.sleep(.01)\n"
            "except BrokenPipeError:\n"
            "    pass\n")
        ffmpeg.chmod(0o700)
        self.module = load_server()
        self.server = self.module.MediaServer(
            ("127.0.0.1", 0), partial(self.module.MediaHandler, directory=str(self.root)),
            self.root, ffmpeg=str(ffmpeg))
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def get(self, path, headers=None):
        return urlopen(Request(self.base + path, headers=headers or {}), timeout=2)

    def test_range_returns_only_requested_bytes(self):
        with self.get("/static/test.mp4", {"Range": "bytes=16-31"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.headers["Content-Range"], "bytes 16-31/64")
            self.assertEqual(response.headers["Accept-Ranges"], "bytes")
            self.assertEqual(response.read(), bytes(range(16, 32)))

    def test_invalid_range_is_rejected(self):
        with self.assertRaises(HTTPError) as error:
            self.get("/static/test.mp4", {"Range": "bytes=99-100"})
        with error.exception:
            self.assertEqual(error.exception.code, 416)
            self.assertEqual(error.exception.headers["Content-Range"], "bytes */64")

    def test_extensionless_manifests_have_media_types(self):
        with self.get("/static/hls/manifest") as hls, self.get("/static/dash/manifest") as dash:
            self.assertEqual(hls.headers.get_content_type(), "application/vnd.apple.mpegurl")
            self.assertEqual(dash.headers.get_content_type(), "application/dash+xml")

    def test_header_fixture_requires_expected_headers(self):
        with self.assertRaises(HTTPError) as error:
            self.get("/static/test-headers.mp4")
        with error.exception:
            self.assertEqual(error.exception.code, 403)
        with self.get("/static/test-headers.mp4", {
            "User-Agent": "FervoTV-Media-Test",
            "Referer": "https://media-test.invalid/",
        }) as response:
            self.assertEqual(response.status, 200)

    def test_http_playlist_uses_request_origin(self):
        with self.get("/playlist-http.m3u") as response:
            body = response.read().decode()
        self.assertIn(self.base + "/live/mp4\n", body)
        self.assertIn(self.base + "/live/hls.m3u8\n", body)
        self.assertIn(self.base + "/live/dash.mpd\n", body)
        self.assertIn("#EXTVLCOPT:http-user-agent=FervoTV-Media-Test", body)

    def test_live_request_starts_ffmpeg_and_disconnect_stops_it(self):
        with self.get("/live/mp4") as response:
            self.assertEqual(response.status, 200)
            self.assertEqual(response.headers.get_content_type(), "video/mp4")
            self.assertEqual(response.headers["Accept-Ranges"], "none")
            self.assertEqual(response.read(128), b"x" * 128)
            self.assertEqual(self.server.process_count(), 1)
        deadline = time.monotonic() + 3
        while self.server.process_count() and time.monotonic() < deadline:
            time.sleep(.05)
        self.assertEqual(self.server.process_count(), 0)

    def test_dash_manifest_points_segments_to_its_session(self):
        body = self.module.dash_manifest(b"<MPD><Period/></MPD>", "abc123")
        self.assertEqual(body, b"<MPD><BaseURL>/live/dash/abc123/</BaseURL>\n<Period/></MPD>")

    def test_live_ffmpeg_source_has_no_duration_or_short_file_loop(self):
        args = self.module.synthetic_args("ffmpeg")
        self.assertIn("lavfi", args)
        self.assertNotIn("-t", args)
        self.assertNotIn("-stream_loop", args)

    def test_client_disconnect_during_static_copy_is_normal(self):
        class Disconnected:
            def write(self, _):
                raise BrokenPipeError()

        handler = object.__new__(self.module.MediaHandler)
        handler.copyfile(BytesIO(b"client stopped reading"), Disconnected())

    def test_old_static_live_url_redirects_to_continuous_stream(self):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            connection.request("GET", "/test.ts")
            response = connection.getresponse()
            self.assertEqual(response.status, 307)
            self.assertEqual(response.headers["Location"], "/live/mpeg2.ts")
            response.read()
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
