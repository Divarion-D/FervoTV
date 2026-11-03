"""CLI and real-media regression checks; requires ffmpeg and ffprobe."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "p3-synthetic-streams.py"


class SyntheticStreamsTest(unittest.TestCase):
    def cli(self, *args, env=None):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *map(str, args)],
            capture_output=True, text=True, timeout=120, env={**os.environ, "P3_BASE_URL": "", **(env or {})},
        )

    def test_help_needs_no_media_tools(self):
        result = self.cli("--help", env={"PATH": ""})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--duration", result.stdout)
        self.assertIn("--base-url", result.stdout)

    def test_invalid_arguments_do_not_create_output(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixtures"
            for args in (
                ("--duration", "0"), ("--duration", "-1"), ("--duration", "nan"),
                ("--base-url", "file:///tmp/media"),
                ("--base-url", "http://host:bad"),
                ("--base-url", "http://host:0"),
                ("--base-url", "http://host/path?token=test"),
                ("--base-url", "http://host/path#fragment"),
                ("--base-url", "http://host/\n#EXTINF:bad"),
            ):
                with self.subTest(args=args):
                    result = self.cli(output, *args)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("error:", result.stderr)
                    self.assertFalse(output.exists())

    def test_existing_data_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixtures"
            output.mkdir()
            sentinel = output / "playlist.m3u"
            sentinel.write_bytes(b"user data\x00")
            result = self.cli(output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("exists", result.stderr)
            self.assertEqual(sentinel.read_bytes(), b"user data\x00")
            self.assertEqual(list(output.iterdir()), [sentinel])

    def test_missing_ffmpeg_is_reported_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixtures"
            result = self.cli(output, env={"PATH": ""})
            self.assertEqual(result.returncode, 1)
            self.assertIn("ffmpeg", result.stderr)
            self.assertFalse(output.exists())

    def test_failed_encoder_does_not_publish_partial_fixtures(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            for name in ("ffmpeg", "ffprobe"):
                tool = folder / name
                tool.write_text("#!/bin/sh\nprintf 'forced encoder failure' >&2\nexit 17\n")
                tool.chmod(0o700)
            output = folder / "fixtures"
            result = self.cli(output, env={"PATH": str(folder)})
            self.assertEqual(result.returncode, 1)
            self.assertIn("forced encoder failure", result.stderr)
            self.assertFalse(output.exists())
            self.assertEqual(sorted(p.name for p in folder.iterdir()), ["ffmpeg", "ffprobe"])

    def test_legacy_shell_entry_uses_environment_base_url(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixtures with spaces"
            result = subprocess.run(
                ["bash", str(SCRIPT.parent / "p3-synthetic-streams.sh"), str(output), "--duration", "2"],
                env={**os.environ, "P3_BASE_URL": "https://p3.invalid/media/"},
                capture_output=True, text=True, timeout=120,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("https://p3.invalid/media/test.mp4\n", (output / "playlist-http.m3u").read_text())

    def test_generates_playable_media_and_local_and_http_playlists(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "fixtures with spaces"
            result = self.cli(output, "--duration", "2", "--base-url", "http://127.0.0.1:8765/qa/")
            self.assertEqual(result.returncode, 0, result.stderr)
            expected = {
                "test.mp4": ("h264", ["aac"]),
                "test.ts": ("mpeg2video", ["mp2"]),
                "test-avc.ts": ("h264", ["aac"]),
                "test-dts.mkv": ("h264", ["dts"]),
                "test-extensionless": ("h264", ["aac"]),
                "test-headers.mp4": ("h264", ["aac"]),
                "hls/live.m3u8": ("h264", ["aac"]),
                "hls/manifest": ("h264", ["aac"]),
                "dash/stream.mpd": ("h264", ["aac"]),
                "dash/manifest": ("h264", ["aac"]),
                "multi.mp4": ("h264", ["aac", "aac"]),
                "episode1.mp4": ("h264", ["aac", "aac"]),
                "episode2.mp4": ("h264", ["aac", "aac"]),
            }
            for name, (video, audio) in expected.items():
                with self.subTest(name=name):
                    hint = {"hls/manifest": ["-f", "hls"], "dash/manifest": ["-f", "dash"]}.get(name, [])
                    probe = subprocess.run(
                        ["ffprobe", "-v", "error", *hint, "-show_streams", "-show_format", "-of", "json", str(output / name)],
                        capture_output=True, text=True, timeout=20, check=True,
                    )
                    info = json.loads(probe.stdout)
                    streams = info["streams"]
                    self.assertEqual([s["codec_name"] for s in streams if s["codec_type"] == "video"], [video])
                    self.assertEqual([s["codec_name"] for s in streams if s["codec_type"] == "audio"], audio)
                    self.assertGreater(float(info["format"]["duration"]), 1.5)
                    if name in ("test.mp4", "test.ts", "test-avc.ts", "test-dts.mkv", "hls/live.m3u8", "dash/stream.mpd", "multi.mp4"):
                        subprocess.run(
                            ["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(output / name),
                             "-map", "0:v:0", "-map", "0:a", "-f", "null", "-"],
                            capture_output=True, text=True, timeout=20, check=True,
                        )
            self.assertEqual((output / "hls/manifest").read_bytes(), (output / "hls/live.m3u8").read_bytes())
            self.assertEqual((output / "dash/manifest").read_bytes(), (output / "dash/stream.mpd").read_bytes())
            multi = json.loads(subprocess.check_output(
                ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(output / "multi.mp4")], text=True))
            self.assertEqual([s["tags"]["language"] for s in multi["streams"] if s["codec_type"] == "audio"], ["rus", "eng"])
            self.assertEqual([s["codec_name"] for s in multi["streams"] if s["codec_type"] == "subtitle"], ["mov_text"])
            local = (output / "playlist.m3u").read_text()
            http = (output / "playlist-http.m3u").read_text()
            self.assertIn((output / "test.mp4").as_uri(), local)
            self.assertNotIn(temp, http)
            self.assertIn("http://127.0.0.1:8765/qa/hls/manifest\n", http)
            self.assertIn("http://127.0.0.1:8765/qa/dash/manifest\n", http)
            self.assertIn('type="live"', http)
            self.assertIn('type="movie"', http)
            self.assertIn('type="series"', http)
            self.assertIn("#EXTVLCOPT:http-user-agent=FervoTV-P3-Test", http)
            self.assertIn("#EXTVLCOPT:http-referrer=https://p3.invalid/test", http)
            urls = [line for line in http.splitlines() if line and not line.startswith("#")]
            self.assertEqual(len(urls), len(set(urls)), "Each fixture needs its own stream identity")
            self.assertTrue((output / "subtitles.vtt").read_text().startswith("WEBVTT\n"))
            self.assertIn("00:00:01,000 --> 00:00:02,000", (output / "subtitles.srt").read_text())


if __name__ == "__main__":
    unittest.main()
