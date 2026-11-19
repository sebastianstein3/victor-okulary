package pl.victor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.victor.app.data.ModelInfo
import pl.victor.app.data.ModelRegistry

/**
 * Animowany badge pokazujący aktualnie używany model AI.
 *
 * Animacje:
 * - Pulsująca kropka "online" (nieskończona)
 * - Płynne przejście koloru (active/deprecated/migrated)
 * - Fade in/out przy zmianie modelu
 * - Slide down przy pojawieniu się
 */
@Composable
fun ModelBadge(
    modelId: String?,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true
) {
    val info: ModelInfo? = modelId?.let { ModelRegistry.findById(it) }
    // Świadomie BEZ fallbacku na domyślny model Gemini: wcześniej każdy nieznany albo
    // pusty modelId powodował, że badge wyświetlał "Gemini 2.5 Flash" niezależnie
    // od tego, co realnie było ustawione.
    val displayName = info?.displayName ?: modelId ?: "nieznany"

    // Kolor zależy od stanu
    val targetColor = when {
        info == null -> MaterialTheme.colorScheme.surfaceVariant
        info.deprecated -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 500),
        label = "badgeColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = animatedColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pulsująca kropka
            PulsingDot(
                color = when {
                    info?.deprecated == true -> MaterialTheme.colorScheme.error
                    else -> Color(0xFF4CAF50)  // zielony = online
                }
            )

            Spacer(Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Model:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                AnimatedVisibility(
                    visible = showDetails,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        info?.releaseDate?.let { date ->
                            Text(
                                text = "Wydany: $date" + (info.contextWindow?.let { " · ${it / 1000}k tok" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (info?.deprecated == true && info.replacementId != null) {
                            val replacement = ModelRegistry.findById(info.replacementId)
                            Text(
                                text = "⚠️ Przestarzały → ${replacement?.displayName ?: info.replacementId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pulsująca kropka - animacja online indicator.
 */
@Composable
fun PulsingDot(
    color: Color,
    size: androidx.compose.ui.unit.Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}
