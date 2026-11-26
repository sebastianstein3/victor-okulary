# Most plików Hermesa dla Victora

API Hermesa nie ma punktu do pobierania plików (sprawdzone w dokumentacji), a
Victor ma pokazywać i otwierać to, co agent wygeneruje: obrazy, PDF-y, arkusze,
dokumenty. Ten skrypt udostępnia **tylko do odczytu** wybrane foldery na
komputerze z Hermesem.

## Uruchomienie (na Macu)

```bash
export HERMES_PLIKI_KLUCZ="$(grep ^API_SERVER_KEY= ~/.hermes/.env | cut -d= -f2-)"
python3 hermes_pliki.py            # 127.0.0.1:8643, foldery: ~/Hermes i ~/.hermes/cache
```

Własne foldery: `--root ~/Hermes --root ~/Documents/Raporty`. Jedyna zależność to Python 3.

Domyślnie most słucha tylko na `127.0.0.1`. Do telefonu wystaw go tak samo jak
Hermesa (Tailscale; adres wpisujesz w Ustawieniach → Hermes → „Adres mostu plików").
**Nie ustawiaj `--host 0.0.0.0`** w sieci, której nie kontrolujesz.

## Co jest zabezpieczone

- ten sam klucz co brama Hermesa (nagłówek `Authorization: Bearer`),
- tylko pliki z folderów `--root`, po rozwiązaniu dowiązań symbolicznych,
- tylko znane rozszerzenia (dokumenty, obrazy, dane, dźwięk, wideo, zip) - nie kod, nie klucze,
- elementy ścieżki zaczynające się od kropki (np. `.ssh`, `.env`) są odrzucane,
- limit rozmiaru (`--max-mb`, domyślnie 50).

Przetestowane: `../`, dowiązanie poza folder, dowiązanie do `/etc`, ukryty
folder, plik `.py`, `/etc/passwd`, zły i brak klucza.

## Jak Hermes ma o tym wiedzieć

Victor dopisuje do polecenia prośbę, by Hermes wypisywał pliki w osobnych liniach
`PLIK: /pełna/ścieżka`. Najlepiej, by zapisywał je w jednym z folderów powyżej
(`terminal.cwd: ~/Hermes` w `~/.hermes/config.yaml`).
