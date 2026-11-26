#!/usr/bin/env python3
"""Most plików Hermesa dla aplikacji Victor - uruchamiany na komputerze z Hermesem.

Po co: API Hermesa nie ma punktu do pobierania plików, a Victor ma je pokazać
(obrazy, PDF-y, arkusze…). Ten skrypt udostępnia TYLKO ODCZYT wybranych folderów.

Bezpieczeństwo (to jest cała jego wartość):
  - wymaga tego samego klucza co brama Hermesa (nagłówek Authorization: Bearer),
  - serwuje wyłącznie pliki z folderów podanych w --root (domyślnie ~/Hermes
    i ~/.hermes/cache), po rozwiązaniu dowiązań symbolicznych (realpath),
  - tylko znane rozszerzenia dokumentów/obrazów/danych (nie kod, nie klucze),
  - odrzuca ścieżki, w których pod folderem głównym jest element zaczynający się
    od kropki (np. .ssh, .env), oraz pliki większe niż --max-mb,
  - domyślnie słucha tylko na 127.0.0.1 (--host zmieniasz świadomie; do
    telefonu użyj Tailscale, a nie 0.0.0.0).

Uruchomienie:
  export HERMES_PLIKI_KLUCZ="$(grep ^API_SERVER_KEY= ~/.hermes/.env | cut -d= -f2-)"
  python3 hermes_pliki.py            # 127.0.0.1:8643, foldery domyślne
  python3 hermes_pliki.py --root ~/Hermes --root ~/Documents/Raporty --port 8643

Wymaga tylko Pythona 3 (biblioteka standardowa).
"""
import argparse
import hmac
import mimetypes
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

DOZWOLONE = {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "tiff",
    "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "html", "epub", "pptx",
    "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
    "mp3", "wav", "m4a", "ogg", "mp4", "mov", "webm", "zip",
}


def rozwiaz(sciezka, katalogi):
    """Zwraca prawdziwą ścieżkę pliku albo None, gdy wolno jej NIE serwować."""
    if not sciezka or "\x00" in sciezka:
        return None
    pelna = os.path.realpath(os.path.expanduser(sciezka))
    ext = os.path.splitext(pelna)[1].lstrip(".").lower()
    if ext not in DOZWOLONE or not os.path.isfile(pelna):
        return None
    for root in katalogi:
        try:
            if os.path.commonpath([pelna, root]) != root:
                continue
        except ValueError:
            continue
        wzgledna = os.path.relpath(pelna, root)
        if any(czesc.startswith(".") for czesc in wzgledna.split(os.sep)):
            return None
        return pelna
    return None


def zrob_handler(klucz, katalogi, max_bajtow):
    class Handler(BaseHTTPRequestHandler):
        server_version = "HermesPliki/1"

        def log_message(self, fmt, *args):  # bez kluczy i zapytań w logu
            sys.stderr.write("%s %s\n" % (self.address_string(), self.command + " " + urlparse(self.path).path))

        def _odpowiedz(self, kod, tekst):
            dane = tekst.encode("utf-8")
            self.send_response(kod)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(dane)))
            self.end_headers()
            self.wfile.write(dane)

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/health":
                return self._odpowiedz(200, "ok")
            if url.path != "/plik":
                return self._odpowiedz(404, "nie ma takiego punktu")
            naglowek = self.headers.get("Authorization", "")
            podany = naglowek[7:] if naglowek.startswith("Bearer ") else ""
            if not hmac.compare_digest(podany.encode(), klucz.encode()):
                return self._odpowiedz(401, "zły klucz")
            sciezka = (parse_qs(url.query).get("sciezka") or [""])[0]
            pelna = rozwiaz(sciezka, katalogi)
            if pelna is None:
                return self._odpowiedz(404, "brak pliku albo leży poza dozwolonymi folderami")
            rozmiar = os.path.getsize(pelna)
            if rozmiar > max_bajtow:
                return self._odpowiedz(413, "plik za duży")
            typ = mimetypes.guess_type(pelna)[0] or "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", typ)
            self.send_header("Content-Length", str(rozmiar))
            self.send_header(
                "Content-Disposition",
                "attachment; filename*=UTF-8''" + quote(os.path.basename(pelna)),
            )
            self.end_headers()
            with open(pelna, "rb") as f:
                while True:
                    blok = f.read(64 * 1024)
                    if not blok:
                        break
                    self.wfile.write(blok)

    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8643)
    ap.add_argument("--root", action="append", help="folder do udostępnienia (można kilka razy)")
    ap.add_argument("--max-mb", type=int, default=50)
    a = ap.parse_args()

    klucz = os.environ.get("HERMES_PLIKI_KLUCZ") or os.environ.get("API_SERVER_KEY") or ""
    if len(klucz) < 16:
        sys.exit("Brak klucza (min. 16 znaków): ustaw HERMES_PLIKI_KLUCZ (ten sam co API_SERVER_KEY Hermesa).")
    roots = a.root or ["~/Hermes", "~/.hermes/cache"]
    katalogi = [os.path.realpath(os.path.expanduser(r)) for r in roots]
    for k in katalogi:
        print("udostępniam (tylko odczyt):", k)
    srv = ThreadingHTTPServer((a.host, a.port), zrob_handler(klucz, katalogi, a.max_mb * 1024 * 1024))
    print("Most plików słucha na http://%s:%d" % (a.host, a.port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
