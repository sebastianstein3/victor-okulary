package pl.victor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import pl.victor.app.ui.theme.Motion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Co potrafię" - spis możliwości V.I.C.T.O.R.-a wprost na ekranie głównym.
 *
 * ## Po co
 * Zgłoszono: "mało funkcji w panelu aplikacji widocznych". I słusznie:
 * aplikacja umie dwadzieścia kilka rzeczy - wysłać SMS-a, umówić spotkanie,
 * poprowadzić osobę niewidomą, przetłumaczyć, włączyć latarkę - ale JEDYNYM
 * miejscem, gdzie ta lista istniała, był prompt wysyłany do modelu. Człowiek
 * nie miał jej gdzie zobaczyć, więc funkcje formalnie były, a praktycznie
 * nie istniały.
 *
 * Każdy przykład jest klikalny i od razu wykonywany jako pytanie - to nie jest
 * ściąga do przeczytania, tylko sposób użycia tych funkcji.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilitiesPanel(
    onExample: (String) -> Unit,
    onOpenAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✨", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Co potrafię",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Dotknij przykładu, żeby od razu spróbować",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
            }

            AnimatedVisibility(
                visible = expanded,
                // Te same krzywe i czasy co przy grupach ustawień - patrz
                // [pl.victor.app.ui.theme.Motion].
                enter = fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOutCubic)) +
                    expandVertically(tween(Motion.ENTER_MS, easing = Motion.EaseOutCubic)),
                exit = fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseInCubic)) +
                    shrinkVertically(tween(Motion.EXIT_MS, easing = Motion.EaseInCubic))
            ) {
                Column {
                    Spacer(Modifier.size(8.dp))
                    CAPABILITIES.forEach { group ->
                        Text(
                            "${group.emoji} ${group.title}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.examples.forEach { example ->
                                SuggestionChip(
                                    onClick = { onExample(example) },
                                    label = { Text(example, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "To samo działa głosem - przez okulary albo przyciskiem " +
                            "mikrofonu niżej.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    // Ten panel jest wstępem, nie spisem. Pełna lista z opisem
                    // działania każdej komendy - i dodawanie własnych - jest na
                    // osobnym ekranie, bo tu by się nie zmieściła.
                    OutlinedButton(onClick = onOpenAll) {
                        Text("Wszystkie komendy i własne frazy", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Grupa możliwości z przykładami do jednego dotknięcia. */
private data class CapabilityGroup(
    val emoji: String,
    val title: String,
    val examples: List<String>
)

/**
 * Lista jest ręczna, nie generowana z [pl.victor.app.actions.ActionType] - i tak
 * ma być. Człowiek nie szuka "toggle_flashlight", tylko "zapal latarkę", a
 * przykład musi być zdaniem, które faktycznie zadziała, gdy się je kliknie.
 *
 * Przy dokładaniu nowej akcji dopisz ją TUTAJ - inaczej znów powstanie funkcja,
 * o której nikt się nie dowie.
 */
private val CAPABILITIES = listOf(
    CapabilityGroup(
        "👁️", "Patrzę przez okulary",
        listOf("Co właśnie widzę?", "Przeczytaj mi to", "Co to za budynek?")
    ),
    CapabilityGroup(
        "💬", "Rozmawiam i tłumaczę",
        listOf("Jak powiedzieć po angielsku dziękuję?", "Wyjaśnij mi to prościej")
    ),
    CapabilityGroup(
        "☀️", "Poranek",
        listOf("Briefing", "Co mnie dziś czeka?")
    ),
    CapabilityGroup(
        "📅", "Kalendarz i czas",
        listOf("Co mam dziś w kalendarzu?", "Umów spotkanie jutro na 15",
            "Ustaw budzik na 7:00", "Minutnik na 10 minut")
    ),
    CapabilityGroup(
        "📞", "Kontakt",
        listOf("Wyślij SMS do Ani, że się spóźnię", "Zadzwoń do domu")
    ),
    CapabilityGroup(
        "🗺️", "W drodze",
        listOf("Prowadź mnie do domu", "Pokaż na mapie najbliższą aptekę")
    ),
    CapabilityGroup(
        "🎵", "Muzyka i telefon",
        listOf("Włącz muzykę", "Następny utwór", "Zapal latarkę", "Włącz Spotify")
    ),
    CapabilityGroup(
        "🌤️", "Świat dookoła",
        listOf("Jaka będzie pogoda?", "Poszukaj w sieci przepisu na żurek")
    ),
    CapabilityGroup(
        "🦯", "Dla niewidomych",
        listOf("Opisuj mi otoczenie", "Czytaj tekst na głos", "Dziękuję, wystarczy")
    )
)
