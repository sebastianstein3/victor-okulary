package pl.victor.app.google

import android.content.Context
import android.util.Log
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.victor.app.notes.Notes
import pl.victor.app.notes.NotesDocument

/**
 * Wysyła notatki na Dysk Google jako jeden Dokument.
 *
 * ## Po co to jest
 * NotebookLM nie ma publicznego API dla zwykłych kont - jest tylko wersja
 * Enterprise, wymagająca Google Cloud i konta firmowego. Ma za to obsługę
 * dokumentów z Dysku jako źródeł, razem z odświeżaniem. Ta klasa dokłada
 * brakujące ogniwo: notatki lądują w dokumencie, dokument dodaje się w
 * NotebookLM RAZ, a potem aktualizuje się sam.
 *
 * Działa też bez NotebookLM - to po prostu kopia notatek poza telefonem.
 *
 * ## Dlaczego cała treść za każdym razem
 * Dopisywanie w miejscu wymagałoby Docs API i osobnego, szerszego zakresu
 * uprawnień. Notatek są dziesiątki, nie tysiące, więc nadpisanie całości jest
 * tańsze niż druga zgoda od użytkownika - i jest idempotentne: dwa
 * uruchomienia z rzędu dają ten sam dokument, a nie dwie kopie.
 */
class NotesDocSync(context: Context) {

    private val tag = "NotesDocSync"
    private val accountManager = GoogleAccountManager(context)

    /** Czy konto ma zgodę na zapis na Dysku. */
    fun hasAccess(): Boolean = accountManager.hasDriveAccess()

    /**
     * Wysyła notatki na Dysk.
     *
     * @param existingFileId identyfikator dokumentu z poprzedniej synchronizacji
     *   albo `null`, gdy jeszcze go nie ma
     * @return wynik z identyfikatorem dokumentu - wołający ma go zapamiętać,
     *   inaczej następna synchronizacja utworzy DRUGI plik
     */
    suspend fun sync(
        notes: List<Notes.Note>,
        existingFileId: String?
    ): Result = withContext(Dispatchers.IO) {
        val credential = accountManager.getDriveCredential()
            ?: return@withContext Result.NoAccess

        val drive = try {
            Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("V.I.C.T.O.R.")
                .build()
        } catch (e: Exception) {
            Log.e(tag, "Nie udało się zbudować klienta Dysku", e)
            return@withContext Result.Failed(e.message ?: "Błąd klienta Dysku")
        }

        val content = ByteArrayContent(
            "text/plain",
            NotesDocument.build(notes).toByteArray(Charsets.UTF_8)
        )

        return@withContext try {
            // Aktualizacja istniejącego pliku. Gdy zniknął (użytkownik go
            // skasował na Dysku), Google odpowiada 404 - wtedy tworzymy nowy
            // zamiast kończyć błędem, bo z punktu widzenia użytkownika to jest
            // ta sama czynność.
            val fileId = existingFileId?.let { id ->
                try {
                    drive.files().update(id, null, content).execute().id
                } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
                    // TYLKO zniknięty plik (404/410) zastępujemy nowym. Każdy inny
                    // błąd (sieć, 429, 5xx) tworzył nowy dokument, a źródło w
                    // NotebookLM zostawało przy starym (przegląd 22.11).
                    if (e.statusCode != 404 && e.statusCode != 410) throw e
                    Log.w(tag, "Dokument $id niedostępny - tworzę nowy", e)
                    null
                }
            } ?: createDocument(drive, content)

            Log.i(tag, "Notatki wysłane na Dysk (id=$fileId)")
            Result.Success(fileId, notes.size)
        } catch (e: Exception) {
            // Wygasłe logowanie ma dostać własny komunikat: "błąd Dysku" wysyła
            // użytkownika w stronę uprawnień, a wystarczy zalogować się ponownie.
            if (GoogleAccountManager.noteApiFailure(e)) {
                Log.w(tag, "Dysk: logowanie Google wygasło", e)
                Result.Failed("Logowanie Google wygasło - zaloguj się ponownie w Ustawieniach.")
            } else {
                Log.e(tag, "Synchronizacja z Dyskiem nie powiodła się", e)
                Result.Failed(e.message ?: "Nieznany błąd")
            }
        }
    }

    private fun createDocument(drive: Drive, content: ByteArrayContent): String {
        val metadata = com.google.api.services.drive.model.File().apply {
            name = NotesDocument.FILE_NAME
            // Dysk sam konwertuje wysłany tekst na Dokument Google - a tylko
            // Dokument da się dodać do NotebookLM jako źródło.
            mimeType = GOOGLE_DOC_MIME
        }
        return drive.files().create(metadata, content).setFields("id").execute().id
    }

    /** Adres dokumentu do otwarcia w przeglądarce. */
    fun documentUrl(fileId: String): String = "https://docs.google.com/document/d/$fileId/edit"

    sealed class Result {
        data class Success(val fileId: String, val noteCount: Int) : Result()

        /** Brak zgody na Dysk - wołający ma o nią poprosić. */
        object NoAccess : Result()

        data class Failed(val reason: String) : Result()
    }

    private companion object {
        const val GOOGLE_DOC_MIME = "application/vnd.google-apps.document"
    }
}
