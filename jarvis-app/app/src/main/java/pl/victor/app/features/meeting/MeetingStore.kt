package pl.victor.app.features.meeting

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/** Spotkania na dysku - jeden plik JSON na spotkanie, w pamięci aplikacji. */
class MeetingStore(context: Context) {

    private val katalog = File(context.filesDir, "meetings").apply { mkdirs() }
    private val gson = Gson()

    fun zapisz(m: Meeting) {
        runCatching { File(katalog, "${m.id}.json").writeText(gson.toJson(m)) }
            .onFailure { Log.w(TAG, "Nie zapisałem spotkania ${m.id}", it) }
    }

    /** Najnowsze pierwsze. */
    fun wszystkie(): List<Meeting> =
        katalog.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { gson.fromJson(f.readText(), Meeting::class.java) }.getOrNull() }
            .sortedByDescending { it.startMs }

    fun usuń(id: String) {
        File(katalog, "$id.json").delete()
        File(katalog, "$id.partial").delete()
    }

    /**
     * Bieżący stan spotkania, zapisywany w trakcie - transkrypcja żyła tylko w
     * pamięci i crash albo zabicie procesu w połowie godzinnego spotkania
     * kasował wszystko (audyt 28.10). Plik `.partial` nie pojawia się na liście.
     */
    fun zapiszCzęściowo(m: Meeting) {
        runCatching { File(katalog, "${m.id}.partial").writeText(gson.toJson(m)) }
            .onFailure { Log.w(TAG, "Nie zapisałem stanu spotkania ${m.id}", it) }
    }

    fun usuńCzęściowe(id: String) {
        File(katalog, "$id.partial").delete()
    }

    /**
     * Odzyskuje spotkania przerwane (proces zabity w trakcie nagrywania): zapisuje
     * je jako zwykłe, bez podsumowania.
     *
     * @return ile odzyskano
     */
    fun odzyskaj(): Int {
        var ile = 0
        katalog.listFiles { f -> f.name.endsWith(".partial") }.orEmpty().forEach { f ->
            val id = f.name.removeSuffix(".partial")
            val gotowe = File(katalog, "$id.json")
            if (!gotowe.exists()) {
                runCatching { gson.fromJson(f.readText(), Meeting::class.java) }.getOrNull()
                    ?.takeIf { it.linie.isNotEmpty() }
                    ?.let { zapisz(it); ile++ }
            }
            f.delete()
        }
        return ile
    }

    companion object {
        private const val TAG = "MeetingStore"
    }
}
