package pl.victor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.victor.app.VictorApplication
import pl.victor.app.wakeword.NaukaGlosu
import pl.victor.app.wakeword.NaukaGlosuDane
import pl.victor.app.wakeword.ProfilGlosu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Etap kreatora nauki głosu. */
private enum class KrokNauki { ZAMKNIETY, FRAZY, TLO, UCZENIE }

/**
 * "Naucz mój głos" - personalizacja „Hej Wiktor” nagraniami właściciela.
 *
 * Darmowe, wszystko na telefonie (patrz [NaukaGlosu]). Kreator: 6-10 nagrań
 * frazy z oceną jakości i odsłuchem, potem ~25 s zwykłej mowy jako tło, potem
 * nauka weryfikatora z walidacją. Do tego "Tylko mój głos", korekta
 * fałszywego wybudzenia i usunięcie danych głosowych.
 *
 * Pokazywane pod wyborem frazy, gdy wybrano „Hej Wiktor” - bez nagrań fraza
 * działa jak dotąd, więc to dodatek, nie warunek.
 */
@Composable
internal fun NaukaGlosuSection() {
    val context = LocalContext.current
    val app = remember { context.applicationContext as VictorApplication }
    val nauka = remember { NaukaGlosu(context) }
    val scope = rememberCoroutineScope()

    var profil by remember { mutableStateOf<ProfilGlosu?>(null) }
    var tylkoMoj by remember { mutableStateOf(app.settings.isWakeOnlyMyVoice()) }
    var komunikat by remember { mutableStateOf<String?>(null) }
    var potwierdzUsuniecie by remember { mutableStateOf(false) }
    var ostatnie by remember { mutableStateOf(app.phoneWake.ostatnieWybudzenie) }
    var pracuje by remember { mutableStateOf(false) }

    var krok by remember { mutableStateOf(KrokNauki.ZAMKNIETY) }
    val frazy = remember { mutableStateListOf<NaukaGlosu.Proba>() }
    var odrzuconych by remember { mutableStateOf(0) }
    var ostatniaProba by remember { mutableStateOf<NaukaGlosu.Proba?>(null) }
    var nagrywa by remember { mutableStateOf(false) }
    var mowTeraz by remember { mutableStateOf(false) }
    var odtwarza by remember { mutableStateOf(false) }
    var tlo by remember { mutableStateOf<NaukaGlosu.Proba?>(null) }
    var tloMs by remember { mutableStateOf(0L) }
    var postepNauki by remember { mutableStateOf("") }
    val przerwij = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        profil = withContext(Dispatchers.IO) { runCatching { nauka.profil() }.getOrNull() }
        // Ostatnie wybudzenie zmienia się w tle - odświeżamy, póki sekcja jest na ekranie.
        while (true) {
            delay(2_000)
            ostatnie = app.phoneWake.ostatnieWybudzenie
        }
    }

    // Wyjście z Ustawień w trakcie nauki: przerwać nagranie i oddać mikrofon nasłuchowi.
    val krokTeraz by rememberUpdatedState(krok)
    DisposableEffect(Unit) {
        onDispose {
            if (krokTeraz != KrokNauki.ZAMKNIETY) {
                przerwij.set(true)
                nauka.zakoncz()
            }
        }
    }

    fun wyczyscKreator() {
        przerwij.set(true)
        frazy.clear()
        odrzuconych = 0
        ostatniaProba = null
        tlo = null
        tloMs = 0L
        nagrywa = false
        mowTeraz = false
    }

    fun zamknijKreator() {
        wyczyscKreator()
        krok = KrokNauki.ZAMKNIETY
        scope.launch { withContext(Dispatchers.IO) { nauka.zakoncz() } }
    }

    fun odsluchaj(probki: ShortArray) {
        if (odtwarza) return
        odtwarza = true
        scope.launch {
            withContext(Dispatchers.IO) { nauka.odtworz(probki) }
            odtwarza = false
        }
    }

    fun nagrajFraze() {
        przerwij.set(false)
        nagrywa = true
        mowTeraz = false
        ostatniaProba = null
        val nr = frazy.size + odrzuconych + 1
        scope.launch {
            val proba = withContext(Dispatchers.IO) {
                nauka.nagrajFraze(nr, { przerwij.get() }) {
                    scope.launch {
                        delay(NaukaGlosuDane.MOW_PO_MS)
                        if (nagrywa) mowTeraz = true
                    }
                }
            }
            nagrywa = false
            mowTeraz = false
            if (krok != KrokNauki.FRAZY) return@launch
            ostatniaProba = proba
            if (proba.dobra) frazy.add(proba) else if (proba.blad == null) odrzuconych++
        }
    }

    fun nagrajTlo() {
        przerwij.set(false)
        nagrywa = true
        tlo = null
        tloMs = 0L
        scope.launch {
            val proba = withContext(Dispatchers.IO) {
                nauka.nagrajTlo({ przerwij.get() }) { ms ->
                    if (ms / 1000 != tloMs / 1000) scope.launch { tloMs = ms }
                }
            }
            nagrywa = false
            if (krok == KrokNauki.TLO) tlo = proba
        }
    }

    fun naucz() {
        val t = tlo ?: return
        val nagrania = frazy.map { it.probki }
        val odrzucone = odrzuconych
        krok = KrokNauki.UCZENIE
        postepNauki = "Przygotowuję…"
        scope.launch {
            val wynik = withContext(Dispatchers.Default) {
                nauka.naucz(nagrania, odrzucone, t.probki, t.mikrofon) { s -> scope.launch { postepNauki = s } }
            }
            komunikat = wynik.komunikat
            if (wynik.ok) profil = wynik.profil
            zamknijKreator()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Naucz mój głos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        Text(
            "Nagraj kilka razy „Hej Wiktor” i chwilę zwykłej mowy. Telefon nauczy się, jak " +
                "TY mówisz frazę: łatwiej ją wtedy usłyszy i rzadziej obudzi się sam z siebie. " +
                "Darmowe, wszystko na telefonie. Dźwięk nie jest zapisywany - tylko liczby " +
                "wyliczone z nagrań (to nadal dane o głosie, możesz je usunąć).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (krok) {
            KrokNauki.ZAMKNIETY -> {
                val p = profil
                Text(
                    if (p == null) "Brak nauczonego głosu - fraza działa na modelu ogólnym." else NaukaGlosu.opis(p),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
                komunikat?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Button(
                        enabled = !pracuje,
                        onClick = {
                            komunikat = null
                            wyczyscKreator()
                            krok = KrokNauki.FRAZY
                            scope.launch { withContext(Dispatchers.IO) { nauka.rozpocznij() } }
                        }
                    ) { Text(if (p == null) "Naucz mój głos" else "Naucz od nowa") }
                    if (p != null) {
                        OutlinedButton(enabled = !pracuje, onClick = { potwierdzUsuniecie = true }) {
                            Text("Usuń moje nagrania")
                        }
                    }
                }

                if (p != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tylko mój głos")
                            Text(
                                "Ostrzej: budzi tylko fraza, którą rozpozna weryfikator nauczony na " +
                                    "Twoich nagraniach - mniej fałszywych wybudzeń, ale fraza " +
                                    "powiedziana inaczej (np. z daleka, z katarem) może nie zadziałać. " +
                                    "To nie jest zabezpieczenie: podobny głos też może obudzić.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = tylkoMoj, onCheckedChange = {
                            tylkoMoj = it
                            app.settings.setWakeOnlyMyVoice(it)
                        })
                    }

                    val ost = ostatnie
                    if (ost != null) {
                        val czas = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ost.czasMs))
                        Text(
                            "Ostatnie wybudzenie: $czas (wynik ${"%.2f".format(ost.wynik)}" +
                                (ost.weryfikator?.let { ", głos ${"%.2f".format(it)}" } ?: "") +
                                ", ${ost.decyzja.opis}).",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        OutlinedButton(
                            enabled = !pracuje,
                            onClick = {
                                pracuje = true
                                scope.launch {
                                    komunikat = withContext(Dispatchers.Default) { nauka.oznaczFalszywe() }
                                    profil = withContext(Dispatchers.IO) { runCatching { nauka.profil() }.getOrNull() }
                                    ostatnie = app.phoneWake.ostatnieWybudzenie
                                    pracuje = false
                                }
                            }
                        ) { Text("To było fałszywe wybudzenie") }
                    }
                }
            }

            KrokNauki.FRAZY -> {
                Text(
                    "Krok 1 z 2. Trzymaj telefon tak jak zwykle, gdy mówisz „Hej Wiktor”. Naciśnij " +
                        "„Nagraj”, poczekaj na napis „Mów teraz” i powiedz frazę normalnym głosem. " +
                        "Potrzeba ${NaukaGlosuDane.MIN_NAGRAN}-${NaukaGlosuDane.MAX_NAGRAN} dobrych nagrań.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    "Dobre nagrania: ${frazy.size} z min. ${NaukaGlosuDane.MIN_NAGRAN}" +
                        if (odrzuconych > 0) " (odrzucone: $odrzuconych)" else "",
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (nagrywa) {
                    Text(
                        if (mowTeraz) "🎙 Mów teraz: „Hej Wiktor”" else "Przygotuj się…",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (mowTeraz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                ostatniaProba?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            (if (p.dobra) "✅ " else "⚠ ") + p.komunikat,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.dobra) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (p.probki.isNotEmpty()) {
                            TextButton(enabled = !odtwarza && !nagrywa, onClick = { odsluchaj(p.probki) }) {
                                Text("Odsłuchaj")
                            }
                        }
                    }
                }
                frazy.forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Nagranie ${i + 1}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(enabled = !odtwarza && !nagrywa, onClick = { odsluchaj(p.probki) }) { Text("Odsłuchaj") }
                        TextButton(enabled = !nagrywa, onClick = {
                            frazy.removeAt(i)
                            if (ostatniaProba === p) ostatniaProba = null
                        }) { Text("Usuń") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(
                        enabled = !nagrywa && !odtwarza && frazy.size < NaukaGlosuDane.MAX_NAGRAN,
                        onClick = { nagrajFraze() }
                    ) { Text("Nagraj") }
                    OutlinedButton(
                        enabled = !nagrywa && frazy.size >= NaukaGlosuDane.MIN_NAGRAN,
                        onClick = {
                            ostatniaProba = null
                            krok = KrokNauki.TLO
                        }
                    ) { Text("Dalej") }
                    TextButton(onClick = { zamknijKreator() }) { Text("Anuluj") }
                }
            }

            KrokNauki.TLO -> {
                Text(
                    "Krok 2 z 2. Teraz mów zwyczajnie przez ok. ${NaukaGlosuDane.CZAS_TLA_MS / 1000} s - np. " +
                        "przeczytaj na głos wiadomość albo opowiedz, co dziś robisz. Nie mów " +
                        "„Hej Wiktor”. To uczy telefon, czego NIE brać za frazę.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (nagrywa) {
                    Text(
                        "🎙 Nagrywam… ${tloMs / 1000} s z ${NaukaGlosuDane.CZAS_TLA_MS / 1000}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                tlo?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            (if (p.dobra) "✅ " else "⚠ ") + p.komunikat,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.dobra) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (p.probki.isNotEmpty()) {
                            TextButton(enabled = !odtwarza, onClick = { odsluchaj(p.probki) }) { Text("Odsłuchaj") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (nagrywa) {
                        Button(
                            // Krócej niż minimum ocena i tak odrzuci - nie kusimy przyciskiem.
                            enabled = tloMs >= pl.victor.app.wakeword.OcenaNagrania.MIN_TLO_MS,
                            onClick = { przerwij.set(true) }
                        ) { Text("Zakończ") }
                    } else {
                        Button(enabled = !odtwarza, onClick = { nagrajTlo() }) {
                            Text(if (tlo == null) "Nagraj" else "Nagraj jeszcze raz")
                        }
                        OutlinedButton(enabled = tlo?.dobra == true && !odtwarza, onClick = { naucz() }) {
                            Text("Ucz")
                        }
                    }
                    TextButton(enabled = !nagrywa, onClick = { krok = KrokNauki.FRAZY }) { Text("Wstecz") }
                    TextButton(onClick = { zamknijKreator() }) { Text("Anuluj") }
                }
            }

            KrokNauki.UCZENIE -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(postepNauki, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (potwierdzUsuniecie) {
        AlertDialog(
            onDismissRequest = { potwierdzUsuniecie = false },
            title = { Text("Usunąć nagrany głos?") },
            text = {
                Text(
                    "Usunę wszystkie dane wyliczone z Twoich nagrań i weryfikator. " +
                        "„Hej Wiktor” wróci do modelu ogólnego."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    potwierdzUsuniecie = false
                    pracuje = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { nauka.usun() }
                        profil = null
                        ostatnie = null
                        komunikat = if (ok) "Usunięte." else "Nie udało się usunąć wszystkich plików."
                        pracuje = false
                    }
                }) { Text("Usuń") }
            },
            dismissButton = {
                TextButton(onClick = { potwierdzUsuniecie = false }) { Text("Anuluj") }
            }
        )
    }
}
