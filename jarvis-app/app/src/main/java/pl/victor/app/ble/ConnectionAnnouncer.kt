package pl.victor.app.ble

/**
 * Decyduje, KIEDY powiedzieć człowiekowi, że okulary odpadły albo wróciły.
 *
 * ## Czemu to w ogóle musi istnieć
 * [VictorManager] radzi sobie z rozłączeniem sam: wznawia połączenie w
 * nieskończoność, gęsto przez pierwszą minutę, potem coraz rzadziej (patrz
 * [ReconnectBackoff]). Czego NIE robił nikt, to powiedzenie o tym człowiekowi.
 *
 * Zgłoszone trzy razy, za każdym razem tak samo: "okulary przestały reagować na
 * hej lens i na kliknięcie (...) okazało się, że w ustawieniach Bluetooth się
 * rozłączyły". Czyli objaw brzmi "aplikacja jest zepsuta", a przyczyną jest
 * zerwane łącze, o którym da się dowiedzieć wyłącznie wchodząc w ustawienia
 * systemowe. Człowiek w okularach na głowie naciska martwy przycisk i nie ma
 * żadnego powodu, żeby podejrzewać Bluetooth.
 *
 * ## Czemu to jest osobna klasa, a nie dwa `if`-y w orkiestratorze
 * Bo trzy rzeczy tu łatwo zepsuć i żadnej nie da się sprawdzić bez okularów:
 *
 *  1. Ogłoszenie na starcie. Stan początkowy to DISCONNECTED, więc naiwny
 *     warunek wita użytkownika zdaniem "okulary się rozłączyły" przy każdym
 *     uruchomieniu aplikacji, także wtedy, gdy nigdy nie były połączone.
 *  2. Powrót bez utraty. "Okulary znów połączone" po PIERWSZYM w życiu
 *     połączeniu jest bez sensu - nie było czego odzyskiwać.
 *  3. Powtarzanie. Łącze potrafi migotać, a stan przychodzi strumieniem; bez
 *     pamięci "już to powiedziałem" asystent gada w kółko.
 *
 * Karencja przed ogłoszeniem NIE siedzi tutaj - to zadanie wołającego, bo
 * wymaga zegara. Tutaj jest sama pamięć o tym, co już padło.
 */
class ConnectionAnnouncer {

    private var byłyKiedykolwiekGotowe = false
    private var utrataOgłoszona = false

    /**
     * Czy wolno ogłosić utratę łącza.
     *
     * Wołane DOPIERO po karencji i tylko wtedy, gdy okulary wciąż nie wróciły.
     *
     * @return `true` najwyżej raz na jedno rozłączenie
     */
    fun czyOgłosićUtratę(): Boolean {
        // Bez pierwszego warunku aplikacja wita użytkownika informacją o
        // rozłączeniu urządzenia, którego nigdy nie miał połączonego.
        if (!byłyKiedykolwiekGotowe || utrataOgłoszona) return false
        utrataOgłoszona = true
        return true
    }

    /**
     * Czy wolno ogłosić powrót.
     *
     * @return `true` tylko wtedy, gdy wcześniej padło ogłoszenie o utracie -
     *   inaczej człowiek usłyszałby "znów połączone" o połączeniu, o którego
     *   zerwaniu nigdy się nie dowiedział
     */
    fun czyOgłosićPowrót(): Boolean {
        val ogłaszamy = utrataOgłoszona
        byłyKiedykolwiekGotowe = true
        utrataOgłoszona = false
        return ogłaszamy
    }

    /** Świadome rozłączenie przez człowieka - nie ma o czym mówić. */
    fun zapomnij() {
        utrataOgłoszona = false
    }
}
