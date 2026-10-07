package pl.victor.app.features

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.victor.app.proactive.GuideLogic
import pl.victor.app.proactive.GuideNearby
import pl.victor.app.proactive.LocationContext

/**
 * Tryb przewodnika - w trakcie spaceru mówi o mijanych miejscach.
 *
 * Działa w tle, dopóki się go nie wyłączy (albo przez [MAKS_MS]): co
 * [INTERWAŁ_MS] sprawdza położenie, a gdy człowiek przeszedł kawałek
 * ([GuideLogic.PRZESUNIĘCIE_M]), szuka w Wikipedii, co jest obok, i mówi o
 * najbliższym miejscu, o którym jeszcze nie mówił.
 *
 * Nigdy nie wchodzi w słowo: mówi tylko wtedy, gdy [wolno] - czyli gdy nie
 * trwa tura, tłumaczenie ani inna wypowiedź. Pytanie zadane w trakcie
 * spaceru ("a kiedy to zbudowano?") idzie zwykłą drogą, a położenie jest
 * wtedy w kontekście modelu.
 *
 * Osobna klasa, a nie kolejne sto linii orkiestratora: ten ma już ponad
 * sześć tysięcy linii i każda poprawka w nim dotyka czegoś obok.
 */
class GuideMode(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mów: suspend (String) -> Unit,
    private val wolno: () -> Boolean,
    private val dziennik: (String, Map<String, Any?>) -> Unit,
    private val nearby: GuideNearby = GuideNearby()
) {
    private val _aktywny = MutableStateFlow(false)
    val aktywny: StateFlow<Boolean> = _aktywny.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (_aktywny.value) return
        _aktywny.value = true
        dziennik("przewodnik: start", emptyMap())
        job = scope.launch { pętla() }
    }

    fun stop(powód: String) {
        if (!_aktywny.value) return
        _aktywny.value = false
        job?.cancel()
        job = null
        dziennik("przewodnik: koniec", mapOf("powód" to powód))
    }

    private suspend fun pętla() {
        val start = System.currentTimeMillis()
        val powiedziane = mutableSetOf<String>()
        var ostatnieSzukanie: Pair<Double, Double>? = null
        var miejsca: List<GuideLogic.Miejsce> = emptyList()
        var pierwszy = true
        try {
            while (_aktywny.value && System.currentTimeMillis() - start < MAKS_MS) {
                val tu = runCatching { LocationContext.tutaj(context, naŚwieżąMs = GPS_MS) }.getOrNull()
                if (tu == null) {
                    if (pierwszy) {
                        mów("Przewodnik włączony, ale nie znam teraz położenia. " +
                            "Sprawdź, czy lokalizacja w telefonie jest włączona.")
                        pierwszy = false
                    }
                    delay(INTERWAŁ_MS)
                    continue
                }
                val przesunięcie = ostatnieSzukanie?.let {
                    GuideLogic.odległośćM(it.first, it.second, tu.lat, tu.lon)
                }
                if (przesunięcie == null || przesunięcie >= GuideLogic.PRZESUNIĘCIE_M) {
                    miejsca = nearby.wPobliżu(tu.lat, tu.lon)
                    ostatnieSzukanie = tu.lat to tu.lon
                    dziennik(
                        "przewodnik: szukam w okolicy",
                        mapOf("znalezionych" to miejsca.size, "przesunięcieM" to przesunięcie?.toInt())
                    )
                }
                val następne = GuideLogic.następne(miejsca, powiedziane)
                if (pierwszy) {
                    pierwszy = false
                    // Samo "włączam" powiedziała już komenda; tu tylko wtedy,
                    // gdy pierwsze szukanie nic nie dało - inaczej cisza
                    // wyglądałaby jak niedziałający tryb.
                    if (następne == null && wolno()) {
                        mów("Na razie nie widzę w pobliżu nic ciekawego - powiem, gdy coś się pojawi.")
                    }
                }
                // Jest o czym mówić, ale coś akurat gra - spróbujemy za
                // chwilę, a nie za pełny odstęp, bo miejsce zostanie z tyłu.
                val czekaNaCiszę = następne != null && !wolno()
                if (następne != null && wolno()) {
                    val opis = nearby.streszczenie(następne.tytuł)
                    // Ponowne sprawdzenie: streszczenie szło przez sieć, a w
                    // tym czasie mogło paść pytanie.
                    if (_aktywny.value && wolno()) {
                        powiedziane += następne.tytuł
                        dziennik("przewodnik: mówię", mapOf("miejsce" to następne.tytuł, "m" to następne.odległośćM))
                        mów(GuideLogic.zapowiedź(następne, opis))
                    }
                }
                delay(if (czekaNaCiszę) PONÓW_MS else INTERWAŁ_MS)
            }
            if (_aktywny.value) {
                mów("Kończę tryb przewodnika.")
                stop("limit czasu")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Przewodnik się wysypał", e)
            _aktywny.value = false
            dziennik("przewodnik: błąd", mapOf("błąd" to e.message))
        }
    }

    companion object {
        private const val TAG = "GuideMode"

        /** Co ile sprawdzać położenie - wolny spacer to circa 70 m na minutę. */
        const val INTERWAŁ_MS = 40_000L

        /** Ponowna próba, gdy było o czym mówić, ale coś grało. */
        private const val PONÓW_MS = 5_000L

        /** Ile czekać na GPS w jednym sprawdzeniu. */
        private const val GPS_MS = 6_000L

        /** Sam się wyłącza po trzech godzinach - zapomniany tryb zjada baterię. */
        const val MAKS_MS = 3 * 60 * 60_000L
    }
}
