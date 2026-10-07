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
    }

    companion object {
        private const val TAG = "MeetingStore"
    }
}
