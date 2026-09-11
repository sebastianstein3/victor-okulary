package pl.victor.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Wspólne krzywe i czasy ruchu.
 *
 * ## Skąd te liczby
 * Ze skilla „animate" (`delphi-ai/animate-skill`), opartego na kursie Emila
 * Kowalskiego „Animations on the Web". Sam skill jest napisany pod React i
 * CSS - kodu nie da się z niego przenieść, ale reguły owszem, bo dotyczą
 * percepcji, a nie frameworka:
 *
 * - element WCHODZĄCY dostaje `ease-out` i 200-300 ms,
 * - element WYCHODZĄCY dostaje `ease-in` i mniej więcej trzy czwarte tego
 *   czasu - bo na wyjście nikt nie patrzy, a czekanie na nie jest czystym
 *   opóźnieniem,
 * - ruszamy przezroczystością i przekształceniem, nie rozmiarem.
 *
 * ## Dlaczego w osobnym pliku
 * Bo skill nazywa „jedno opóźnienie skopiowane do każdego przejścia"
 * antywzorcem, a rozsypanie tych samych liczb po plikach kończy się właśnie
 * tym: przy kolejnej zmianie ktoś poprawia jedną kopię z trzech. Tu jest
 * jedno miejsce, w którym się je zmienia.
 */
object Motion {

    /** `--ease-out-cubic` z tabeli skilla: cubic-bezier(.33, 1, .68, 1). */
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

    /** `--ease-in-cubic` z tabeli skilla: cubic-bezier(.32, 0, .67, 0). */
    val EaseInCubic: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

    /** Wejście - środek zalecanego przedziału 200-300 ms. */
    const val ENTER_MS = 220

    /** Wyjście - około trzech czwartych wejścia. */
    const val EXIT_MS = 160
}
