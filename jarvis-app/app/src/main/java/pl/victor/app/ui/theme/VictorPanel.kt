package pl.victor.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Panel - pole odczytu, nie kafelek.
 *
 * ## Czym różni się od zwykłej karty
 * Materialowa `Card` oddziela treść CIENIEM i wypełnieniem: wygląda jak
 * kartonik leżący na tle. Panel oddziela ją KRESKĄ, a tło zostawia prawie takie
 * samo jak dookoła - tak buduje się pola w przyrządach pomiarowych i to jest
 * cała różnica między "przyjaźnie" a "technicznie".
 *
 * Cienia nie ma z premedytacją. Na niemal czarnym tle cień i tak nie jest
 * widoczny, więc udawałby głębię, której nie widać - a każde takie udawanie
 * kosztuje potem pytanie "czemu to wygląda na zepsute".
 *
 * ## Nagłówek
 * Krótka etykieta wersalikami, monospace, w kolorze akcentu, poprzedzona
 * cyjanową kreską. Nie jest ozdobą: nazywa pole, tak jak opis pod gałką. Panel
 * bez etykiety jest w porządku - wtedy sama ramka wystarcza.
 *
 * @param accent czy pole ma być wyróżnione - obwódka idzie wtedy cyjanem
 *   zamiast zwykłego obrysu. Do JEDNEGO pola na ekranie; gdy wyróżnione są
 *   wszystkie, nie jest wyróżnione żadne.
 */
@Composable
fun VictorPanel(
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Boolean = false,
    contentPadding: Int = PANEL_PADDING,
    content: @Composable ColumnScope.() -> Unit
) {
    val border = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (accent) ACCENT_BORDER.dp else BORDER.dp, border),
        // Zero: patrz komentarz o cieniu wyżej.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding.dp)) {
            if (label != null) {
                PanelLabel(label)
            }
            content()
        }
    }
}

/**
 * Etykieta pola - kreska, odstęp, wersaliki.
 *
 * Wersaliki i rozstrzelenie, bo to podpis, nie zdanie; monospace bierze się z
 * motywu (`labelSmall`), więc etykieta wygląda tak samo jak liczby w środku.
 */
@Composable
private fun PanelLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = LABEL_GAP.dp)
    ) {
        Box(
            modifier = Modifier
                .width(TICK_WIDTH.dp)
                .height(TICK_HEIGHT.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = LABEL_GAP.dp)
        )
    }
}

/**
 * Wiersz odczytu: nazwa z lewej, wartość z prawej.
 *
 * Wartość idzie monospace (`labelMedium` z motywu) i to nie jest wybór
 * estetyczny: cyfry o stałej szerokości przestają skakać, gdy licznik rośnie z
 * 9 na 10. Przy liczniku tokenów, który zmienia się co turę, widać to od razu.
 */
@Composable
fun VictorReadout(
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = READOUT_GAP.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.labelMedium, color = valueColor)
    }
}

private const val PANEL_PADDING = 12
private const val BORDER = 1
private const val ACCENT_BORDER = 1
private const val LABEL_GAP = 6
private const val TICK_WIDTH = 2
private const val TICK_HEIGHT = 10
private const val READOUT_GAP = 3
