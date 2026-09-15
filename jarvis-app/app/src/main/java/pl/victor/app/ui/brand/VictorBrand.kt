package pl.victor.app.ui.brand

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.victor.app.R

/**
 * Znak V.I.C.T.O.R. - lemniskata czytana zarazem jako nieskończoność i jako
 * oprawki okularów. Wektor odtworzony z oficjalnego pliku
 * (`design/logo_victor_black.svg`) i przebarwiany w locie przez [Icon], więc
 * jedno źródło obsługuje jasne i ciemne tło, wysoki kontrast i każdy motyw.
 *
 * ## Proporcja 2,56:1 - to nie jest kwadrat
 * Znak jest szeroki i niski. `Modifier.size(64.dp)` da więc znak szeroki na 64
 * dp i wysoki na 25, wyśrodkowany w kwadracie - bez zniekształceń, ale z pustką
 * nad i pod. Gdy pustka przeszkadza, lepiej podać samą szerokość
 * (`Modifier.width(...)`) i pozwolić wysokości wyjść z proporcji.
 */
@Composable
fun VictorMark(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_victor_mark),
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

/**
 * Logotyp "V.I.C.T.O.R." - PRAWDZIWE liternictwo z pliku marki.
 *
 * ## Co się tu zmieniło i czemu to nie jest kosmetyka
 * Wcześniej napis był SKŁADANY: sześć liter czcionką systemową, a między nimi
 * kwadraciki rysowane [Box]-em. Wyglądało podobnie, ale było podobieństwem, nie
 * logotypem - kształt liter zmieniał się razem z czcionką telefonu, a odstępy
 * nie zgadzały się z oryginałem. Teraz idą oryginalne krzywe.
 *
 * ## Czemu dwie warstwy
 * Bo litery mają iść za motywem (raz białe, raz czarne), a kropki są ZAWSZE
 * cyjanowe - to jedyny kolor w tym znaku. Jeden zasób przebarwiony przez [Icon]
 * pomalowałby kropki razem z literami i logo straciłoby swój jedyny akcent.
 * Oba pliki mają ten sam układ współrzędnych, więc nakładają się co do piksela.
 */
@Composable
fun VictorWordmark(
    modifier: Modifier = Modifier,
    letterColor: Color = MaterialTheme.colorScheme.onBackground,
    dotColor: Color = VictorCyan,
    height: Dp = 22.dp
) {
    Box(modifier = modifier.height(height)) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_victor_wordmark),
            contentDescription = "V.I.C.T.O.R.",
            tint = letterColor,
            modifier = Modifier.fillMaxHeight()
        )
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_victor_dots),
            contentDescription = null,
            tint = dotColor,
            modifier = Modifier.fillMaxHeight()
        )
    }
}

/**
 * Cyjan marki, wzięty wprost z pliku logo.
 *
 * Jedyny kolor w całym znaku, więc to on go rozpoznaje. Stała tutaj, a nie w
 * motywie, bo ma zostać TEN SAM także wtedy, gdy motyw jest inny - logo nie
 * zmienia koloru razem z resztą ekranu.
 */
val VictorCyan = Color(0xFF00A0E3)

/** Czerń marki z pliku logo - do teł, na których znak ma stać biały. */
val VictorInk = Color(0xFF2B2A29)

/** Znak + wordmark pod spodem - do ekranów powitalnych/about. */
@Composable
fun VictorBrandMark(
    modifier: Modifier = Modifier,
    markSize: Dp = 64.dp
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Szerokość, nie rozmiar: znak ma proporcję 2,56:1, więc kwadratowe
        // pole zostawiłoby nad nim i pod nim pustkę wysokości jednej trzeciej.
        VictorMark(modifier = Modifier.width(markSize))
        Spacer(Modifier.height(12.dp))
        // Logotyp mniej więcej w jednej trzeciej szerokości znaku - tak jak w
        // oryginalnym pliku marki.
        VictorWordmark(height = markSize / 8)
    }
}
