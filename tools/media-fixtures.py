#!/usr/bin/env python3
"""Generate permanent local media compatibility fixtures with FFmpeg."""
import argparse
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit


def duration_arg(value):
    seconds = int(value)
    if seconds < 2:
        raise argparse.ArgumentTypeError("duration must be at least 2 seconds")
    return seconds


def base_url_arg(value):
    if not value:
        return ""
    try:
        parsed = urlsplit(value)
        port = parsed.port
        valid = (parsed.scheme in ("http", "https") and parsed.hostname
                 and (port is None or port > 0)
                 and not parsed.query and not parsed.fragment
                 and not any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in value))
        if not valid:
            raise ValueError()
    except ValueError:
        raise argparse.ArgumentTypeError("base URL must be HTTP(S), with a valid port and no whitespace/query/fragment")
    return value.rstrip("/")


def output_path(value):
    return (Path(value) if value else Path(__file__).resolve().parents[1] / "build/media-fixtures").absolute()


def command(args, seconds):
    return subprocess.run(
        list(map(str, args)), check=True, capture_output=True, text=True,
        timeout=max(60, seconds * 3),
    ).stdout


def timestamp(seconds, separator):
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02}:{minutes:02}:{seconds:02}{separator}000"


def generate(folder, seconds):
    def ffmpeg(name, args):
        print(f"Generating {name}", flush=True)
        command(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-n", *args, folder / name], seconds)

    def picture_and_tone(frequency):
        return ["-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                "-f", "lavfi", "-i", f"sine=frequency={frequency}:sample_rate=48000", "-t", seconds]

    h264 = ["-c:v", "libx264", "-threads", "2", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-g", "50",
            "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709"]
    ffmpeg("test.mp4", [*picture_and_tone(880), *h264, "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart"])
    ffmpeg("test.ts", [*picture_and_tone(440), "-c:v", "mpeg2video", "-threads", "2", "-q:v", "5",
                        "-g", "50", "-c:a", "mp2", "-b:a", "128k"])
    ffmpeg("test-avc.ts", ["-i", folder / "test.mp4", "-map", "0", "-c", "copy", "-f", "mpegts"])
    ffmpeg("test-dts.mkv", ["-i", folder / "test.mp4", "-f", "lavfi", "-i", "sine=frequency=550:sample_rate=48000",
                            "-map", "0:v:0", "-map", "1:a:0", "-t", seconds, "-c:v", "copy",
                            "-c:a", "dca", "-strict", "-2", "-b:a", "768k"])
    (folder / "hls").mkdir()
    (folder / "dash").mkdir()
    ffmpeg("hls/live.m3u8", ["-i", folder / "test.mp4", "-map", "0", "-c", "copy", "-f", "hls",
                                 "-hls_time", "2", "-hls_list_size", "0", "-hls_playlist_type", "vod",
                                 "-hls_segment_filename", folder / "hls/%03d.ts"])
    ffmpeg("dash/stream.mpd", ["-i", folder / "test.mp4", "-map", "0", "-c", "copy", "-f", "dash",
                                   "-seg_duration", "2", "-use_template", "1", "-use_timeline", "1"])
    for original, alias in (("test.mp4", "test-extensionless"), ("test.mp4", "test-headers.mp4"),
                            ("hls/live.m3u8", "hls/manifest"),
                            ("dash/stream.mpd", "dash/manifest")):
        shutil.copyfile(folder / original, folder / alias)

    text = "Fervo TV media fixture: ru/en audio track check"
    (folder / "subtitles.srt").write_text(
        f"1\n00:00:01,000 --> {timestamp(seconds, ',')}\n{text}\n", encoding="utf-8")
    (folder / "subtitles.vtt").write_text(
        f"WEBVTT\n\n00:00:01.000 --> {timestamp(seconds, '.')}\n{text}\n", encoding="utf-8")
    ffmpeg("multi.mp4", ["-i", folder / "test.mp4", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                         "-i", folder / "subtitles.srt", "-map", "0:v:0", "-map", "0:a:0", "-map", "1:a:0",
                         "-map", "2:s:0", "-t", seconds, "-c:v", "copy", "-c:a", "aac", "-b:a", "128k",
                         "-c:s", "mov_text", "-metadata:s:a:0", "language=rus", "-metadata:s:a:1", "language=eng",
                         "-metadata:s:s:0", "language=eng", "-movflags", "+faststart"])
    for name in ("episode1.mp4", "episode2.mp4"):
        shutil.copyfile(folder / "multi.mp4", folder / name)


def verify(folder, seconds):
    media = {
        "test.mp4": ("h264", ["aac"]), "test.ts": ("mpeg2video", ["mp2"]),
        "test-avc.ts": ("h264", ["aac"]), "test-dts.mkv": ("h264", ["dts"]),
        "test-extensionless": ("h264", ["aac"]), "hls/live.m3u8": ("h264", ["aac"]),
        "test-headers.mp4": ("h264", ["aac"]),
        "hls/manifest": ("h264", ["aac"]), "dash/stream.mpd": ("h264", ["aac"]),
        "dash/manifest": ("h264", ["aac"]), "multi.mp4": ("h264", ["aac", "aac"]),
        "episode1.mp4": ("h264", ["aac", "aac"]), "episode2.mp4": ("h264", ["aac", "aac"]),
    }
    for name, (video, audio) in media.items():
        # Local extensionless manifests have no HTTP MIME; give ffprobe their known demuxer.
        hint = ["-f", "hls"] if name == "hls/manifest" else ["-f", "dash"] if name == "dash/manifest" else []
        info = json.loads(command(["ffprobe", "-v", "error", *hint, "-show_streams", "-show_format", "-of", "json", folder / name], seconds))
        streams = info.get("streams", [])
        duration = float(info["format"]["duration"])
        if (not math.isfinite(duration) or duration <= 0
                or [s["codec_name"] for s in streams if s["codec_type"] == "video"] != [video]
                or [s["codec_name"] for s in streams if s["codec_type"] == "audio"] != audio):
            raise ValueError(f"ffprobe validation failed: {name}")
        if name in ("multi.mp4", "episode1.mp4", "episode2.mp4"):
            if ([s.get("tags", {}).get("language") for s in streams if s["codec_type"] == "audio"] != ["rus", "eng"]
                    or [s["codec_name"] for s in streams if s["codec_type"] == "subtitle"] != ["mov_text"]):
                raise ValueError(f"Audio/subtitle validation failed: {name}")


def verify_existing(folder, seconds, require_http_playlist=False):
    required = [
        "test.mp4", "test.ts", "test-avc.ts", "test-dts.mkv", "test-extensionless", "test-headers.mp4",
        "hls/live.m3u8", "hls/manifest", "dash/stream.mpd", "dash/manifest", "multi.mp4",
        "episode1.mp4", "episode2.mp4", "subtitles.srt", "subtitles.vtt", "playlist.m3u",
    ]
    if require_http_playlist:
        required.append("playlist-http.m3u")
    missing = [name for name in required if not (folder / name).is_file()]
    if missing:
        raise ValueError(f"fixture set is incomplete; missing: {', '.join(missing)}")
    verify(folder, seconds)


def playlist(output, base):
    entries = [
        ("live", "mp4", "Test MP4", "test.mp4"),
        ("live", "ts", "Test MPEG-TS MPEG2 MP2", "test.ts"),
        ("live", "avc-ts", "Test MPEG-TS AVC AAC", "test-avc.ts"),
        ("live", "hls", "Test HLS", "hls/live.m3u8"),
        ("live", "mkv-dts", "Test MKV DTS", "test-dts.mkv"),
        ("live", "dash", "Test DASH", "dash/stream.mpd"),
        ("live", "extensionless", "Test extensionless MP4", "test-extensionless"),
        ("live", "headers", "Test headers", "test-headers.mp4"),
        ("live", "hls-extensionless", "Test extensionless HLS", "hls/manifest"),
        ("live", "dash-extensionless", "Test extensionless DASH", "dash/manifest"),
        ("movie", "multi", "Test multi audio subtitles", "multi.mp4"),
        ("series", "episode1", "Test Series S01E01", "episode1.mp4"),
        ("series", "episode2", "Test Series S01E02", "episode2.mp4"),
    ]
    lines = ["#EXTM3U"]
    for kind, key, title, name in entries:
        lines.append(f'#EXTINF:-1 type="{kind}" tvg-id="media-test-{key}" group-title="Media tests",{title}')
        if key == "headers":
            lines.extend(["#EXTVLCOPT:http-user-agent=FervoTV-Media-Test", "#EXTVLCOPT:http-referrer=https://media-test.invalid/"])
        live = {
            "mp4": "live/mp4", "ts": "live/mpeg2.ts", "avc-ts": "live/avc.ts",
            "hls": "live/hls.m3u8", "mkv-dts": "live/dts.mkv", "dash": "live/dash.mpd",
            "extensionless": "live/extensionless", "headers": "live/headers",
            "hls-extensionless": "live/hls", "dash-extensionless": "live/dash",
        }.get(key)
        lines.append(f"{base}/{live or name}" if base else (output / name).as_uri())
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", help="output directory (default: repository build/media-fixtures)")
    parser.add_argument("--duration", type=duration_arg, default=12, help="clip duration in seconds, at least 2 (default: 12)")
    parser.add_argument("--base-url", type=base_url_arg, default=os.environ.get("MEDIA_BASE_URL", ""),
                        help="HTTP(S) origin/base path for playlist-http.m3u; also accepts MEDIA_BASE_URL")
    parser.add_argument("--reuse-existing", action="store_true",
                        help="verify and reuse a complete existing fixture directory without modifying it")
    args = parser.parse_args()
    output = output_path(args.output)
    if (output.exists() or output.is_symlink()) and not args.reuse_existing:
        parser.error(f"output already exists: {output}; choose a new directory")
    for tool in ("ffmpeg", "ffprobe"):
        if shutil.which(tool) is None:
            print(f"error: {tool} is required on PATH", file=sys.stderr)
            return 1
    try:
        if output.exists() or output.is_symlink():
            if not output.is_dir() or output.is_symlink():
                raise ValueError(f"existing output is not a directory: {output}")
            verify_existing(output, args.duration, require_http_playlist=bool(args.base_url))
            print(f"Existing media fixtures verified and reused at {output}")
            return 0
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".media-fixtures-", dir=output.parent) as temp:
            folder = Path(temp)
            generate(folder, args.duration)
            verify(folder, args.duration)
            (folder / "playlist.m3u").write_text(playlist(output, ""), encoding="utf-8")
            if args.base_url:
                (folder / "playlist-http.m3u").write_text(playlist(output, args.base_url), encoding="utf-8")
            folder.rename(output)
        print(f"Media fixtures verified and written to {output}")
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else str(error)
        print(f"error: generation failed: {detail}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
