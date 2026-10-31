#!/usr/bin/env bash
set -euo pipefail

# Generates deterministic local media for P3 checks. Requires ffmpeg and ffprobe.
out=$(realpath -m "${1:-build/p3-fixtures}")
base=${P3_BASE_URL:-}
mkdir -p "$out/hls"
mkdir -p "$out/dash"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=880:sample_rate=48000" \
  -t 12 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -b:a 128k \
  -movflags +faststart "$out/test.mp4"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=440:sample_rate=48000" \
  -t 12 -c:v mpeg2video -q:v 5 -c:a mp2 -b:a 128k "$out/test.ts"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=550:sample_rate=48000" \
  -t 12 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a dca -strict -2 -b:a 768k \
  "$out/test-dts.mkv"

cp "$out/test.mp4" "$out/test-extensionless"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=770:sample_rate=48000" \
  -t 12 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -b:a 128k \
  -f dash -seg_duration 2 -use_template 1 -use_timeline 1 \
  "$out/dash/stream.mpd"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "testsrc2=size=640x360:rate=25" \
  -f lavfi -i "sine=frequency=660:sample_rate=48000" \
  -t 12 -c:v libx264 -pix_fmt yuv420p -preset veryfast -c:a aac -b:a 128k \
  -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename "$out/hls/%03d.ts" "$out/hls/live.m3u8"

cat > "$out/subtitles.srt" <<'EOF'
1
00:00:01,000 --> 00:00:03,000
Fervo TV synthetic subtitle

2
00:00:04,000 --> 00:00:06,000
Audio and subtitle track check
EOF

cat > "$out/playlist.m3u" <<EOF
#EXTM3U
#EXTINF:-1 tvg-id="synthetic-mp4" group-title="P3",Synthetic MP4
$out/test.mp4
#EXTINF:-1 tvg-id="synthetic-ts" group-title="P3",Synthetic MPEG-TS
$out/test.ts
#EXTINF:-1 tvg-id="synthetic-hls" group-title="P3",Synthetic HLS
$out/hls/live.m3u8
#EXTINF:-1 tvg-id="synthetic-mkv-dts" group-title="P3",Synthetic MKV DTS
$out/test-dts.mkv
#EXTINF:-1 tvg-id="synthetic-dash" group-title="P3",Synthetic DASH
$out/dash/stream.mpd
#EXTINF:-1 tvg-id="synthetic-extensionless" group-title="P3",Synthetic extensionless
$out/test-extensionless
#EXTINF:-1 tvg-id="synthetic-headers" group-title="P3",Synthetic headers
#EXTVLCOPT:http-user-agent=FervoTV-P3-Test
#EXTVLCOPT:http-referrer=https://p3.invalid/test
$out/test.mp4
EOF

if [[ -n "$base" ]]; then
  cat > "$out/playlist-http.m3u" <<EOF
#EXTM3U
#EXTINF:-1 tvg-id="synthetic-mp4" group-title="P3",Synthetic MP4
$base/test.mp4
#EXTINF:-1 tvg-id="synthetic-ts" group-title="P3",Synthetic MPEG-TS
$base/test.ts
#EXTINF:-1 tvg-id="synthetic-hls" group-title="P3",Synthetic HLS
$base/hls/live.m3u8
#EXTINF:-1 tvg-id="synthetic-mkv-dts" group-title="P3",Synthetic MKV DTS
$base/test-dts.mkv
#EXTINF:-1 tvg-id="synthetic-dash" group-title="P3",Synthetic DASH
$base/dash/stream.mpd
#EXTINF:-1 tvg-id="synthetic-extensionless" group-title="P3",Synthetic extensionless
$base/test-extensionless
#EXTINF:-1 tvg-id="synthetic-headers" group-title="P3",Synthetic headers
#EXTVLCOPT:http-user-agent=FervoTV-P3-Test
#EXTVLCOPT:http-referrer=https://p3.invalid/test
$base/test.mp4
EOF
fi

for file in "$out/test.mp4" "$out/test.ts" "$out/test-dts.mkv" "$out/test-extensionless" "$out/hls/live.m3u8" "$out/dash/stream.mpd"; do
  ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$file" >/dev/null
done

echo "Synthetic P3 fixtures written to $out"
