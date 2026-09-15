package pl.victor.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta marki V.I.C.T.O.R. - jedno miejsce, z którego bierze kolor cała aplikacja.
 *
 * ## Skąd te wartości
 * Cyjan i czerń są WPROST z pliku logo (`design/logo_victor_black.svg`) - to
 * jedyne dwa kolory, jakie w nim występują. Reszta to szarości wyprowadzone z
 * tej czerni, a nie dobrane osobno: neutralna szarość obok ciepłej czerni
 * #2B2A29 wygląda na brudną, więc wszystkie tła mają ten sam lekki skręt w
 * ciepło.
 *
 * ## Czemu paleta jest STAŁA, a nie z tapety
 * Bo aplikacja na Androidzie 12+ brała kolory z tapety telefonu (dynamic
 * color). Cyjan z logo nie miał wtedy jak się przebić, a dwie osoby testujące
 * ten sam build widziały dwie różne aplikacje - co przy zbieraniu zgłoszeń
 * znaczy, że "ten niebieski panel" nie identyfikuje niczego.
 *
 * Wysoki kontrast dla osób słabowidzących zostaje nietknięty i nadal ma
 * pierwszeństwo nad wszystkim - patrz [VictorTheme].
 */
internal object VictorColors {

    /** Cyjan marki - jedyny kolor w logo, więc jedyny akcent w aplikacji. */
    val Cyan = Color(0xFF00A0E3)

    /**
     * Cyjan przyciemniony - do TEKSTU I IKON NA JASNYM TLE.
     *
     * Markowy #00A0E3 na bieli daje kontrast circa 2,7:1 przy wymaganych 4,5:1,
     * więc jako kolor tekstu byłby zwyczajnie nieczytelny. Ten sam odcień,
     * ściemniony do granicy czytelności: hue zostaje, marka zostaje rozpoznawalna,
     * a tekst da się przeczytać. Wypełnienia (paski, wskaźniki, obramowania)
     * dalej idą pełnym [Cyan], bo tam kontrast liczy się inaczej.
     */
    val CyanDeep = Color(0xFF00719F)

    /** Cyjan rozjaśniony - do drobnych elementów na bardzo ciemnym tle. */
    val CyanBright = Color(0xFF4FC8F5)

    /** Czerń marki. */
    val Ink = Color(0xFF2B2A29)

    // === Ciemny motyw: "przyrząd pomiarowy" ===
    //
    // Tło niemal czarne, powierzchnie ledwie jaśniejsze. Hierarchię niosą
    // OBRAMOWANIA, nie wypełnienia - stąd wyraźny `outline` przy oszczędnych
    // różnicach tła.

    val DarkBackground = Color(0xFF0D0F0F)
    val DarkSurface = Color(0xFF151817)
    val DarkSurfaceHigh = Color(0xFF1C201F)
    val DarkOutline = Color(0xFF39403E)
    val DarkOutlineFaint = Color(0xFF262B2A)
    val DarkOnBackground = Color(0xFFE7EBEA)
    val DarkOnSurfaceMuted = Color(0xFF98A2A0)

    // === Jasny motyw ===

    val LightBackground = Color(0xFFF7F7F6)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEFF0EF)
    val LightOutline = Color(0xFFBFC4C3)
    val LightOutlineFaint = Color(0xFFDFE2E1)
    val LightOnSurfaceMuted = Color(0xFF5B615F)

    /** Czerń, na której siada cyjan - do wypełnionych przycisków. */
    val OnCyan = Color(0xFF04171F)

    val DangerDark = Color(0xFFFF6B5E)
    val DangerLight = Color(0xFFB3261E)
}
