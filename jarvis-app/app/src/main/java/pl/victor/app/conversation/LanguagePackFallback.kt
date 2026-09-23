package pl.victor.app.conversation

/**
 * Które błędy rozpoznawania znaczą "ten silnik nie ma tego języka".
 *
 * Wydzielone z [SpeechToText], bo od tej jednej decyzji zależy, czy nasłuch
 * przechodzi na rozpoznawanie przez sieć - a da się ją sprawdzić bez Androida.
 *
 * Wartości to stałe `SpeechRecognizer` z Androida 13, wpisane liczbami, żeby ten
 * plik nie zależał od frameworka: ERROR_LANGUAGE_NOT_SUPPORTED = 12,
 * ERROR_LANGUAGE_UNAVAILABLE = 13. Obie znaczą to samo z naszego punktu widzenia:
 * silnik na urządzeniu tego języka nie ma w SWOIM magazynie pakietów, choćby
 * leżał w innym.
 */
object LanguagePackFallback {

    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13

    fun isLanguageError(code: Int?): Boolean =
        code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE
}
