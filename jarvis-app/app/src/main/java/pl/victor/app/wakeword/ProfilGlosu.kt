package pl.victor.app.wakeword

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Nauczony głos właściciela: przykłady uczące (okna cech, NIE dźwięk) i opis
 * nauki. Bez Androida.
 *
 * ## Po co trzymać przykłady, a nie tylko weryfikator
 * Żeby "To było fałszywe wybudzenie" mogło dopisać negatyw i nauczyć
 * weryfikator od nowa - bez ponownego nagrywania frazy. Samego dźwięku nie
 * zapisujemy wcale: odsłuch działa tylko w trakcie nauki, z pamięci. Okna cech
 * to i tak dane wyliczone z głosu - dlatego "Usuń moje nagrania" kasuje cały
 * katalog ([MagazynGlosu.usun]).
 */
class ProfilGlosu(
    val przyklady: List<WeryfikatorGlosu.Przyklad>,
    /** Przyjęte nagrania frazy. */
    val nagranFrazy: Int,
    /** Nagrania odrzucone przy nauce (za cicho, przester itd.). */
    val odrzuconychNagran: Int,
    /** Długość nagrania tła w ms. */
    val tloMs: Int,
    /** Którym mikrofonem nagrano - patrz [OpenWakeWord.nagraj]. */
    val mikrofon: String,
    val utworzonoMs: Long,
    /** Ile razy oznaczono fałszywe wybudzenie. */
    val korekt: Int,
    val walidacja: WeryfikatorGlosu.Walidacja?
) {
    val pozytywow: Int get() = przyklady.count { it.pozytywny }
    val negatywow: Int get() = przyklady.count { !it.pozytywny }

    /**
     * Profil z dopisanymi oknami fałszywego wybudzenia jako negatywami.
     *
     * Waga [WAGA_KOREKTY]: jedno wybudzenie to 3 okna wobec kilkuset okien tła -
     * z wagą 1 prawie nic by nie zmieniło, a użytkownik oczekuje, że po
     * kliknięciu TEN dźwięk przestanie budzić. Liczba negatywów ma górny limit
     * [MAX_NEGATYWOW]; ponad nim odpadają najpierw najstarsze okna tła, potem
     * najstarsze korekty.
     */
    fun zKorekta(okna: List<FloatArray>): ProfilGlosu {
        val nowe = przyklady + okna.map {
            WeryfikatorGlosu.Przyklad(it, pozytywny = false, waga = WAGA_KOREKTY, grupa = NaukaGlosuDane.GRUPA_KOREKT)
        }
        return ProfilGlosu(
            przyklady = przytnijNegatywy(nowe, MAX_NEGATYWOW),
            nagranFrazy = nagranFrazy,
            odrzuconychNagran = odrzuconychNagran,
            tloMs = tloMs,
            mikrofon = mikrofon,
            utworzonoMs = utworzonoMs,
            korekt = korekt + 1,
            walidacja = walidacja
        )
    }

    fun doBajtow(): ByteArray {
        val bajty = ByteArrayOutputStream()
        DataOutputStream(bajty).use { out ->
            out.writeInt(MAGIA)
            out.writeInt(WERSJA)
            out.writeInt(nagranFrazy)
            out.writeInt(odrzuconychNagran)
            out.writeInt(tloMs)
            out.writeUTF(mikrofon)
            out.writeLong(utworzonoMs)
            out.writeInt(korekt)
            out.writeBoolean(walidacja != null)
            walidacja?.let {
                out.writeInt(it.rund)
                out.writeFloat(it.trafnoscPozytywow)
                out.writeFloat(it.falszyweAkceptacje)
            }
            val d = przyklady.firstOrNull()?.cechy?.size ?: 0
            out.writeInt(przyklady.size)
            out.writeInt(d)
            for (p in przyklady) {
                out.writeBoolean(p.pozytywny)
                out.writeFloat(p.waga)
                out.writeInt(p.grupa)
                out.writeBoolean(p.glowny)
                p.cechy.forEach { out.writeFloat(it) }
            }
        }
        return bajty.toByteArray()
    }

    companion object {
        private const val MAGIA = 0x56504731 // "VPG1"
        private const val WERSJA = 1

        const val WAGA_KOREKTY = 3f
        const val MAX_NEGATYWOW = 800

        /** Odczyt z [doBajtow]; `null` przy uszkodzonym albo obcym pliku. */
        fun zBajtow(bajty: ByteArray): ProfilGlosu? = runCatching {
            DataInputStream(ByteArrayInputStream(bajty)).use { inp ->
                if (inp.readInt() != MAGIA || inp.readInt() != WERSJA) return null
                val nagranFrazy = inp.readInt()
                val odrzuconych = inp.readInt()
                val tloMs = inp.readInt()
                val mikrofon = inp.readUTF()
                val utworzono = inp.readLong()
                val korekt = inp.readInt()
                val walidacja = if (inp.readBoolean()) {
                    WeryfikatorGlosu.Walidacja(inp.readInt(), inp.readFloat(), inp.readFloat())
                } else {
                    null
                }
                val n = inp.readInt()
                val d = inp.readInt()
                if (n < 0 || n > 100_000 || d < 0 || d > 100_000) return null
                val przyklady = List(n) {
                    val poz = inp.readBoolean()
                    val waga = inp.readFloat()
                    val grupa = inp.readInt()
                    val glowny = inp.readBoolean()
                    WeryfikatorGlosu.Przyklad(FloatArray(d) { inp.readFloat() }, poz, waga, grupa, glowny)
                }
                ProfilGlosu(przyklady, nagranFrazy, odrzuconych, tloMs, mikrofon, utworzono, korekt, walidacja)
            }
        }.getOrNull()

        /** Zostawia najwyżej [max] negatywów - patrz [zKorekta]. Pozytywy zawsze zostają. */
        fun przytnijNegatywy(
            przyklady: List<WeryfikatorGlosu.Przyklad>,
            max: Int
        ): List<WeryfikatorGlosu.Przyklad> {
            var nadmiar = przyklady.count { !it.pozytywny } - max
            if (nadmiar <= 0) return przyklady
            val usun = HashSet<WeryfikatorGlosu.Przyklad>()
            for (korekta in listOf(false, true)) {
                for (p in przyklady) {
                    if (nadmiar == 0) break
                    if (!p.pozytywny && (p.grupa == NaukaGlosuDane.GRUPA_KOREKT) == korekta) {
                        usun += p
                        nadmiar--
                    }
                }
            }
            return przyklady.filter { it !in usun }
        }
    }
}

/**
 * Pliki nauczonego głosu w jednym katalogu (na telefonie `filesDir/glos_wybudzenia`).
 *
 * Weryfikator ([PLIK_WERYFIKATORA], kilkanaście kB) jest osobno od przykładów
 * ([PLIK_PROFILU], 1-3 MB): nasłuch potrzebuje tylko jego i wczytuje go przy
 * każdym starcie. Zapis przez plik tymczasowy i zmianę nazwy - przerwany zapis
 * nie zostawia połówki pliku.
 */
class MagazynGlosu(val katalog: File) {

    private val plikProfilu get() = File(katalog, PLIK_PROFILU)
    private val plikWeryfikatora get() = File(katalog, PLIK_WERYFIKATORA)

    fun istnieje(): Boolean = plikWeryfikatora.isFile

    fun wczytajWeryfikator(): WeryfikatorGlosu? =
        runCatching { plikWeryfikatora.takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?.let { WeryfikatorGlosu.zBajtow(it) }

    fun wczytajProfil(): ProfilGlosu? =
        runCatching { plikProfilu.takeIf { it.isFile }?.readBytes() }.getOrNull()
            ?.let { ProfilGlosu.zBajtow(it) }

    /** Zapisuje oba pliki; profil pierwszy, bo to weryfikator oznacza "głos nauczony". */
    fun zapisz(profil: ProfilGlosu, weryfikator: WeryfikatorGlosu) {
        katalog.mkdirs()
        zapiszAtomowo(plikProfilu, profil.doBajtow())
        zapiszAtomowo(plikWeryfikatora, weryfikator.doBajtow())
    }

    /** Usuwa wszystkie dane głosowe. @return `true`, gdy nic nie zostało. */
    fun usun(): Boolean {
        if (katalog.exists()) katalog.deleteRecursively()
        return !katalog.exists()
    }

    private fun zapiszAtomowo(plik: File, bajty: ByteArray) {
        val tymczasowy = File(plik.parentFile, plik.name + ".tmp")
        tymczasowy.writeBytes(bajty)
        if (!tymczasowy.renameTo(plik)) {
            plik.delete()
            check(tymczasowy.renameTo(plik)) { "nie udało się zapisać ${plik.name}" }
        }
    }

    companion object {
        const val KATALOG = "glos_wybudzenia"
        const val PLIK_PROFILU = "probki.bin"
        const val PLIK_WERYFIKATORA = "weryfikator.bin"
    }
}
