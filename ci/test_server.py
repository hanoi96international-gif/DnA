#!/usr/bin/env python3
"""Testserver für den Emulator-Rundgang.

Liefert die Dateien aus ci-media (mit HTTP-Range wie echte IPTV-Server) und simuliert
einen Xtream-Codes-Anbieter (player_api.php, xmltv.php, /live/, /movie/, /series/).
"""
import json
import os
import re
import sys
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "ci-media")
# Startzeit des wackeligen Senders: Er „läuft“ ab dem ersten Abruf, wie ein echter Live-Sender.
FLAKY_EPOCH = None
USER, PASSWORD = "ayu", "test"

LIVE = [
    {"num": 1, "name": "DE | Xtream HD", "stream_id": 101, "stream_icon": "", "epg_channel_id": "hd.test", "category_id": "10", "tv_archive": 0},
    {"num": 2, "name": "DE | Xtream 4K", "stream_id": 102, "stream_icon": None, "epg_channel_id": "uhd.test", "category_id": "11", "tv_archive": 1, "tv_archive_duration": "3"},
]
VOD = [{"num": 1, "name": "DE - Xtream Film (2023) [FHD]", "stream_id": 201, "stream_icon": "", "rating": "7.5", "added": "1700000000", "category_id": "20", "container_extension": "mkv"}]
SERIES = [{"num": 1, "name": "Xtream Serie", "series_id": 301, "cover": "", "plot": "Eine Testserie.", "rating": "8", "last_modified": "1700000000", "category_id": "30"}]
EPISODES = {"301": {"episodes": {"1": [
    {"id": "401", "episode_num": 1, "season": 1, "title": "Folge 1", "container_extension": "mkv", "info": {"duration_secs": "240", "plot": "Erste Folge."}},
    {"id": "402", "episode_num": 2, "season": 1, "title": "Folge 2", "container_extension": "mkv", "info": {"duration_secs": "240"}},
]}}}
FILES = {"live/101": "hd50.ts", "live/102": "uhd.ts", "movie/201": "movie/test/test/1001.mkv",
         "series/401": "movie/test/test/1001.mkv", "series/402": "movie/test/test/1001.mkv"}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def send_json(self, data):
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        query = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path == "/player_api.php":
            if query.get("username") != USER or query.get("password") != PASSWORD:
                return self.send_json({"user_info": {"auth": 0}})
            action = query.get("action")
            responses = {
                None: {"user_info": {"auth": 1, "status": "Active", "exp_date": "1900000000", "max_connections": "2"}, "server_info": {}},
                "get_live_categories": [{"category_id": "10", "category_name": "DE | HD"}, {"category_id": "11", "category_name": "DE | 4K"}],
                "get_live_streams": LIVE,
                "get_vod_categories": [{"category_id": "20", "category_name": "Filme | Neu"}],
                "get_vod_streams": VOD,
                "get_series_categories": [{"category_id": "30", "category_name": "Serien | Test"}],
                "get_series": SERIES,
                "get_series_info": EPISODES.get(query.get("series_id", ""), {}),
            }
            return self.send_json(responses.get(action, []))
        if url.path == "/flaky.ts":
            return self.serve_flaky()
        if url.path == "/stall.ts":
            return self.serve_flaky(stall=True)
        if url.path == "/xmltv.php":
            self.path = "/epg.xml"
            return self.serve_file()
        # Archiv (Catch-up): /timeshift/user/pass/<minuten>/<yyyy-MM-dd:HH-mm>/<id>.ts
        if re.match(r"^/timeshift/%s/%s/\d+/\d{4}-\d{2}-\d{2}:\d{2}-\d{2}/\d+\.ts$" % (USER, PASSWORD), url.path):
            self.path = "/hd50.ts"
            return self.serve_file()
        match = re.match(r"^/(live|movie|series)/%s/%s/(\d+)\.\w+$" % (USER, PASSWORD), url.path)
        if match:
            target = FILES.get("%s/%s" % (match.group(1), match.group(2)))
            if target is None:
                return self.send_error(404)
            self.path = "/" + target
        return self.serve_file()

    def serve_flaky(self, stall=False):
        """Wie ein wackeliger IPTV-Server: liefert flaky.ts in Echtzeit (nach kurzem Startschub) und
        trennt die Verbindung nach 15 Sekunden. Der Player muss neu verbinden, ohne hängen zu bleiben.
        Mit stall: Statt zu trennen, schickt der Server 12 s lang nichts mehr (die Verbindung bleibt offen),
        wie ein hängender Anbieter. Der Player muss das selbst merken und neu verbinden."""
        global FLAKY_EPOCH
        if FLAKY_EPOCH is None:
            FLAKY_EPOCH = time.time()
        path, seconds = os.path.join(ROOT, "flaky.ts"), 450.0  # 5 × 90 s (make-test-media.sh)
        if not os.path.exists(path):
            path, seconds = os.path.join(ROOT, "hd50.ts"), 90.0
        size = os.path.getsize(path)
        rate = size / seconds  # Bytes pro Sekunde
        m = re.match(r"bytes=(\d+)-", self.headers.get("Range", ""))
        if m:
            start = int(m.group(1)) % size
        else:
            # Wie ein echter Live-Server: dort, wo der Sender gerade ist, mit 2 s Vorlauf aus dem Puffer.
            # Nach einem Abbruch kommen diese 2 s also noch einmal (der Player muss sie überspringen).
            live = (time.time() - FLAKY_EPOCH) * rate
            start = int(max(0.0, live - rate * 2)) % size
            start -= start % 188
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.end_headers()
        began = time.time()
        sent = 0
        burst = rate * 2  # 2 Sekunden Startschub wie bei echten Servern
        with open(path, "rb") as f:
            f.seek(start)
            try:
                while time.time() - began < 15:
                    chunk = f.read(32 * 1024)
                    if not chunk:
                        f.seek(0)
                        continue
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    ahead = (sent - burst) / rate - (time.time() - began)
                    if ahead > 0:
                        time.sleep(ahead)
                if stall:
                    time.sleep(12)
            except (BrokenPipeError, ConnectionResetError):
                pass
        self.close_connection = True

    def do_HEAD(self):
        self.head_only = True
        self.do_GET()

    def serve_file(self):
        """Statische Datei mit Unterstützung für "Range: bytes=a-b"."""
        path = self.translate_path(urlparse(self.path).path)
        if not os.path.isfile(path):
            return self.send_error(404)
        size = os.path.getsize(path)
        start, end = 0, size - 1
        header = self.headers.get("Range")
        partial = False
        if header:
            m = re.match(r"bytes=(\d*)-(\d*)", header)
            if m:
                partial = True
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:
                    start = max(0, size - int(m.group(2)))
                end = min(end, size - 1)
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if partial:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        if getattr(self, "head_only", False):
            return
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            try:
                while remaining > 0:
                    chunk = f.read(min(256 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
