#!/usr/bin/env python3
"""Serve media compatibility fixtures with byte ranges and fixture-specific behavior."""
import argparse
from dataclasses import dataclass
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
from threading import Event, Lock, Thread
import time
from urllib.parse import urlsplit
from uuid import uuid4


playlist = runpy.run_path(str(Path(__file__).with_name("media-fixtures.py")))["playlist"]

STREAMS = {
    "/live/mp4": ("video/mp4", "h264", "aac", ["-movflags", "frag_keyframe+empty_moov+default_base_moof", "-f", "mp4"]),
    "/live/extensionless": ("video/mp4", "h264", "aac", ["-movflags", "frag_keyframe+empty_moov+default_base_moof", "-f", "mp4"]),
    "/live/headers": ("video/mp4", "h264", "aac", ["-movflags", "frag_keyframe+empty_moov+default_base_moof", "-f", "mp4"]),
    "/live/mpeg2.ts": ("video/mp2t", "mpeg2video", "mp2", ["-f", "mpegts"]),
    "/live/avc.ts": ("video/mp2t", "h264", "aac", ["-f", "mpegts"]),
    "/live/dts.mkv": ("video/x-matroska", "h264", "dts", ["-f", "matroska"]),
}

LEGACY_STREAMS = {
    "/test.mp4": "/live/mp4", "/test.ts": "/live/mpeg2.ts", "/test-avc.ts": "/live/avc.ts",
    "/test-dts.mkv": "/live/dts.mkv", "/test-extensionless": "/live/extensionless",
    "/test-headers.mp4": "/live/headers", "/hls/live.m3u8": "/live/hls.m3u8",
    "/hls/manifest": "/live/hls", "/dash/stream.mpd": "/live/dash.mpd", "/dash/manifest": "/live/dash",
}


def synthetic_args(ffmpeg, video="h264", audio="aac"):
    video_args = (["-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-tune", "zerolatency",
                   "-pix_fmt", "yuv420p", "-g", "50", "-sc_threshold", "0"] if video == "h264" else
                  ["-c:v", "mpeg2video", "-threads", "2", "-q:v", "5", "-g", "50"])
    audio_args = {
        "aac": ["-c:a", "aac", "-b:a", "128k"],
        "mp2": ["-c:a", "mp2", "-b:a", "128k"],
        "dts": ["-c:a", "dca", "-strict", "-2", "-b:a", "768k"],
    }[audio]
    return [ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-re", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
            "-re", "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
            "-map", "0:v:0", "-map", "1:a:0", *video_args, *audio_args]


def dash_manifest(body, session_id):
    return body.replace(b"<Period", f"<BaseURL>/live/dash/{session_id}/</BaseURL>\n<Period".encode(), 1)


def stop_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


@dataclass
class LiveSession:
    kind: str
    folder: Path
    process: subprocess.Popen
    touched: float


class LiveSessions:
    def __init__(self, server, idle_seconds=20):
        self.server = server
        self.idle_seconds = idle_seconds
        self.temp = tempfile.TemporaryDirectory(prefix="fervotv-media-live-")
        self.items = {}
        self.lock = Lock()
        self.start_lock = Lock()
        self.closed = Event()
        self.reaper = Thread(target=self._reap, name="media-live-reaper", daemon=True)
        self.reaper.start()

    def start(self, kind):
        with self.start_lock:
            with self.lock:
                for session_id, session in self.items.items():
                    if session.kind == kind and session.process.poll() is None:
                        session.touched = time.monotonic()
                        return session_id
            session_id = uuid4().hex
            folder = Path(self.temp.name) / session_id
            folder.mkdir()
            output = folder / ("index.m3u8" if kind == "hls" else "index.mpd")
            common = synthetic_args(self.server.ffmpeg)
            if kind == "hls":
                args = [*common, "-f", "hls", "-hls_time", "2", "-hls_list_size", "6",
                        "-hls_flags", "delete_segments+omit_endlist+independent_segments",
                        "-hls_base_url", f"/live/hls/{session_id}/",
                        "-hls_segment_filename", str(folder / "segment-%06d.ts"), str(output)]
                ready = lambda: output.is_file() and any(folder.glob("segment-*.ts"))
            else:
                args = [*common, "-f", "dash", "-seg_duration", "2", "-window_size", "6",
                        "-extra_window_size", "2", "-update_period", "2",
                        "-use_template", "1", "-use_timeline", "1",
                        str(output)]
                ready = lambda: output.is_file() and any(folder.glob("chunk-*.m4s"))
            process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.server.register_process(process)
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline and process.poll() is None and not ready():
                time.sleep(.05)
            if not ready():
                stop_process(process)
                self.server.unregister_process(process)
                shutil.rmtree(folder, ignore_errors=True)
                raise RuntimeError(f"ffmpeg did not start the {kind.upper()} stream")
            with self.lock:
                self.items[session_id] = LiveSession(kind, folder, process, time.monotonic())
            return session_id

    def file(self, kind, session_id, name):
        if Path(name).name != name:
            return None
        with self.lock:
            session = self.items.get(session_id)
            if not session or session.kind != kind or session.process.poll() is not None:
                return None
            session.touched = time.monotonic()
            return session.folder / name

    def _reap(self):
        while not self.closed.wait(2):
            now = time.monotonic()
            with self.lock:
                expired = [key for key, value in self.items.items()
                           if now - value.touched > self.idle_seconds or value.process.poll() is not None]
            for key in expired:
                self.stop(key)

    def stop(self, session_id):
        with self.lock:
            session = self.items.pop(session_id, None)
        if session:
            stop_process(session.process)
            self.server.unregister_process(session.process)
            shutil.rmtree(session.folder, ignore_errors=True)

    def close(self):
        self.closed.set()
        self.reaper.join()
        with self.lock:
            keys = list(self.items)
        for key in keys:
            self.stop(key)
        self.temp.cleanup()


class MediaServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, directory, ffmpeg="ffmpeg"):
        self.directory = str(directory)
        self.ffmpeg = ffmpeg
        self.processes = set()
        self.process_lock = Lock()
        self.sessions = None
        super().__init__(address, handler)
        self.sessions = LiveSessions(self)

    def register_process(self, process):
        with self.process_lock:
            self.processes.add(process)

    def unregister_process(self, process):
        with self.process_lock:
            self.processes.discard(process)

    def process_count(self):
        with self.process_lock:
            return len(self.processes)

    def server_close(self):
        if self.sessions:
            self.sessions.close()
        with self.process_lock:
            processes = list(self.processes)
        for process in processes:
            stop_process(process)
            self.unregister_process(process)
        super().server_close()


def port_arg(value):
    port = int(value)
    if not 1 <= port <= 65535:
        raise argparse.ArgumentTypeError("port must be between 1 and 65535")
    return port


def byte_range(value, size):
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or size == 0:
        return None
    first, last = match.groups()
    if not first:
        length = int(last or 0)
        return (max(0, size - length), size - 1) if length else None
    start = int(first)
    end = min(int(last), size - 1) if last else size - 1
    return (start, end) if start < size and start <= end else None


class MediaHandler(SimpleHTTPRequestHandler):
    def _headers_allowed(self, path):
        return not (path.endswith("/test-headers.mp4") or path == "/live/headers") or (
            self.headers.get("User-Agent") == "FervoTV-Media-Test"
            and self.headers.get("Referer") == "https://media-test.invalid/")

    def do_GET(self):
        path = urlsplit(self.path).path
        if not self._headers_allowed(path):
            self.send_error(HTTPStatus.FORBIDDEN, "expected media test User-Agent and Referer")
        elif path in LEGACY_STREAMS:
            self._redirect(LEGACY_STREAMS[path])
        elif path in STREAMS:
            self._serve_stream(*STREAMS[path])
        elif path in ("/live/hls", "/live/hls.m3u8"):
            self._start_manifest_stream("hls")
        elif path in ("/live/dash", "/live/dash.mpd"):
            self._start_manifest_stream("dash")
        elif path.startswith("/live/hls/") or path.startswith("/live/dash/"):
            self._serve_session_file(path)
        elif path.startswith("/static/"):
            self.path = self.path[len("/static"):]
            super().do_GET()
        else:
            super().do_GET()

    def do_HEAD(self):
        path = urlsplit(self.path).path
        if not self._headers_allowed(path):
            self.send_error(HTTPStatus.FORBIDDEN, "expected media test User-Agent and Referer")
        elif path in LEGACY_STREAMS:
            self._redirect(LEGACY_STREAMS[path])
        elif path in STREAMS:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", STREAMS[path][0])
            self.end_headers()
        elif path.startswith("/static/"):
            self.path = self.path[len("/static"):]
            super().do_HEAD()
        else:
            super().do_HEAD()

    def _redirect(self, location):
        self.send_response(HTTPStatus.TEMPORARY_REDIRECT)
        self.send_header("Location", location)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _serve_stream(self, content_type, video, audio, output_args):
        try:
            process = subprocess.Popen(
                [*synthetic_args(self.server.ffmpeg, video, audio), *output_args, "pipe:1"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError as error:
            self.send_error(HTTPStatus.INTERNAL_SERVER_ERROR, str(error))
            return
        self.server.register_process(process)
        try:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Accept-Ranges", "none")
            self._no_ranges = True
            self.end_headers()
            while chunk := process.stdout.read(shutil.COPY_BUFSIZE):
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            process.stdout.close()
            stop_process(process)
            self.server.unregister_process(process)

    def _start_manifest_stream(self, kind):
        try:
            session_id = self.server.sessions.start(kind)
        except (OSError, RuntimeError) as error:
            self.send_error(HTTPStatus.BAD_GATEWAY, str(error))
            return
        if kind == "dash":
            self._serve_session_file(f"/live/dash/{session_id}/index.mpd")
            return
        extension = "m3u8" if kind == "hls" else "mpd"
        self.send_response(HTTPStatus.FOUND)
        self.send_header("Location", f"/live/{kind}/{session_id}/index.{extension}")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _serve_session_file(self, path):
        parts = path.strip("/").split("/")
        if len(parts) != 4:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        _, kind, session_id, name = parts
        file = self.server.sessions.file(kind, session_id, name)
        if file and kind == "dash" and name.startswith("chunk-") and name.endswith(".m4s"):
            deadline = time.monotonic() + 4
            while not file.is_file() and time.monotonic() < deadline:
                time.sleep(.05)
        if not file or not file.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if kind == "dash" and name == "index.mpd":
            try:
                body = dash_manifest(file.read_bytes(), session_id)
            except OSError:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/dash+xml")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        try:
            source = file.open("rb")
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        with source:
            size = os.fstat(source.fileno()).st_size
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", self.guess_type(str(file)))
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                shutil.copyfileobj(source, self.wfile)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def end_headers(self):
        if not getattr(self, "_no_ranges", False):
            self.send_header("Accept-Ranges", "bytes")
        super().end_headers()

    def guess_type(self, path):
        normalized = path.replace(os.sep, "/")
        if normalized.endswith(("/hls/manifest", ".m3u8")):
            return "application/vnd.apple.mpegurl"
        if normalized.endswith(("/dash/manifest", ".mpd")):
            return "application/dash+xml"
        if normalized.endswith(".m4s"):
            return "video/iso.segment"
        if normalized.endswith(".ts"):
            return "video/mp2t"
        if normalized.endswith("/test-extensionless"):
            return "video/mp4"
        return super().guess_type(path)

    def send_head(self):
        request_path = urlsplit(self.path).path
        if not self._headers_allowed(request_path):
            self.send_error(HTTPStatus.FORBIDDEN, "expected media test User-Agent and Referer")
            return None
        if request_path == "/playlist-http.m3u":
            host = self.headers.get("Host", "")
            if not re.fullmatch(r"[A-Za-z0-9.:[\]-]+", host):
                self.send_error(HTTPStatus.BAD_REQUEST, "invalid Host header")
                return None
            body = playlist(Path(self.directory), f"http://{host}").encode()
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return BytesIO(body)

        value = self.headers.get("Range")
        if not value:
            return super().send_head()
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            return super().send_head()
        try:
            source = open(path, "rb")
            size = os.fstat(source.fileno()).st_size
            selected = byte_range(value, size)
            if selected is None:
                source.close()
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return None
            start, end = selected
            self.send_response(HTTPStatus.PARTIAL_CONTENT)
            self.send_header("Content-Type", self.guess_type(path))
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Last-Modified", self.date_time_string(os.fstat(source.fileno()).st_mtime))
            self.end_headers()
            self._range = start, end
            return source
        except OSError:
            self.send_error(HTTPStatus.NOT_FOUND, "file not found")
            return None

    def copyfile(self, source, outputfile):
        selected = getattr(self, "_range", None)
        try:
            if selected is None:
                return super().copyfile(source, outputfile)
            start, end = selected
            source.seek(start)
            remaining = end - start + 1
            while remaining:
                chunk = source.read(min(shutil.COPY_BUFSIZE, remaining))
                if not chunk:
                    break
                outputfile.write(chunk)
                remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", default=Path(__file__).resolve().parents[1] / "build/media-fixtures",
                        type=Path, help="fixture directory (default: build/media-fixtures)")
    parser.add_argument("--bind", default="0.0.0.0", help="address to bind (default: 0.0.0.0)")
    parser.add_argument("--port", default=8765, type=port_arg, help="port (default: 8765)")
    args = parser.parse_args()
    directory = args.directory.absolute()
    if not (directory / "playlist.m3u").is_file():
        parser.error(f"Media fixtures not found in {directory}; run make media-fixtures")
    try:
        server = MediaServer((args.bind, args.port), partial(MediaHandler, directory=str(directory)), directory)
    except OSError as error:
        parser.error(f"cannot listen on {args.bind}:{args.port}: {error}")
    print(f"Media test server: http://{args.bind}:{args.port}/", flush=True)
    print(f"Playlist: http://<host-ip>:{args.port}/playlist-http.m3u", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nMedia test server stopped", flush=True)
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
