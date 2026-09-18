package pl.victor.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabela prawdy wyboru mikrofonu - w tym WIERSZ, KTÓRY BYŁ USTERKĄ.
 *
 * Zgłoszone: "Wybrałem Pytania mikrofonem okularów, ale wciąż podczas mówienia
 * włącza się mikrofon w telefonie". Wiersz `okulary + strumień BLE żyje + SCO
 * nie wstało` oddawał `false`, czyli zakaz sięgania po mikrofon okularów - bo
 * wyrażenie nie patrzyło na wybór człowieka w ogóle.
 */
class MicChoiceTest {

    @Test
    fun `prosba o mikrofon okularow przezywa nieudane zestawienie SCO`() {
        // DOKŁADNIE ten wiersz był zgłoszoną usterką: człowiek wybrał mikrofon
        // okularów, strumień BLE żyje (tura z okularów), profil rozmowy nie
        // wstał - i dotąd wychodziło z tego "słuchaj telefonem".
        assertTrue(
            "prośba o mikrofon okularów nie może przepadać przez nieudane SCO",
            MicChoice.useBluetoothMic(
                wantsGlassesMic = true,
                overSco = false,
                bleStreamLive = true
            )
        )
    }

    @Test
    fun `bez prosby i przy zywym strumieniu BLE nie zajmujemy profilu rozmowy`() {
        // Druga strona tej samej monety: kto mikrofonu okularów NIE chce, ten ma
        // nie płacić kilku sekund negocjacji SCO ani nie tracić A2DP.
        assertFalse(
            "bez prośby o mikrofon okularów profil rozmowy jest zbędny",
            MicChoice.useBluetoothMic(
                wantsGlassesMic = false,
                overSco = false,
                bleStreamLive = true
            )
        )
    }

    @Test
    fun `stojace lacze wykorzystujemy nawet bez prosby`() {
        assertTrue(
            "skoro SCO i tak stoi, rozpoznawanie ma z niego korzystać",
            MicChoice.useBluetoothMic(
                wantsGlassesMic = false,
                overSco = true,
                bleStreamLive = true
            )
        )
    }

    @Test
    fun `bez strumienia BLE zostaje tylko profil rozmowy`() {
        assertTrue(
            "gdy nie ma strumienia BLE, nie ma innej drogi do dźwięku",
            MicChoice.useBluetoothMic(
                wantsGlassesMic = false,
                overSco = false,
                bleStreamLive = false
            )
        )
    }

    @Test
    fun `tekst z telefonu jest niepewny tylko przy niespelnionej prosbie`() {
        assertTrue(
            "prośba o mikrofon okularów + brak SCO = telefon zbierał zastępczo",
            MicChoice.phoneMicNotTrusted(
                fromGlasses = true,
                wantsGlassesMic = true,
                overSco = false
            )
        )
        assertFalse(
            "gdy SCO stoi, telefon niczego nie zbierał zastępczo",
            MicChoice.phoneMicNotTrusted(
                fromGlasses = true,
                wantsGlassesMic = true,
                overSco = true
            )
        )
        assertFalse(
            "kto nie prosił o mikrofon okularów, dostał dokładnie to, co wybrał",
            MicChoice.phoneMicNotTrusted(
                fromGlasses = true,
                wantsGlassesMic = false,
                overSco = false
            )
        )
        assertFalse(
            "pytanie zadane do telefonu ma być zebrane telefonem",
            MicChoice.phoneMicNotTrusted(
                fromGlasses = false,
                wantsGlassesMic = true,
                overSco = false
            )
        )
    }

    @Test
    fun `wybor czlowieka wystepuje w decyzji`() {
        // Strażnik przeciw powrotowi usterki w innej postaci: gdyby ktoś
        // uprościł wyrażenie z powrotem do `overSco || !bleStreamLive`, wszystkie
        // pozostałe wiersze tabeli dalej by przechodziły poza jednym - ale ten
        // jeden łatwo "poprawić". Ten test mówi wprost, co jest tu istotne.
        val bezProsby = MicChoice.useBluetoothMic(false, overSco = false, bleStreamLive = true)
        val zProsba = MicChoice.useBluetoothMic(true, overSco = false, bleStreamLive = true)
        assertTrue(
            "sam wybór człowieka musi zmieniać wynik przy identycznym stanie sprzętu",
            bezProsby != zProsba
        )
    }
}
