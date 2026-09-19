package pl.victor.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabela prawdy wyboru mikrofonu - i zapis MOJEJ WŁASNEJ REGRESJI.
 *
 * ## Co się stało
 * Uznałem, że wyrażenie `overSco || !bleStreamLive` pomija wybór człowieka, bo
 * nie ma w nim `wantsGlassesMic`. To było błędne odczytanie kodu: ustawienie
 * decyduje PIĘTRO WYŻEJ o tym, czy w ogóle podjąć próbę zestawienia profilu
 * rozmowy, a `overSco` to już WYNIK tej próby.
 *
 * Dopisałem `wantsGlassesMic ||`. Ustawienie stoi domyślnie na `true`, więc
 * wynik stał się prawdą w praktycznie każdej turze, a SpeechToText zaczął brać
 * profil rozmowy DRUGI RAZ, przy każdym rozpoznaniu - dokładnie to, przed czym
 * ostrzega komentarz nad tamtym wywołaniem.
 *
 * Zgłoszone natychmiast: "teraz prawie nic nie działa, wszystko działa wolno".
 *
 * Morał, który ten plik ma utrwalić: pierwsza wersja tego testu sprawdzała, że
 * `wantsGlassesMic` ZMIENIA wynik - i przechodziła. Test może z równym
 * powodzeniem zabetonować usterkę, jeśli pilnuje cudzej decyzji zamiast
 * skutku. Dlatego teraz stoi tu koszt, a nie kształt wyrażenia.
 */
class MicChoiceTest {

    @Test
    fun `nieudane zestawienie SCO NIE powoduje drugiej proby`() {
        // TO JEST WIERSZ, KTÓRY ZEPSUŁEM. Zwracał `true`, przez co rozpoznawanie
        // brało profil rozmowy po raz drugi - po próbie, która właśnie zawiodła
        // z tego samego powodu. Koszt: do czterech sekund przed każdym
        // rozpoznaniem i zawieszone A2DP. Zysk: żaden.
        assertFalse(
            "po nieudanym zestawieniu nie wolno próbować drugi raz w tej turze",
            MicChoice.useBluetoothMic(overSco = false, bleStreamLive = true)
        )
    }

    @Test
    fun `stojace lacze wykorzystujemy`() {
        assertTrue(
            "skoro SCO i tak stoi, rozpoznawanie ma z niego korzystać",
            MicChoice.useBluetoothMic(overSco = true, bleStreamLive = true)
        )
    }

    @Test
    fun `bez strumienia BLE zostaje tylko profil rozmowy`() {
        assertTrue(
            "gdy nie ma strumienia BLE, nie ma innej drogi do dźwięku",
            MicChoice.useBluetoothMic(overSco = false, bleStreamLive = false)
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
    fun `decyzja zalezy TYLKO od stanu sprzetu`() {
        // Strażnik przeciw powrotowi MOJEJ regresji. Ta funkcja nie przyjmuje
        // już wyboru człowieka i nie ma go przyjąć: prośba decyduje piętro
        // wyżej, o tym, czy podjąć próbę, a tutaj liczy się wyłącznie jej
        // wynik. Sygnatura z trzecim argumentem to znak, że ktoś idzie tą samą
        // drogą co ja - i ten test przestanie się wtedy kompilować.
        val wynik = MicChoice::class.java.methods.first { it.name == "useBluetoothMic" }
        assertTrue(
            "useBluetoothMic ma brać dwa argumenty o stanie sprzętu, nie trzy",
            wynik.parameterCount == 2
        )
    }
}
