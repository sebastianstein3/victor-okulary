#!/usr/bin/env python3
"""
Reproducible training pipeline for a custom openWakeWord classifier: "hej wiktor" (Polish).

Stages (run in order, each caches its outputs under ./data):
  python3 -I train_hej_wiktor.py --stage bank     # synthesize background-speech bank (babble) with Piper
  python3 -I train_hej_wiktor.py --stage clips    # synthesize + augment positive / adversarial clips (3.2 s, 16 kHz int16)
  python3 -I train_hej_wiktor.py --stage feats    # openwakeword.utils.AudioFeatures.embed_clips -> (16,96) windows
  python3 -I train_hej_wiktor.py --stage acav     # assemble range-downloaded ACAV100M feature slices (see dl_acav.sh)
  python3 -I train_hej_wiktor.py --stage train    # train MLP (torch, CPU) and export ONNX hej_wiktor.onnx
  python3 -I train_hej_wiktor.py --stage eval     # evaluate with openwakeword.Model (streaming) + validation FA/h
  python3 -I train_hej_wiktor.py --stage wavs     # write 3 on-device sanity-test WAVs
Or: --stage all

Inputs expected in the work dir (downloaded beforehand, see README.md):
  voices/*.onnx(.json)              Piper voices (5 x pl_PL + en_US-libritts_r-medium)
  dl/acav/chunk_XX.bin              30 raw slices of openwakeword_features_ACAV100M_2000_hrs_16bit.npy (float16 rows of 16x96)
  dl/val/validation_set_features.npy
"""
import argparse
import json
import os
import random
import sys
import time
from multiprocessing import Pool

import numpy as np

WORK = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(WORK, "data")
VOICES = os.path.join(WORK, "voices")
os.makedirs(DATA, exist_ok=True)

SR = 16000
CLIP_LEN = 51200          # 3.2 s -> 31 embedding frames from embed_clips
N_EMB = 31
WIN = 16                  # classifier input: 16 embeddings x 96
SEED = 1234

PL_VOICES = ["pl_PL-bass-high", "pl_PL-darkman-medium", "pl_PL-gosia-medium",
             "pl_PL-mc_speech-medium", "pl_PL-mls_6892-low"]
EN_VOICE = "en_US-libritts_r-medium"
HELDOUT_PL_VOICE = "pl_PL-darkman-medium"
N_LIBRI = 904
rng_split = np.random.RandomState(7)
HELDOUT_LIBRI = set(rng_split.choice(N_LIBRI, 90, replace=False).tolist())

# ----------------------------------------------------------------------------- texts
POS_PL = ["hej wiktor", "hej, wiktor", "hej wiktorze", "hej, wiktorze", "hej wiktor!", "hej wiktorze!",
          "hej wiktor.", "hej, wiktor!"]
POS_EN = ["hey victor", "hey viktor", "hay victor", "hey, victor", "hey victor!", "hey, viktor."]
# Polish text spoken by English LibriTTS-R speakers through Polish espeak phonemes
POS_PL_BY_EN = ["hej wiktor", "hej, wiktor", "hej wiktorze"]

ADV_PL = ["hej", "hej!", "wiktor", "wiktor!", "wiktoria", "hej wiktoria", "hej, wiktoria", "hej victoria",
          "dzień dobry", "hej kolego", "piktor", "hej doktor", "hej, kto to", "rektor", "inspektor",
          "hej doktorze", "dyrektor", "hej rektorze", "hej hektor", "wiktorze", "hej tato", "hej mamo",
          "hej wiktorio", "okej", "hejka", "hej, wiesz co", "hej, piotrze", "victoria", "hej, wiktorek? nie",
          "hej, wikary", "hej inspektorze", "pan wiktor", "hej, tylko tyle", "hej kierowco", "proszę pana"]
ADV_EN = ["hey", "victor", "hey doctor", "hey victoria", "victoria", "hey there", "picture", "hey director",
          "hey vic", "hey, what's up", "hey taylor", "victory", "hey hector", "hey mentor", "okay"]

SENT_PL = """Dzisiaj rano padał deszcz, ale teraz świeci słońce.
Czy możesz mi podać sól i pieprz?
Jutro jadę pociągiem do Krakowa.
Muszę jeszcze zrobić zakupy na kolację.
Nie wiem, gdzie położyłem klucze od mieszkania.
Ten film był naprawdę bardzo ciekawy.
Proszę zamknąć drzwi, bo jest przeciąg.
Moja siostra mieszka w Gdańsku od pięciu lat.
Zadzwonię do ciebie wieczorem po pracy.
Kawa jest za gorąca, poczekam chwilę.
Czy wiesz, która jest godzina?
W sobotę idziemy na spacer do parku.
Pies sąsiadów szczekał całą noc.
Kupiłem nowe okulary, bo stare się zepsuły.
Lubię czytać książki przed snem.
Ile kosztuje bilet na autobus?
Musimy porozmawiać o planach na wakacje.
Na obiad będzie zupa pomidorowa i kotlet.
Spóźniłem się na spotkanie przez korki.
Wczoraj oglądaliśmy mecz w telewizji.
Proszę mówić trochę wolniej.
To jest bardzo dobre pytanie.
Gdzie jest najbliższa apteka?
Mój telefon znowu się rozładował.
Zostawiłem parasol w tramwaju.
W lodówce nie ma już mleka.
Dziecko bawi się klockami na dywanie.
Dzień był długi i męczący.
Czy mogę zapłacić kartą?
Nasz pociąg ma dwadzieścia minut opóźnienia.
Szef poprosił mnie o raport do piątku.
Ten sklep jest otwarty do dwudziestej drugiej.
Wiosną w ogrodzie kwitną tulipany.
Zimą często jeździmy na narty w góry.
Kolega pożyczył mi rower na weekend.
Trzeba umyć naczynia po obiedzie.
Lekarz powiedział, że to nic poważnego.
Nie zapomnij zabrać dokumentów.
Mam ochotę na lody czekoladowe.
Babcia upiekła pyszne ciasto ze śliwkami.
Kot śpi na parapecie w słońcu.
Na skrzyżowaniu skręć w lewo.
Czy ktoś widział moją niebieską kurtkę?
Wieczorem będzie burza z piorunami.
Poproszę dwa bilety ulgowe.
Mój brat studiuje informatykę we Wrocławiu.
Ta droga prowadzi prosto nad jezioro.
Komputer działa bardzo wolno.
Musimy wymienić żarówkę w kuchni.
Zrobiło się zimno, załóż sweter.
Pociąg odjeżdża z peronu trzeciego.
Kto dzisiaj gotuje obiad?
Zamówiłem pizzę z pieczarkami.
To była najlepsza podróż w moim życiu.
Spotkajmy się przed kinem o siódmej.
Nie lubię wstawać wcześnie rano.
Na rynku sprzedają świeże truskawki.
Woda w rzece jest dzisiaj bardzo zimna.
Pamiętaj, żeby podlać kwiaty.
W bibliotece trzeba być cicho.
Mamy jeszcze dużo czasu do odjazdu.
Proszę włączyć światło w przedpokoju.
Czy jutro będzie padać śnieg?
Autobus numer sto siedemnaście właśnie odjechał.
Muszę naładować baterie w aparacie.
Wszyscy czekają na ciebie w samochodzie.
Ta piosenka leci ciągle w radiu.
Na urodziny dostałem nowy zegarek.
Sąsiad remontuje mieszkanie od miesiąca.
Lubię słuchać muzyki klasycznej.
Nauczyciel zadał nam dużo pracy domowej.
W tym roku lato było bardzo gorące.
Herbata z cytryną dobrze robi na gardło.
Kiedy wracasz z delegacji?
Przepraszam, którędy na dworzec?
Okno w sypialni się nie domyka.
Zgubiłem portfel w autobusie.
Dzieci wróciły ze szkoły bardzo głodne.
Zaraz zacznie się prognoza pogody.
Ten samochód pali za dużo benzyny.
Wyślę ci wiadomość, jak dojadę.
Mam spotkanie z klientem o dziesiątej.
Pomidory trzeba pokroić w kostkę.
Na drodze jest wypadek i duży korek.
Czy słyszałeś najnowsze wiadomości?
Ola gra na pianinie od dziecka.
Marek naprawia kran w łazience.
Wyjdź z psem na spacer, proszę.
Jest już późno, idę spać.
W poniedziałek zaczynam nową pracę.
Bardzo dziękuję za pomoc.
Nie ma za co, to drobiazg.
Gdzie jest pilot do telewizora?
Dzisiaj jest wyjątkowo ciepło jak na listopad.
Po drugiej stronie ulicy otworzyli piekarnię.
Ile jajek potrzeba do naleśników?
Rano biegam dookoła osiedla.
Zostało nam tylko pięć minut.
Musisz skręcić w drugą ulicę w prawo.
Słyszę jakiś dziwny dźwięk w silniku.
Jak się dzisiaj czujesz?
Mama prosi, żebyś do niej zadzwonił.
To miasto jest piękne nocą.
Poczekaj chwilę, zaraz wracam.
Wiktor i Kasia pojechali nad morze w zeszłym tygodniu.
Spotkałem wczoraj Wiktorię w sklepie.
Doktor Nowak przyjmuje we wtorki.
Rektor otworzył nowy rok akademicki.
Inspektor sprawdził wszystkie dokumenty.
Dyrektor szkoły przemawiał na apelu.
Hej, co słychać u ciebie?
Hej, dawno się nie widzieliśmy.
Hej, czy ktoś tu jest?
Wiktoria ma dziś urodziny.
Zapytaj Wiktora, on będzie wiedział.
Wiktorze, podaj mi proszę talerz.
Ten obraz namalował znany malarz.
Na stole leży gazeta z wczoraj.
Kolejka do kasy była bardzo długa.
Zaraz będzie dzwonek na przerwę.
Ciężarówka zablokowała cały wjazd.
W ogrodzie rosną jabłonie i grusze.
Kupiłam bilety na koncert w grudniu.
Proszę o rachunek.
Jaka jest prognoza na weekend?
Włącz muzykę trochę głośniej.
Ścisz trochę radio, proszę.
Ustaw budzik na szóstą trzydzieści.
Przypomnij mi jutro o lekarzu.
Która drużyna wygrała wczoraj mecz?
Ile kilometrów jest do Poznania?
Otwórz okno, bo jest duszno.
Zrób mi zdjęcie przy fontannie.
Nie mogę znaleźć ładowarki.
Wszystkiego najlepszego z okazji urodzin.
Smacznego!
Do zobaczenia jutro.
Dobranoc, śpij dobrze.
Jak dojść do muzeum narodowego?
Mieszkam na trzecim piętrze bez windy.
Przez okno widać góry.
Ten czajnik gwiżdże, kiedy woda się zagotuje.
Pociąg do Warszawy jest pełny.
Zapisałem się na kurs hiszpańskiego.
Wieczorem robimy grilla na działce.
Chyba zostawiłem włączone żelazko.
Pożycz mi długopis na chwilę.""".strip().split("\n")

SENT_EN = """The weather is really nice today, isn't it?
I need to pick up some groceries on the way home.
Could you please turn down the music a little?
We are going to the cinema on Friday night.
My brother just bought a new car.
The meeting has been moved to three o'clock.
Have you seen my keys anywhere?
It took us almost two hours to get there.
She plays the violin in the city orchestra.
Let's have dinner at the Italian place tonight.
The train was delayed because of the snow.
I think it's going to rain this afternoon.
Can you send me the report by tomorrow?
The kids are playing football in the garden.
This coffee is way too strong for me.
He told me a very funny story yesterday.
We should leave early to avoid the traffic.
The museum is closed on Mondays.
I'm reading a book about the history of Rome.
Please remember to water the plants.
Victoria and Hector went to the beach.
The doctor will see you in a few minutes.
Our director announced a new project.
There's a picture of the mountains on the wall.
What time does the store open on Sunday?
I can't believe how fast the year has gone.
The dog barked at the mailman again.
Turn left at the second traffic light.
My phone battery is almost dead.
Thank you so much for your help.""".strip().split("\n")


# ----------------------------------------------------------------------------- audio utils
def resample_to_16k(x, sr):
    if sr == SR:
        return x.astype(np.float32)
    from scipy.signal import resample_poly
    from math import gcd
    g = gcd(SR, sr)
    return resample_poly(x, SR // g, sr // g).astype(np.float32)


def trim(x, rel_db=-30.0, margin=0.03):
    """Trim leading/trailing silence using 10 ms RMS frames."""
    fl = 160
    n = len(x) // fl
    if n < 3:
        return x
    rms = np.sqrt(np.mean(x[:n * fl].reshape(n, fl) ** 2, axis=1) + 1e-12)
    thr = rms.max() * 10 ** (rel_db / 20)
    idx = np.where(rms > thr)[0]
    s = max(0, idx[0] * fl - int(margin * SR))
    e = min(len(x), (idx[-1] + 1) * fl + int(margin * SR))
    return x[s:e]


def colored_noise(n, kind, rs):
    if kind == "white":
        y = rs.randn(n)
    else:
        X = np.fft.rfft(rs.randn(n))
        f = np.arange(len(X)) + 1.0
        X = X / (np.sqrt(f) if kind == "pink" else f)
        y = np.fft.irfft(X, n)
    y = y / (np.std(y) + 1e-9)
    return y.astype(np.float32)


def reverb(x, rs):
    from scipy.signal import fftconvolve
    rt60 = rs.uniform(0.15, 0.8)
    L = int(SR * min(rt60, 0.6))
    t = np.arange(L) / SR
    ir = rs.randn(L) * np.exp(-6.9 * t / rt60)
    ir[0] = 0
    ir = ir / (np.sqrt(np.sum(ir ** 2)) + 1e-9)
    wet = rs.uniform(0.1, 0.6)
    pre = int(rs.uniform(0.002, 0.02) * SR)
    ir = np.concatenate([[1.0], np.zeros(pre), wet * ir])
    y = fftconvolve(x, ir)[: len(x) + int(0.25 * SR)]
    return (y / (np.max(np.abs(y)) + 1e-9) * np.max(np.abs(x))).astype(np.float32)


def bandlimit(x, rs, kind):
    from scipy.signal import butter, sosfilt
    if kind == "tel":
        sos = butter(4, [rs.uniform(250, 400), rs.uniform(3000, 3600)], btype="band", fs=SR, output="sos")
    else:  # random high-pass (cheap phone mic / wind filter)
        sos = butter(2, rs.uniform(80, 250), btype="high", fs=SR, output="sos")
    return sosfilt(sos, x).astype(np.float32)


def rms(x):
    return float(np.sqrt(np.mean(x.astype(np.float64) ** 2) + 1e-12))


# ----------------------------------------------------------------------------- piper workers
_V = {}
_BANK = None


def _load_voices(names):
    import onnxruntime as ort
    from piper import PiperVoice
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    for n in names:
        p = os.path.join(VOICES, n + ".onnx")
        v = PiperVoice.load(p)
        v.session = ort.InferenceSession(p, sess_options=so, providers=["CPUExecutionProvider"])
        _V[n] = v


def _init_worker(with_bank):
    global _BANK
    _load_voices(PL_VOICES + [EN_VOICE])
    if with_bank:
        b = np.load(os.path.join(DATA, "bank.npz"))
        _BANK = {"audio": b["audio"], "offsets": b["offsets"], "split": b["split"]}


def synth(job):
    """Synthesize one utterance -> float32 16 kHz in [-1,1] (trimmed)."""
    from piper import SynthesisConfig
    v = _V[job["voice"]]
    cfg = SynthesisConfig(speaker_id=job.get("speaker"), length_scale=job["ls"], noise_scale=job["ns"],
                          noise_w_scale=job["nw"], normalize_audio=True)
    if job.get("phon") == "pl" and job["voice"] == EN_VOICE:
        # Polish espeak phonemes rendered by an English multi-speaker voice
        phs = _V["pl_PL-gosia-medium"].phonemize(job["text"])
        audio = []
        for ph in phs:
            ids = v.phonemes_to_ids(ph)
            a = v.phoneme_ids_to_audio(ids, cfg)
            audio.append(a / (np.max(np.abs(a)) + 1e-9) * 0.9)
        x = np.concatenate(audio)
    else:
        x = np.concatenate([c.audio_float_array for c in v.synthesize(job["text"], cfg)])
    x = resample_to_16k(x, v.config.sample_rate)
    return trim(x)


def bank_job(job):
    try:
        return job["split"], synth(job)
    except Exception as e:  # noqa
        print("bank err", job, e, flush=True)
        return job["split"], np.zeros(1600, np.float32)


def _babble(rs, n, split):
    """Random segment of unrelated TTS speech from the bank (same split only)."""
    a, off, sp = _BANK["audio"], _BANK["offsets"], _BANK["split"]
    ids = np.where(sp == split)[0]
    out = np.zeros(n, np.float32)
    pos = int(rs.uniform(-0.5, 0.3) * SR)
    while pos < n:
        i = rs.choice(ids)
        seg = a[off[i]:off[i + 1]].astype(np.float32) / 32767.0
        s = max(0, pos)
        k = min(n - s, len(seg) - max(0, -pos))
        out[s:s + k] += seg[max(0, -pos):max(0, -pos) + k]
        pos += len(seg) + int(rs.uniform(0.05, 0.6) * SR)
    return out


def augment_place(x, job):
    """Place utterance in a 3.2 s clip with background, reverb, band-limiting and gain. Returns int16 clip, phrase end."""
    rs = np.random.RandomState(job["seed"])
    if rs.rand() < 0.35:
        x = reverb(x, rs)
        x = trim(x, -40)
    sp_rms = rms(x)
    if job["kind"] == "sent":
        # ordinary sentence: random crop/placement anywhere
        if len(x) > CLIP_LEN:
            s = rs.randint(0, len(x) - CLIP_LEN)
            x = x[s:s + CLIP_LEN]
            start = 0
        else:
            start = rs.randint(0, CLIP_LEN - len(x) + 1)
    else:
        te = rs.uniform(1.85, 2.45) if job["kind"] == "pos" else rs.uniform(1.2, 3.1)
        start = int(te * SR) - len(x)
        if start < 0:
            x = x[-start:]
            start = 0
        x = x[:CLIP_LEN - start]
    clip = np.zeros(CLIP_LEN, np.float32)
    clip[start:start + len(x)] = x
    end = (start + len(x)) / SR
    # background
    r = rs.rand()
    bg = np.zeros(CLIP_LEN, np.float32)
    if r < 0.85:
        kinds = []
        if rs.rand() < 0.75:
            kinds.append("noise")
        if rs.rand() < 0.5 or not kinds:
            kinds.append("babble")
        for k in kinds:
            if k == "noise":
                b = colored_noise(CLIP_LEN, rs.choice(["white", "pink", "brown", "pink"]), rs)
            else:
                b = _babble(rs, CLIP_LEN, job["split"])
                if rs.rand() < 0.5:
                    b = reverb(b, rs)[:CLIP_LEN]
            snr = rs.uniform(5, 20) + (3 if len(kinds) == 2 else 0)
            bg += b / (rms(b) + 1e-9) * sp_rms * 10 ** (-snr / 20)
    else:
        bg = colored_noise(CLIP_LEN, "pink", rs) * sp_rms * 10 ** (-rs.uniform(30, 45) / 20)
    clip = clip + bg
    r = rs.rand()
    if r < 0.15:
        clip = bandlimit(clip, rs, "tel")
    elif r < 0.4:
        clip = bandlimit(clip, rs, "hp")
    peak = np.max(np.abs(clip)) + 1e-9
    target = 10 ** (rs.uniform(-30, -1) / 20)
    clip = clip / peak * target
    return np.clip(clip * 32767, -32768, 32767).astype(np.int16), end, start / SR


def clip_job(job):
    try:
        x = synth(job)
        c, end, start = augment_place(x, job)
        return c, end, start
    except Exception as e:  # noqa
        print("clip err", job, e, flush=True)
        return None, 0.0, 0.0


# ----------------------------------------------------------------------------- job lists
def _params(rs):
    return dict(ls=float(rs.uniform(0.8, 1.3)), ns=float(rs.uniform(0.3, 0.9)), nw=float(rs.uniform(0.3, 1.0)))


def make_bank_jobs():
    rs = np.random.RandomState(SEED)
    jobs = []
    for s in SENT_PL:
        for v in PL_VOICES:
            jobs.append(dict(voice=v, text=s, split="test" if v == HELDOUT_PL_VOICE else "train", **_params(rs)))
    for s in SENT_EN:
        for _ in range(6):
            spk = int(rs.randint(N_LIBRI))
            jobs.append(dict(voice=EN_VOICE, speaker=spk, text=s, split="test" if spk in HELDOUT_LIBRI else "train",
                             **_params(rs)))
    for s in SENT_PL[:60]:
        for _ in range(3):
            spk = int(rs.randint(N_LIBRI))
            jobs.append(dict(voice=EN_VOICE, speaker=spk, text=s, phon="pl",
                             split="test" if spk in HELDOUT_LIBRI else "train", **_params(rs)))
    return jobs


def make_clip_jobs(n_pl_per_voice=700, en_per_spk=4, plen_per_spk=3, adv_pl_per_voice_text=36,
                   adv_libri_pl=3, adv_libri_en=2, sent_per_voice=2, sent_libri=1):
    rs = np.random.RandomState(SEED + 1)
    jobs = []

    def add(kind, label, voice, text, speaker=None, phon=None):
        split = "test" if (voice == HELDOUT_PL_VOICE or (speaker is not None and speaker in HELDOUT_LIBRI)) else "train"
        jobs.append(dict(kind=kind, label=label, voice=voice, text=text, speaker=speaker, phon=phon, split=split,
                         seed=int(rs.randint(2 ** 31)), **_params(rs)))

    for v in PL_VOICES:
        for i in range(n_pl_per_voice):
            add("pos", 1, v, POS_PL[i % len(POS_PL)])
    for spk in range(N_LIBRI):
        for i in range(en_per_spk):
            add("pos", 1, EN_VOICE, POS_EN[rs.randint(len(POS_EN))], speaker=spk)
        for i in range(plen_per_spk):
            add("pos", 1, EN_VOICE, POS_PL_BY_EN[i % len(POS_PL_BY_EN)], speaker=spk, phon="pl")
    for v in PL_VOICES:
        for t in ADV_PL:
            for i in range(adv_pl_per_voice_text):
                add("adv", 0, v, t)
        for s in SENT_PL:
            for i in range(sent_per_voice):
                add("sent", 0, v, s)
    for spk in range(N_LIBRI):
        for i in range(adv_libri_pl):
            add("adv", 0, EN_VOICE, ADV_PL[rs.randint(len(ADV_PL))], speaker=spk, phon="pl")
        for i in range(adv_libri_en):
            add("adv", 0, EN_VOICE, ADV_EN[rs.randint(len(ADV_EN))], speaker=spk)
        for i in range(sent_libri):
            if rs.rand() < 0.5:
                add("sent", 0, EN_VOICE, SENT_PL[rs.randint(len(SENT_PL))], speaker=spk, phon="pl")
            else:
                add("sent", 0, EN_VOICE, SENT_EN[rs.randint(len(SENT_EN))], speaker=spk)
    return jobs


# ----------------------------------------------------------------------------- stages
def stage_bank(args):
    jobs = make_bank_jobs()
    t = time.time()
    with Pool(args.procs, initializer=_init_worker, initargs=(False,)) as p:
        res = p.map(bank_job, jobs, chunksize=8)
    auds = [np.clip(a * 32767 * 0.9 / (np.max(np.abs(a)) + 1e-9), -32768, 32767).astype(np.int16) for _, a in res]
    offs = np.concatenate([[0], np.cumsum([len(a) for a in auds])])
    np.savez(os.path.join(DATA, "bank.npz"), audio=np.concatenate(auds), offsets=offs,
             split=np.array([s for s, _ in res]))
    print(f"bank: {len(jobs)} utts, {offs[-1] / SR / 3600:.2f} h, {time.time() - t:.0f}s")


def stage_clips(args):
    jobs = make_clip_jobs(**json.loads(args.sizes)) if args.sizes else make_clip_jobs()
    print("clip jobs:", len(jobs), {k: sum(1 for j in jobs if j["kind"] == k) for k in ["pos", "adv", "sent"]})
    t = time.time()
    clips = np.zeros((len(jobs), CLIP_LEN), np.int16)
    ends = np.zeros(len(jobs), np.float32)
    starts = np.zeros(len(jobs), np.float32)
    ok = np.zeros(len(jobs), bool)
    with Pool(args.procs, initializer=_init_worker, initargs=(True,)) as p:
        for i, (c, e, s) in enumerate(p.imap(clip_job, jobs, chunksize=16)):
            if c is not None:
                clips[i], ends[i], starts[i], ok[i] = c, e, s, True
            if i % 2000 == 0:
                print(i, f"{time.time() - t:.0f}s", flush=True)
    np.save(os.path.join(DATA, "clips.npy"), clips)
    meta = dict(ends=ends, starts=starts, ok=ok,
                kind=np.array([j["kind"] for j in jobs]), label=np.array([j["label"] for j in jobs]),
                voice=np.array([j["voice"] for j in jobs]), split=np.array([j["split"] for j in jobs]),
                speaker=np.array([-1 if j["speaker"] is None else j["speaker"] for j in jobs]),
                text=np.array([j["text"] for j in jobs]), phon=np.array([j["phon"] or "" for j in jobs]))
    np.savez(os.path.join(DATA, "clips_meta.npz"), **meta)
    print(f"clips done {time.time() - t:.0f}s, ok {ok.sum()}/{len(ok)}")


def get_audio_features():
    from openwakeword.utils import AudioFeatures
    return AudioFeatures(inference_framework="onnx", ncpu=args_global.procs)


def stage_feats(args):
    clips = np.load(os.path.join(DATA, "clips.npy"), mmap_mode="r")
    F = get_audio_features()
    out = np.lib.format.open_memmap(os.path.join(DATA, "clip_emb.npy"), mode="w+", dtype=np.float16,
                                    shape=(clips.shape[0], N_EMB, 96))
    t = time.time()
    B = 512
    for i in range(0, clips.shape[0], B):
        e = F.embed_clips(np.asarray(clips[i:i + B]), batch_size=B, ncpu=args.procs)
        assert e.shape[1] == N_EMB, e.shape
        out[i:i + B] = e.astype(np.float16)
        if i % (B * 10) == 0:
            print(i, f"{time.time() - t:.0f}s", flush=True)
    out.flush()
    print(f"feats done {time.time() - t:.0f}s")


def emb_end_time(k):
    """Audio time (s) at which embedding frame k (0-based) of embed_clips ends."""
    return ((8 * k + 75) * 160 + 400) / SR


def build_windows(split):
    """Return (X (n,16,96) float16, y, clip_idx) windows for a split."""
    m = np.load(os.path.join(DATA, "clips_meta.npz"))
    E = np.load(os.path.join(DATA, "clip_emb.npy"), mmap_mode="r")
    Xs, ys, cs = [], [], []
    for i in np.where((m["split"] == split) & m["ok"])[0]:
        e = np.asarray(E[i])
        if m["label"][i] == 1:
            te, ts = m["ends"][i], m["starts"][i]
            for k in range(WIN - 1, N_EMB):
                wend = emb_end_time(k)
                wstart = emb_end_time(k) - emb_end_time(WIN - 1)  # start time of first frame in window
                d = wend - te
                if 0.12 <= d <= 0.7 and ts >= wstart - 0.05:
                    Xs.append(e[k - WIN + 1:k + 1]); ys.append(1); cs.append(i)
        else:
            for k in range(WIN - 1, N_EMB):
                Xs.append(e[k - WIN + 1:k + 1]); ys.append(0); cs.append(i)
    return np.stack(Xs).astype(np.float16), np.array(ys, np.float32), np.array(cs)


def acav_slices():
    """(path, start_row) of every range-downloaded slice. Must match dl_acav.sh / dl_acav2.sh."""
    NROWS = 5625000
    out = []
    for d, nch, ch, off, span in [("acav", 30, 32768, 0, 0), ("acav2", 400, 3277, 16000, 32000)]:
        for i in range(nch):
            f = os.path.join(WORK, "dl", d, f"chunk_{i:02d}.bin")
            if os.path.exists(f):
                out.append((f, off + i * (NROWS - ch - span) // (nch - 1), d, i))
    return out


def stage_acav(args):
    """Assemble ACAV slices. Dev (never trained on) = slices 28,29 of the first download; acav2 slices that overlap
    those row ranges are dropped."""
    sl = acav_slices()
    dev_ranges = [(r, r + 32768) for f, r, d, i in sl if d == "acav" and i >= 28]
    dev = [x for x in sl if x[2] == "acav" and x[3] >= 28]
    train = [x for x in sl if x not in dev and not any(lo - 3277 <= x[1] < hi for lo, hi in dev_ranges)]
    for name, lst in [("acav_dev.npy", dev), ("acav_train.npy", train)]:
        n = sum(os.path.getsize(f) // 3072 for f, *_ in lst)
        out = np.lib.format.open_memmap(os.path.join(DATA, name), mode="w+", dtype=np.float16, shape=(n, 16, 96))
        p = 0
        for f, *_ in lst:
            a = np.fromfile(f, dtype="<f2")
            a = a[: (len(a) // 1536) * 1536].reshape(-1, 16, 96)
            out[p:p + len(a)] = a
            p += len(a)
        out.flush()
        print(name, "windows:", n, "slices:", len(lst), "finite:", bool(np.isfinite(out[::997]).all()))
    old = os.path.join(DATA, "acav.npy")
    if os.path.exists(old):
        os.remove(old)


# ----------------------------------------------------------------------------- model
def make_model(h=32):
    import torch.nn as nn
    return nn.Sequential(nn.Flatten(), nn.Linear(16 * 96, h), nn.LayerNorm(h), nn.ReLU(),
                         nn.Linear(h, h), nn.LayerNorm(h), nn.ReLU(), nn.Linear(h, 1), nn.Sigmoid())


def predict_np(model, X, bs=65536):
    import torch
    out = []
    with torch.no_grad():
        for i in range(0, len(X), bs):
            out.append(model(torch.from_numpy(np.asarray(X[i:i + bs], dtype=np.float32))).numpy()[:, 0])
    return np.concatenate(out) if out else np.zeros(0)


def stage_train(args):
    import torch
    torch.manual_seed(SEED)
    np.random.seed(SEED)
    torch.set_num_threads(args.procs)
    Xtr, ytr, ctr = build_windows("train")
    # small internal dev split of training clips (by clip) for model selection
    uc = np.unique(ctr)
    dev_clips = set(np.random.RandomState(3).choice(uc, len(uc) // 20, replace=False).tolist())
    dmask = np.array([c in dev_clips for c in ctr])
    Xdev, ydev = Xtr[dmask], ytr[dmask]
    Xtr, ytr = Xtr[~dmask], ytr[~dmask]
    pos, tneg = Xtr[ytr == 1], Xtr[ytr == 0]
    acav = np.load(os.path.join(DATA, "acav_train.npy"))  # float16 in RAM
    dev_acav = np.load(os.path.join(DATA, "acav_dev.npy"))  # held-out ACAV slices: dev FP rate (not trained on)
    print(f"train pos {len(pos)}  tts-neg {len(tneg)}  acav {len(acav)}  dev pos {int(ydev.sum())} "
          f"dev tts-neg {int((ydev == 0).sum())} dev acav {len(dev_acav)}", flush=True)

    model = make_model(args.hidden)
    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-2)
    steps = args.steps
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=2e-3, total_steps=steps, pct_start=0.1)
    bce = torch.nn.BCELoss(reduction="none")
    hard_idx = None
    best = None
    t = time.time()
    Bp, Bt, Ba = 256, 256, args.acav_batch
    for step in range(steps):
        frac = step / steps
        ip = np.random.randint(len(pos), size=Bp)
        it = np.random.randint(len(tneg), size=Bt)
        if hard_idx is not None and frac > 0.3:
            nh = Ba // 2
            ia = np.concatenate([np.random.randint(len(acav), size=Ba - nh), hard_idx[np.random.randint(len(hard_idx), size=nh)]])
        else:
            ia = np.random.randint(len(acav), size=Ba)
        xb = np.concatenate([pos[ip], tneg[it], acav[np.sort(ia)]]).astype(np.float32)
        yb = np.concatenate([np.ones(Bp), np.zeros(Bt + Ba)]).astype(np.float32)
        # negative weight ramps up (oWW-style) so the model first learns the phrase, then pushes down false accepts
        wneg = 1.0 + (args.max_neg_w - 1.0) * min(1.0, frac / 0.5)
        w = np.concatenate([np.ones(Bp) * args.pos_w, np.full(Bt, wneg * 1.5), np.full(Ba, wneg)]).astype(np.float32)
        xb_t = torch.from_numpy(xb)
        # light feature-space noise augmentation
        xb_t = xb_t + 0.02 * torch.randn_like(xb_t) * xb_t.std()
        xb_t = xb_t * (torch.rand_like(xb_t) > args.in_drop).float()  # input dropout (regularization)
        p = model(xb_t)[:, 0]
        loss = (bce(p.clamp(1e-7, 1 - 1e-7), torch.from_numpy(yb)) * torch.from_numpy(w)).sum() / w.sum()
        opt.zero_grad()
        loss.backward()
        opt.step()
        sched.step()
        if (step + 1) % 1000 == 0:
            model.eval()
            # hard negative mining over the full ACAV training slice
            sa = predict_np(model, acav)
            hard_idx = np.argsort(-sa)[:20000]
            sd = predict_np(model, dev_acav)
            spd = predict_np(model, Xdev)
            rec = (spd[ydev == 1] > 0.5).mean()
            fa_dev = (sd > 0.5).mean() * 1e4  # dev ACAV windows > 0.5, per 10k windows
            fa_tts = (spd[ydev == 0] > 0.5).mean()
            score = rec - args.fa_pen * fa_dev - 2 * fa_tts
            msg = (f"step {step + 1} loss {loss.item():.4f} wneg {wneg:.1f} | dev recall@.5 {rec:.3f} "
                   f"acav FP/10k@.5 {fa_dev:.2f} tts-neg FP@.5 {fa_tts:.4f} | acav>0.5 train {(sa > 0.5).sum()} "
                   f"| {time.time() - t:.0f}s")
            print(msg, flush=True)
            if frac > 0.5 and (best is None or score > best[0]):
                best = (score, step + 1, {k: v.clone() for k, v in model.state_dict().items()})
            model.train()
    print("best step", best[1], "score", best[0])
    model.load_state_dict(best[2])
    model.eval()
    torch.save(model.state_dict(), os.path.join(DATA, "model.pt"))
    export_onnx(model, os.path.join(WORK, "hej_wiktor.onnx"))


def export_onnx(model, path):
    import torch
    import onnx
    import onnxruntime as ort
    dummy = torch.zeros(1, 16, 96)
    torch.onnx.export(model, dummy, path, input_names=["input"], output_names=["output"], opset_version=13,
                      dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}}, dynamo=False)
    m = onnx.load(path)
    onnx.checker.check_model(m)
    # inline any external data and re-save as one file
    onnx.save_model(m, path, save_as_external_data=False)
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    x = np.random.randn(3, 16, 96).astype(np.float32)
    o = s.run(None, {"input": x})[0]
    with torch.no_grad():
        ref = model(torch.from_numpy(x)).numpy()
    print("onnx export ok:", path, os.path.getsize(path), "bytes; opset",
          [o_.version for o_ in m.opset_import], "out", o.shape, "max|diff|", float(np.abs(o - ref).max()))


# ----------------------------------------------------------------------------- evaluation
def stream_scores(oww, clip):
    """Per-frame scores from openwakeword.Model in streaming mode (1280-sample chunks), 1 s zero padding each side."""
    oww.reset()
    x = np.concatenate([np.zeros(SR, np.int16), clip, np.zeros(SR, np.int16)])
    s = []
    for i in range(0, len(x) - 1279, 1280):
        p = oww.predict(x[i:i + 1280])
        s.append(list(p.values())[0])
    return np.array(s)


def consec_max(scores, thr, n):
    """True if there are >= n consecutive frames with score >= thr."""
    a = scores >= thr
    if n <= 1:
        return a.any()
    run = 0
    for v in a:
        run = run + 1 if v else 0
        if run >= n:
            return True
    return False


def count_events(scores, thr, n=1, refractory=25):
    """Count detections: a detection fires when n consecutive frames >= thr; then refractory frames (~2 s) ignored."""
    a = scores >= thr
    ev, run, i = 0, 0, 0
    L = len(a)
    while i < L:
        run = run + 1 if a[i] else 0
        if run >= n:
            ev += 1
            i += refractory
            run = 0
        i += 1
    return ev


_EV = {}


def _init_eval(path):
    import openwakeword
    _EV["oww"] = openwakeword.Model(wakeword_models=[path], inference_framework="onnx")
    _EV["clips"] = np.load(os.path.join(DATA, "clips.npy"), mmap_mode="r")


def _eval_clip(i):
    return stream_scores(_EV["oww"], np.asarray(_EV["clips"][i]))


def stage_eval(args):
    import openwakeword
    import onnxruntime as ort
    path = os.path.join(WORK, "hej_wiktor.onnx")
    m = np.load(os.path.join(DATA, "clips_meta.npz"))
    clips = np.load(os.path.join(DATA, "clips.npy"), mmap_mode="r")
    test = np.where((m["split"] == "test") & m["ok"])[0]
    oww = openwakeword.Model(wakeword_models=[path], inference_framework="onnx")
    print("openwakeword.Model loaded:", list(oww.models.keys()), "input frames", oww.model_inputs)
    thrs = [0.3, 0.5, 0.7]
    Ns = [1, 2, 3]
    res = {"thresholds": thrs, "N": Ns}
    # held-out clips (streaming through the full oWW pipeline)
    rs = np.random.RandomState(0)
    groups = {}
    t = time.time()
    sel = test
    if args.eval_max and len(sel) > args.eval_max:
        sel = np.sort(rs.choice(sel, args.eval_max, replace=False))
    with Pool(args.procs, initializer=_init_eval, initargs=(path,)) as pool:
        all_scores = pool.map(_eval_clip, sel.tolist(), chunksize=16)
    print(f"streamed {len(sel)} held-out clips in {time.time() - t:.0f}s", flush=True)
    np.savez(os.path.join(DATA, "eval_scores.npz"), idx=sel, scores=np.array(all_scores, dtype=object))
    for i, s in zip(sel, all_scores):
        lab = int(m["label"][i])
        g = "positive" if lab == 1 else ("adversarial" if m["kind"][i] == "adv" else "sentence")
        sub = g + ("/pl_voice" if m["voice"][i] != EN_VOICE else ("/libritts_pl_phon" if m["phon"][i] == "pl" else "/libritts_en"))
        for key in (g, sub):
            groups.setdefault(key, []).append((s, str(m["text"][i])))
    for key, lst in sorted(groups.items()):
        row = {"n": len(lst)}
        for thr in thrs:
            for n in Ns:
                row[f"{thr}/N{n}"] = float(np.mean([consec_max(s, thr, n) for s, _ in lst]))
        res[key] = row
    # per-text adversarial breakdown at 0.5
    adv = {}
    for s, txt in groups.get("adversarial", []):
        adv.setdefault(txt, []).append(consec_max(s, 0.5, 1))
    res["adversarial_by_text@0.5"] = {k: [float(np.mean(v)), len(v)] for k, v in sorted(adv.items(), key=lambda kv: -np.mean(kv[1]))}
    # validation set: ~11 h of continuous features, sliding 16-frame windows (stride 1 = 80 ms)
    v = np.load(os.path.join(WORK, "dl", "val", "validation_set_features.npy"))
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    W = np.lib.stride_tricks.sliding_window_view(v, (16, 96))[:, 0]
    sc = np.concatenate([sess.run(None, {"input": np.ascontiguousarray(W[i:i + 20000])})[0][:, 0] for i in range(0, len(W), 20000)])
    hours = len(v) * 0.08 / 3600
    val = {"hours": hours}
    for thr in thrs:
        for n in Ns:
            val[f"{thr}/N{n}"] = count_events(sc, thr, n) / hours
        val[f"{thr}/frames_above_per_h"] = float((sc >= thr).sum() / hours)
    res["validation_FA_per_hour"] = val
    res["validation_max_score"] = float(sc.max())
    json.dump(res, open(os.path.join(WORK, "metrics.json"), "w"), indent=1)
    print(json.dumps(res, indent=1))


def stage_wavs(args):
    import wave
    m = np.load(os.path.join(DATA, "clips_meta.npz"))
    clips = np.load(os.path.join(DATA, "clips.npy"), mmap_mode="r")
    _init_worker(True)
    rs = np.random.RandomState(5)
    for name, text, kind in [("test_positive_hej_wiktor.wav", "hej wiktor", "pos"),
                             ("test_negative_hej_wiktoria.wav", "hej wiktoria", "adv"),
                             ("test_negative_sentence.wav", "Jutro jadę pociągiem do Krakowa i wrócę w piątek.", "adv")]:
        x = synth(dict(voice=HELDOUT_PL_VOICE, text=text, ls=1.0, ns=0.5, nw=0.6))
        x = x / np.max(np.abs(x)) * 0.5
        bg = colored_noise(len(x) + 2 * SR, "pink", rs) * rms(x) * 10 ** (-25 / 20)
        y = bg.copy()
        y[SR:SR + len(x)] += x
        y = np.clip(y * 32767, -32768, 32767).astype(np.int16)
        with wave.open(os.path.join(WORK, name), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(y.tobytes())
        import openwakeword
        oww = openwakeword.Model(wakeword_models=[os.path.join(WORK, "hej_wiktor.onnx")], inference_framework="onnx")
        s = stream_scores(oww, y)
        print(name, f"{len(y) / SR:.2f}s", "max score", round(float(s.max()), 4))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True)
    ap.add_argument("--procs", type=int, default=4)
    ap.add_argument("--sizes", default="")
    ap.add_argument("--steps", type=int, default=24000)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--max_neg_w", type=float, default=100.0)
    ap.add_argument("--pos_w", type=float, default=1.0)
    ap.add_argument("--eval_max", type=int, default=0)
    ap.add_argument("--acav_batch", type=int, default=2048)
    ap.add_argument("--in_drop", type=float, default=0.1)
    ap.add_argument("--fa_pen", type=float, default=0.1)
    args_global = ap.parse_args()
    stages = ["bank", "clips", "feats", "acav", "train", "eval", "wavs"] if args_global.stage == "all" else args_global.stage.split(",")
    for st in stages:
        print("=== stage", st, flush=True)
        globals()["stage_" + st](args_global)
