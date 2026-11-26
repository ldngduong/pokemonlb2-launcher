"""Verified HTTPS downloads and updater for the Windows launcher."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

import certifi


APP_VERSION = "1.0.1"
LAUNCHER_REPO = "ldngduong/pokemonlb2-launcher"
LAUNCHER_API = f"https://api.github.com/repos/{LAUNCHER_REPO}/releases/latest"
WINDOWS_ASSET = "PokemonLegendBlack2-Windows.exe"
DOWNLOAD_LIMIT = 100 * 1024 * 1024


class HTTPSRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise urllib.error.URLError("GitHub chuyển hướng đến địa chỉ không dùng HTTPS.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_https(request, *, timeout):
    if urllib.parse.urlsplit(request.full_url).scheme != "https":
        raise ValueError("Cần kết nối HTTPS.")
    context = ssl.create_default_context()
    context.load_verify_locations(cafile=certifi.where())
    opener = urllib.request.build_opener(HTTPSRedirectHandler(), urllib.request.HTTPSHandler(context=context))
    return opener.open(request, timeout=timeout)


def connection_error(error):
    reason = getattr(error, "reason", error)
    if isinstance(reason, ssl.SSLCertVerificationError):
        return ("Không thể xác thực chứng chỉ HTTPS của GitHub. "
                "Kiểm tra ngày giờ Windows hoặc thử lại trên mạng khác.")
    return str(error)


def version_number(value):
    if not re.fullmatch(r"v?\d+\.\d+\.\d+", value):
        raise ValueError("Phiên bản launcher không hợp lệ.")
    return tuple(map(int, value.removeprefix("v").split(".")))


def latest_update(user_agent):
    request = urllib.request.Request(LAUNCHER_API, headers={"User-Agent": user_agent, "Accept": "application/vnd.github+json"})
    with open_https(request, timeout=10) as response:
        data = json.load(response)
    tag = data["tag_name"]
    if version_number(tag) <= version_number(APP_VERSION):
        return None
    asset, = [item for item in data["assets"] if item["name"] == WINDOWS_ASSET]
    digest = asset["digest"].removeprefix("sha256:")
    size = asset["size"]
    url = asset["browser_download_url"]
    expected_path = f"https://github.com/{LAUNCHER_REPO}/releases/download/{tag}/{WINDOWS_ASSET}"
    if url != expected_path or not re.fullmatch(r"[0-9a-fA-F]{64}", digest) or not 0 < size <= DOWNLOAD_LIMIT:
        raise ValueError("Thông tin tệp cập nhật launcher không hợp lệ.")
    return tag, url, size, digest


def download_update(update, executable, user_agent, progress, cancelled):
    _, url, expected_size, expected_hash = update
    directory = Path(tempfile.mkdtemp(prefix=".launcher-update-", dir=executable.parent))
    target = directory / "candidate.exe"
    try:
        request = urllib.request.Request(url, headers={"User-Agent": user_agent})
        digest = hashlib.sha256()
        count = 0
        with open_https(request, timeout=25) as response, target.open("xb") as output:
            while chunk := response.read(256 * 1024):
                if cancelled.is_set():
                    raise InterruptedError()
                count += len(chunk)
                if count > expected_size:
                    raise ValueError("Tệp tải về vượt dung lượng công bố.")
                digest.update(chunk)
                output.write(chunk)
                progress(count * 100 // expected_size)
            output.flush()
            os.fsync(output.fileno())
        if cancelled.is_set():
            raise InterruptedError()
        if count != expected_size or digest.hexdigest().lower() != expected_hash.lower():
            raise ValueError("Tệp launcher tải về không khớp SHA-256 hoặc dung lượng.")
        return target
    except Exception:
        target.unlink(missing_ok=True)
        directory.rmdir()
        raise


def start_replacement(candidate, executable, digest, script):
    environment = os.environ.copy()
    environment.update(LEGEND_OTA_CANDIDATE=str(candidate), LEGEND_OTA_EXE=str(executable),
                       LEGEND_OTA_HASH=digest, LEGEND_OTA_PID=str(os.getpid()),
                       PYINSTALLER_RESET_ENVIRONMENT="1")
    command = base64.b64encode(Path(script).read_text(encoding="utf-8").encode("utf-16-le")).decode("ascii")
    powershell = Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    subprocess.Popen([str(powershell), "-NoProfile", "-NonInteractive", "-EncodedCommand", command],
                     env=environment, creationflags=subprocess.CREATE_NO_WINDOW)


def mark_started(executable):
    marker = os.environ.pop("LEGEND_OTA_READY", None)
    if marker:
        path = Path(marker)
        if path.name == "started" and path.parent.name.startswith(".launcher-update-") and path.parent.parent == executable.parent:
            path.touch()
