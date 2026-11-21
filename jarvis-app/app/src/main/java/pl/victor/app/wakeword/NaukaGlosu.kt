package pl.victor.app.wakeword

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import pl.victor.app.VictorApplication
import pl.victor.app.diagnostics.DiagFormat

/**
 * Nauka „Hej Wiktor” na głosie właściciela - nagrania, analiza, zapis, korekty.
 * Funkcja darmowa: wszystko dzieje się na telefonie, nic nie wychodzi do sieci.
 *
 * Logika bez Androida jest w [OcenaNagrania], [NaukaGlosuDane],
 * [WeryfikatorGlosu] i [ProfilGlosu]; tutaj tylko mikrofon, głośnik, pliki i
 * dziennik. Wszystkie metody poza [rozpocznij]/[zakoncz] blokują - wołać poza
 * wątkiem głównym.
 *
 * ## Mikrofon
 * Nasłuch frazy trzyma mikrofon cały czas, a mikrofon jest wyłączny - na czas
 * nauki [rozpocznij] oddaje go jak przy turze ([VictorApplication.pauseWakeForTurn]),
 * a [zakoncz] przywraca. Nagrania idą przez [OpenWakeWord.nagraj], czyli tym
 * samym mikrofonem i źródłem co nasłuch.
 *
 * ## Dźwięk
 * Nagrań nie zapisujemy do plików - są w pamięci ekranu nauki (do odsłuchu) i
 * znikają z nim. Na dysk trafiają tylko okna cech ([ProfilGlosu]) i weryfikator.
 */
class NaukaGlosu(context: Context) {

    private val tag = "NaukaGlosu"
    private val app = context.applicationContext as VictorApplication
    private val oww get() = app.phoneWake
    private val magazyn get() = oww.magazynGlosu

    private fun log(wiadomosc: String, pola: Map<String, Any?> = emptyMap()) {
        runCatching { app.diag.event(DiagFormat.Phase.WAKE, wiadomosc, pola) }
    }

    /** Zabiera mikrofon nasłuchowi frazy na czas nauki. */
    fun rozpocznij() {
        app.pauseWakeForTurn(VictorApplication.WAKE_HOLDER_NAUKA_GLOSU)
        log("nauka głosu - start", mapOf("jestProfil" to magazyn.istnieje()))
    }

    /** Oddaje mikrofon nasłuchowi (jeśli ustawienia go chcą). */
    fun zakoncz() {
        app.resumeWakeAfterTurn(VictorApplication.WAKE_HOLDER_NAUKA_GLOSU)
    }

    /** Jedno nagranie z oceną; [blad] - mikrofon zawiódł (wtedy ocena jest bez znaczenia). */
    class Proba(
        val probki: ShortArray,
        val ocena: OcenaNagrania.Ocena,
        val mikrofon: String,
        val blad: String?
    ) {
        val dobra: Boolean get() = blad == null && ocena.dobre
        val komunikat: String get() = blad ?: ocena.werdykt.komunikat
    }

    /**
     * Nagrywa jedną frazę ([NaukaGlosuDane.CZAS_FRAZY_MS]).
     *
     * @param nr numer próby (do dziennika)
     * @param naStart mikrofon ruszył - od tej chwili UI liczy [NaukaGlosuDane.MOW_PO_MS]
     */
    fun nagrajFraze(nr: Int, czyPrzerwac: () -> Boolean, naStart: () -> Unit): Proba {
        val n = oww.nagraj(NaukaGlosuDane.CZAS_FRAZY_MS, czyPrzerwac, naStart)
        val ocena = OcenaNagrania.ocenFraze(n.probki)
        val proba = Proba(n.probki, ocena, n.mikrofon, n.blad)
        log(
            "nauka głosu - nagranie frazy",
            mapOf("nr" to nr, "mikrofon" to n.mikrofon, "błąd" to n.blad) + ocena.doDziennika()
        )
        return proba
    }

    /** Nagrywa tło (zwykła mowa użytkownika), najwyżej [NaukaGlosuDane.CZAS_TLA_MS]. */
    fun nagrajTlo(czyPrzerwac: () -> Boolean, postep: (Long) -> Unit): Proba {
        val n = oww.nagraj(NaukaGlosuDane.CZAS_TLA_MS, czyPrzerwac, postep = postep)
        val ocena = OcenaNagrania.ocenTlo(n.probki)
        log(
            "nauka głosu - nagranie tła",
            mapOf("mikrofon" to n.mikrofon, "błąd" to n.blad) + ocena.doDziennika()
        )
        return Proba(n.probki, ocena, n.mikrofon, n.blad)
    }

    /** Odtwarza nagranie na głośniku multimediów; wraca po zakończeniu. */
    fun odtworz(probki: ShortArray) {
        if (probki.isEmpty()) return
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(OwwPipeline.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(probki.size * 2)
                .build()
        }.getOrElse {
            Log.w(tag, "Odsłuch niedostępny", it)
            return
        }
        try {
            track.write(probki, 0, probki.size)
            track.play()
            Thread.sleep(probki.size * 1000L / OwwPipeline.SAMPLE_RATE + 150)
        } catch (e: Exception) {
            Log.w(tag, "Odsłuch przerwany", e)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Wynik [naucz]: [profil] tylko przy powodzeniu. */
    class WynikNauki(val ok: Boolean, val komunikat: String, val profil: ProfilGlosu?)

    /**
     * Uczy weryfikator z przyjętych nagrań i zapisuje go. Kilka sekund
     * (walidacja krzyżowa to tyle nauk, ile nagrań frazy).
     *
     * @param odrzuconych ile nagrań frazy odrzuciła ocena jakości (do opisu)
     */
    fun naucz(
        frazy: List<ShortArray>,
        odrzuconych: Int,
        tlo: ShortArray,
        mikrofon: String,
        postep: (String) -> Unit
    ): WynikNauki {
        val start = SystemClock.elapsedRealtime()
        val nagrania = frazy.mapIndexed { i, probki ->
            postep("Analizuję nagranie ${i + 1} z ${frazy.size}…")
            val okna = oww.analizuj(NaukaGlosuDane.zWstepem(probki))
                ?: return blad("Model „Hej Wiktor” nie daje się wczytać.", "brak modelu")
            NaukaGlosuDane.NagranieFrazy(okna, OcenaNagrania.ocenFraze(probki))
        }
        postep("Analizuję tło…")
        val oknaTla = oww.analizuj(tlo) ?: return blad("Model „Hej Wiktor” nie daje się wczytać.", "brak modelu")
        val zbior = NaukaGlosuDane.zbuduj(nagrania, oknaTla)
        if (zbior.nagranFrazy < 2 || zbior.negatywow == 0) {
            return blad("Za mało danych z nagrań - nagraj jeszcze raz.", "za mało okien")
        }

        val walidacja = WeryfikatorGlosu.walidacjaKrzyzowa(zbior.przyklady, postep = { k, n ->
            if (k < n) postep("Sprawdzam jakość ${k + 1} z $n…")
        })
        val pola = mapOf(
            "nagrańFrazy" to zbior.nagranFrazy,
            "odrzuconychNagrań" to odrzuconych,
            "pominiętych" to zbior.pominiete.takeIf { it > 0 },
            "oknaPoz" to zbior.pozytywow,
            "oknaNeg" to zbior.negatywow,
            "tłoS" to tlo.size / OwwPipeline.SAMPLE_RATE,
            // Jak model ogólny słyszy ten głos - gdy tu są niskie liczby,
            // weryfikator ma największe znaczenie.
            "maksWynikOgólny" to zbior.maksWyniki.joinToString(",") { "%.2f".format(it) },
            "wyrównaneDoMowy" to zbior.wyrownaneDoMowy.takeIf { it > 0 },
            "trafnośćCV" to walidacja?.let { "%.0f%%".format(it.trafnosc * 100) },
            "frazyPrzyjęteCV" to walidacja?.let { "%.0f%%".format(it.trafnoscPozytywow * 100) },
            "fałszyweCV" to walidacja?.let { "%.1f%%".format(it.falszyweAkceptacje * 100) },
            "mikrofon" to mikrofon
        )
        if (!NaukaGlosuDane.czyWystarczajaca(walidacja)) {
            log("nauka głosu - wynik za słaby, nie zapisano", pola + ("czasMs" to SystemClock.elapsedRealtime() - start))
            return WynikNauki(
                false,
                "Weryfikator wyszedł za słaby" +
                    (walidacja?.let { " (trafność ${"%.0f".format(it.trafnosc * 100)}%)" } ?: "") +
                    " - nic nie zapisałem, „Hej Wiktor” działa jak dotąd. Spróbuj w cichszym " +
                    "miejscu, mów frazę zawsze tak samo, a w nagraniu tła - zwykłe zdania.",
                null
            )
        }

        postep("Uczę weryfikator…")
        val model = WeryfikatorGlosu.naucz(zbior.przyklady)
        val profil = ProfilGlosu(
            przyklady = zbior.przyklady,
            nagranFrazy = zbior.nagranFrazy,
            odrzuconychNagran = odrzuconych,
            tloMs = (tlo.size * 1000L / OwwPipeline.SAMPLE_RATE).toInt(),
            mikrofon = mikrofon,
            utworzonoMs = System.currentTimeMillis(),
            korekt = 0,
            walidacja = walidacja
        )
        return try {
            magazyn.zapisz(profil, model)
            oww.przeladujWeryfikator()
            log("nauka głosu - wyuczono", pola + ("czasMs" to SystemClock.elapsedRealtime() - start))
            WynikNauki(true, opis(profil), profil)
        } catch (e: Exception) {
            Log.e(tag, "Zapis weryfikatora nie powiódł się", e)
            blad("Nie udało się zapisać: ${e.message ?: e::class.java.simpleName}", "błąd zapisu")
        }
    }

    private fun blad(komunikat: String, powod: String): WynikNauki {
        log("nauka głosu - nieudana", mapOf("powód" to powod))
        return WynikNauki(false, komunikat, null)
    }

    /** Zapisany profil albo `null` (plik ma 1-3 MB - nie wołać na wątku głównym). */
    fun profil(): ProfilGlosu? = magazyn.wczytajProfil()

    /** Usuwa nagrany głos (pliki z oknami cech i weryfikator). */
    fun usun(): Boolean {
        val ok = magazyn.usun()
        oww.ostatnieWybudzenie = null
        oww.przeladujWeryfikator()
        log("nauka głosu - usunięto nagrania", mapOf("usunięte" to ok))
        return ok
    }

    /**
     * "To było fałszywe wybudzenie": okna ostatniego wykrycia trafiają do
     * negatywów i weryfikator uczy się od nowa (ułamek sekundy).
     *
     * @return komunikat dla użytkownika
     */
    fun oznaczFalszywe(): String {
        val ostatnie = oww.ostatnieWybudzenie ?: return "Nie ma wybudzenia do oznaczenia."
        val profil = magazyn.wczytajProfil() ?: return "Najpierw naucz swój głos."
        val d = profil.przyklady.firstOrNull()?.cechy?.size ?: 0
        val okna = ostatnie.okna.filter { it.size == d }
        if (okna.isEmpty()) return "To wybudzenie nie ma zapisanych danych do korekty."
        val przed = (oww.aktualnyWeryfikator ?: magazyn.wczytajWeryfikator())?.ocen(okna.last())
        val nowy = profil.zKorekta(okna)
        val model = WeryfikatorGlosu.naucz(nowy.przyklady)
        val po = model.ocen(okna.last())
        return try {
            magazyn.zapisz(nowy, model)
            oww.ostatnieWybudzenie = null
            oww.przeladujWeryfikator()
            log(
                "oznaczono fałszywe wybudzenie",
                mapOf(
                    "wynik" to "%.2f".format(ostatnie.wynik),
                    "decyzja" to ostatnie.decyzja.opis,
                    "weryfikatorPrzed" to przed?.let { "%.2f".format(it) },
                    "weryfikatorPo" to "%.2f".format(po),
                    "negatywów" to nowy.negatywow,
                    "korekt" to nowy.korekt
                )
            )
            "Zapamiętane. Weryfikator dla tego dźwięku: " +
                (przed?.let { "%.2f → ".format(it) } ?: "") + "%.2f".format(po) +
                if (po < DecyzjaWybudzenia.PROG_MOCNEGO_ODRZUCENIA) " - nie powinien już budzić." else "."
        } catch (e: Exception) {
            Log.e(tag, "Zapis korekty nie powiódł się", e)
            "Nie udało się zapisać korekty: ${e.message ?: e::class.java.simpleName}"
        }
    }

    companion object {
        /** Opis nauczonego głosu do Ustawień. */
        fun opis(p: ProfilGlosu): String = buildString {
            append("Nauczony głos: ${p.nagranFrazy} nagrań frazy, ${p.tloMs / 1000} s tła")
            p.walidacja?.let {
                append(
                    ", trafność ${"%.0f".format(it.trafnosc * 100)}% " +
                        "(frazy przyjęte ${"%.0f".format(it.trafnoscPozytywow * 100)}%, " +
                        "tło przyjęte błędnie ${"%.1f".format(it.falszyweAkceptacje * 100)}%)"
                )
            }
            append(". Mikrofon: ${p.mikrofon}.")
            if (p.korekt > 0) append(" Oznaczonych fałszywych wybudzeń: ${p.korekt}.")
        }
    }
}
