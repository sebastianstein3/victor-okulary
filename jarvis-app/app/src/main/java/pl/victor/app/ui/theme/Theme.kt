package pl.victor.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import pl.victor.app.data.SettingsRepository

/**
 * Ciemny motyw - domyślny język tej aplikacji.
 *
 * Tło niemal czarne, powierzchnie ledwie jaśniejsze, a hierarchię niosą
 * OBRAMOWANIA zamiast wypełnień. Stąd mocny `outline` przy oszczędnych
 * różnicach tła: to jest ten sam zabieg, co w przyrządach pomiarowych, gdzie
 * pola oddziela kreska, a nie odcień.
 *
 * Cyjan jest tu JEDYNYM kolorem i dlatego działa - gdy wszystko dookoła jest
 * achromatyczne, akcent nie musi krzyczeć, żeby go było widać.
 */
// Role `surfaceContainer*` są tu ŚWIADOMIE pominięte. Weszły do Material3 w
// okolicach 1.2 i nie mam offline czym potwierdzić, czy są w sygnaturze przy
// BOM 2024.06 - a Material wyprowadza je z `surface`, które i tak jest już
// markowe. Zgadnięty parametr to zepsuty build za siedem minut; pominięty nie
// kosztuje nic.
private val DarkColorScheme = darkColorScheme(
    primary = VictorColors.Cyan,
    onPrimary = VictorColors.OnCyan,
    primaryContainer = Color(0xFF00384F),
    onPrimaryContainer = VictorColors.CyanBright,
    secondary = VictorColors.CyanBright,
    onSecondary = VictorColors.OnCyan,
    tertiary = VictorColors.CyanBright,
    onTertiary = VictorColors.OnCyan,
    background = VictorColors.DarkBackground,
    onBackground = VictorColors.DarkOnBackground,
    surface = VictorColors.DarkSurface,
    onSurface = VictorColors.DarkOnBackground,
    surfaceVariant = VictorColors.DarkSurfaceHigh,
    onSurfaceVariant = VictorColors.DarkOnSurfaceMuted,
    outline = VictorColors.DarkOutline,
    outlineVariant = VictorColors.DarkOutlineFaint,
    error = VictorColors.DangerDark,
    onError = VictorColors.OnCyan
)

/**
 * Jasny motyw - ta sama marka na białym.
 *
 * Tekst i ikony idą [VictorColors.CyanDeep], a nie markowym cyjanem: ten na
 * bieli daje circa 2,7:1 przy wymaganych 4,5:1 i byłby po prostu nieczytelny.
 * Wypełnienia - paski postępu, przełączniki, obramowania - zostają pełnym
 * cyjanem, bo tam kontrast liczy się wobec sąsiedniej powierzchni, nie wobec
 * tła pod tekstem.
 */
private val LightColorScheme = lightColorScheme(
    primary = VictorColors.CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EEFA),
    onPrimaryContainer = Color(0xFF00364C),
    secondary = VictorColors.CyanDeep,
    onSecondary = Color.White,
    tertiary = VictorColors.CyanDeep,
    onTertiary = Color.White,
    background = VictorColors.LightBackground,
    onBackground = VictorColors.Ink,
    surface = VictorColors.LightSurface,
    onSurface = VictorColors.Ink,
    surfaceVariant = VictorColors.LightSurfaceHigh,
    onSurfaceVariant = VictorColors.LightOnSurfaceMuted,
    outline = VictorColors.LightOutline,
    outlineVariant = VictorColors.LightOutlineFaint,
    error = VictorColors.DangerLight,
    onError = Color.White
)

/**
 * Narożniki - prawie ostre.
 *
 * Material daje domyślnie 12, 16 i 28 dp, czyli kształty miękkie i przyjazne.
 * Ta aplikacja ma wyglądać na przyrząd, więc schodzimy do 2-6 dp: wciąż nie
 * surowy prostokąt (ten przy przyciskach wygląda na niedokończony), ale
 * różnica jest widoczna na każdym ekranie naraz.
 *
 * To jedna z dwóch rzeczy, które zmieniają CHARAKTER wszystkich czternastu
 * ekranów bez dotykania ich kodu - druga to [VictorTypography].
 */
private val VictorShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

/**
 * Typografia - monospace tam, gdzie stoją DANE.
 *
 * `labelMedium` i `labelSmall` to w tej aplikacji plakietki, podpisy pod
 * kafelkami, liczniki tokenów, nazwy plików i wiersze diagnostyki. Monospace
 * robi z nich odczyt z przyrządu zamiast zdania - a przy okazji naprawia realną
 * niedogodność: cyfry w stałej szerokości przestają skakać, gdy licznik rośnie.
 *
 * `labelLarge` zostaje proporcjonalny Z ROZMYSŁEM - to jest styl NAPISÓW NA
 * PRZYCISKACH. Monospace na przycisku "Zapisz wszystko" wygląda jak terminal,
 * nie jak przycisk, a przyciski mają być czytelne, nie stylowe.
 */
private val VictorTypography = Typography().let { base ->
    base.copy(
        labelMedium = base.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp
        )
    )
}

/**
 * Wysoki kontrast - czysta czerń i biel z nasyconymi akcentami.
 * Dla osób słabowidzących; wyłącza dynamic color, bo tapeta systemowa
 * potrafi obniżyć kontrast.
 */
private val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color.Black,
    secondary = Color(0xFFFFEB3B),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    outline = Color.White
)

private val HighContrastLightScheme = lightColorScheme(
    primary = Color(0xFF00344D),
    onPrimary = Color.White,
    secondary = Color(0xFF4A2800),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color.Black,
    error = Color(0xFFB00020),
    onError = Color.White,
    outline = Color.Black
)

/** Mnożnik rozmiaru tekstu przy włączonych dużych literach. */
private const val LARGE_TEXT_SCALE = 1.30f

/**
 * Motyw aplikacji V.I.C.T.O.R.
 *
 * Domyślnie czyta opcje dostępności (wysoki kontrast, duże litery) z ustawień.
 * Parametry można nadpisać w podglądach Compose.
 */
@Composable
fun VictorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Czy brać kolory z tapety telefonu zamiast z marki.
     *
     * DOMYŚLNIE WYŁĄCZONE, odwrotnie niż wcześniej. Na Androidzie 12+ aplikacja
     * przejmowała paletę z tapety, przez co cyjan z logo nie miał jak się
     * przebić, a dwie osoby testujące ten sam build widziały dwie różne
     * aplikacje - przy zbieraniu zgłoszeń "ten niebieski panel" przestaje wtedy
     * cokolwiek identyfikować.
     *
     * Parametr zostaje, bo podglądy Compose i ewentualne ustawienie mogą go
     * jeszcze potrzebować.
     */
    dynamicColor: Boolean = false,
    highContrast: Boolean? = null,
    largeText: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { SettingsRepository.getInstance(context) }

    val useHighContrast = highContrast ?: settings.isHighContrastEnabled()
    val useLargeText = largeText ?: settings.isLargeTextEnabled()

    val colorScheme = when {
        // Wysoki kontrast ma pierwszeństwo przed dynamic color.
        useHighContrast && darkTheme -> HighContrastDarkScheme
        useHighContrast -> HighContrastLightScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val typography = if (useLargeText) {
        remember { VictorTypography.scaledBy(LARGE_TEXT_SCALE) }
    } else {
        VictorTypography
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = VictorShapes,
        content = content
    )
}

/**
 * Powiększa wszystkie style tekstu o zadany mnożnik.
 * Wartości nieokreślone (`TextUnit.Unspecified`) zostają nietknięte -
 * mnożenie ich rzuca wyjątkiem.
 */
private fun Typography.scaledBy(factor: Float): Typography = Typography(
    displayLarge = displayLarge.scaledBy(factor),
    displayMedium = displayMedium.scaledBy(factor),
    displaySmall = displaySmall.scaledBy(factor),
    headlineLarge = headlineLarge.scaledBy(factor),
    headlineMedium = headlineMedium.scaledBy(factor),
    headlineSmall = headlineSmall.scaledBy(factor),
    titleLarge = titleLarge.scaledBy(factor),
    titleMedium = titleMedium.scaledBy(factor),
    titleSmall = titleSmall.scaledBy(factor),
    bodyLarge = bodyLarge.scaledBy(factor),
    bodyMedium = bodyMedium.scaledBy(factor),
    bodySmall = bodySmall.scaledBy(factor),
    labelLarge = labelLarge.scaledBy(factor),
    labelMedium = labelMedium.scaledBy(factor),
    labelSmall = labelSmall.scaledBy(factor)
)

private fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight
)
// `copy` zachowuje rodzinę czcionki, więc duże litery nie gubią monospace przy
// danych - a właśnie tam są najczęściej potrzebne.
