# Dostawca obrazów Gemini (Nano Banana) dla Hermesa

Wbudowani dostawcy obrazów w Hermesie to m.in. FAL, OpenAI, OpenRouter, Krea, xAI -
**nie ma bezpośredniego Gemini**. Ta wtyczka dodaje go, żeby Hermes mógł generować
obrazy Twoim kluczem `GEMINI_API_KEY`. **Nie była uruchamiana z prawdziwym Hermesem ani
z prawdziwym kluczem** - zbudowana wg dokumentacji wtyczek `image_gen`, więc pierwsze
uruchomienie jest sprawdzianem.

## Instalacja (na Macu)

```bash
mkdir -p ~/.hermes/plugins/image_gen
cp -R gemini ~/.hermes/plugins/image_gen/gemini
hermes plugins enable gemini
hermes plugins doctor            # sprawdza wtyczki
hermes config set GEMINI_API_KEY <klucz>     # jeśli jeszcze nie masz
hermes config set image_gen.provider gemini
hermes tools                     # upewnij się, że Image Generation jest włączone także dla API server
hermes -z "Generate an image of a corgi"     # test bez Victora
```

Model: domyślnie `gemini-2.5-flash-image`. Inny (np. Nano Banana Pro) ustawiasz zmienną
`GEMINI_IMAGE_MODEL` w `~/.hermes/.env` - dokładną nazwę sprawdź w Google AI Studio.
Obrazy zapisują się w `~/.hermes/cache/images/` - ten folder jest domyślnie udostępniany
przez most plików, więc Victor otworzy obraz kartą „Pliki od Hermesa".
