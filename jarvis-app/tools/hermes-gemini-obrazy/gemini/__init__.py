"""Dostawca obrazów Gemini (Nano Banana) dla Hermes Agent - NIEPRZETESTOWANY na żywo.

Zbudowany według dokumentacji "image-gen provider plugin" Hermesa
(ImageGenProvider + register(ctx)). Dokumentacja nie ma przykładu dla Gemini, więc
pierwsze uruchomienie jest sprawdzianem:  hermes -z "Generate an image of a corgi"

Konfiguracja:
  klucz:   GEMINI_API_KEY (albo GOOGLE_API_KEY) w ~/.hermes/.env
  model:   GEMINI_IMAGE_MODEL w ~/.hermes/.env, domyślnie gemini-2.5-flash-image
           (Nano Banana Pro to zwykle gemini-3-pro-image-preview - sprawdź nazwy w
           Google AI Studio, bo zmieniają się często)
Obrazy zapisują się w $HERMES_HOME/cache/images/ (domyślnie ~/.hermes/cache/images).
"""
import base64
import json
import os
import time
import urllib.error
import urllib.request

from agent.image_gen_provider import ImageGenProvider, error_response, success_response

DOMYSLNY_MODEL = "gemini-2.5-flash-image"
PROPORCJE = {"1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"}
ROZSZERZENIA = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp"}


def _klucz():
    return os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or ""


def _katalog():
    home = os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes")
    return os.path.join(home, "cache", "images")


def zbuduj_zadanie(prompt, aspect_ratio=None):
    """Treść zapytania do generateContent."""
    body = {"contents": [{"parts": [{"text": prompt}]}]}
    if aspect_ratio in PROPORCJE:
        body["generationConfig"] = {"imageConfig": {"aspectRatio": aspect_ratio}}
    return body


def wyciagnij_obraz(odpowiedz):
    """Z odpowiedzi API zwraca (bajty, mime) pierwszego obrazu albo (None, tekst_modelu)."""
    tekst = []
    for kand in odpowiedz.get("candidates", []):
        for czesc in (kand.get("content") or {}).get("parts", []):
            dane = czesc.get("inlineData") or czesc.get("inline_data")
            if dane and dane.get("data"):
                mime = dane.get("mimeType") or dane.get("mime_type") or "image/png"
                return base64.b64decode(dane["data"]), mime
            if czesc.get("text"):
                tekst.append(czesc["text"])
    return None, " ".join(tekst)[:300]


class GeminiImageProvider(ImageGenProvider):
    @property
    def name(self):
        return "gemini"

    def display_name(self):
        return "Google Gemini (Nano Banana)"

    def is_available(self):
        return bool(_klucz())

    def default_model(self):
        return os.environ.get("GEMINI_IMAGE_MODEL") or DOMYSLNY_MODEL

    def generate(self, prompt, aspect_ratio=None, *, image_url=None, reference_image_urls=None, **kwargs):
        klucz = _klucz()
        model = self.default_model()
        if not klucz:
            return error_response(
                error="Brak GEMINI_API_KEY w ~/.hermes/.env", error_type="missing_credentials", provider="gemini"
            )
        url = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent" % model
        req = urllib.request.Request(
            url,
            data=json.dumps(zbuduj_zadanie(prompt, aspect_ratio)).encode("utf-8"),
            headers={"Content-Type": "application/json", "x-goog-api-key": klucz},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                odp = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            # Bez treści żądania i bez klucza w komunikacie.
            return error_response(
                error="Gemini odpowiedziało kodem %d" % e.code, error_type="api_error", provider="gemini"
            )
        except Exception as e:  # sieć, limit czasu, zły JSON
            return error_response(error="Błąd połączenia: %s" % type(e).__name__, error_type="network", provider="gemini")
        dane, mime_lub_tekst = wyciagnij_obraz(odp)
        if dane is None:
            return error_response(
                error="Model nie zwrócił obrazu. %s" % mime_lub_tekst, error_type="no_image", provider="gemini"
            )
        os.makedirs(_katalog(), exist_ok=True)
        sciezka = os.path.join(
            _katalog(), "gemini-%d.%s" % (int(time.time() * 1000), ROZSZERZENIA.get(mime_lub_tekst, "png"))
        )
        with open(sciezka, "wb") as f:
            f.write(dane)
        return success_response(
            image=sciezka, model=model, prompt=prompt, aspect_ratio=aspect_ratio or "1:1", provider="gemini"
        )


def register(ctx):
    ctx.register_image_gen_provider(GeminiImageProvider())
