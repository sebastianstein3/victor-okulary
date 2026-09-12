package pl.victor.app.actions

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rozwiązuje nazwy kontaktów na numery telefonów i emaile.
 *
 * Wymaga READ_CONTACTS permission. W trybie SAFE nie jest potrzebny
 * (bo system sam rozwiązuje przez dialer/SMS).
 *
 * Algorytm:
 * 1. Normalizuj nazwę (lowercase, bez polskich znaków opcjonalnie)
 * 2. Szukaj w ContactsContract.Phone
 * 3. Dopasuj: substring / startsWith / fuzzy
 */
class ContactResolver(private val context: Context) {

    private val tag = "ContactResolver"

    /**
     * Szuka kontaktu o podanej nazwie, zwraca numer telefonu lub null.
     */
    suspend fun findPhoneNumber(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val normalized = normalize(name)

            // Najpierw szukaj po displayName
            val byName = searchByName(normalized)
            if (byName != null) return@withContext byName

            // Fallback: sprawdź czy to może numer telefonu już
            if (isPhoneNumber(name)) return@withContext name

            null
        } catch (e: SecurityException) {
            Log.w(tag, "READ_CONTACTS permission not granted")
            null
        } catch (e: Exception) {
            Log.e(tag, "Contact lookup failed", e)
            null
        }
    }

    /**
     * Szuka emaila po nazwie kontaktu.
     */
    suspend fun findEmail(name: String): String? = withContext(Dispatchers.IO) {
        try {
            val normalized = normalize(name)
            val contactId = findContactId(normalized) ?: return@withContext null

            val projection = arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS)
            val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                    "${ContactsContract.Data.MIMETYPE} = ?"
            val args = arrayOf(
                contactId.toString(),
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            )

            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection, selection, args, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(tag, "READ_CONTACTS permission not granted")
            null
        } catch (e: Exception) {
            Log.e(tag, "Email lookup failed", e)
            null
        }
    }

    /**
     * Szuka numeru po nazwie, zwraca parę (contactId, phone).
     */
    private fun searchByName(normalizedName: String): String? {
        val contactId = findContactId(normalizedName) ?: return null

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ?"
        val args = arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
        )

        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    /**
     * Szuka contactId po displayName.
     *
     * ## Trzy rzeczy naprawione naraz, bo wszystkie siedziały w tych kilkunastu
     * wierszach
     *
     * 1. OGONKI. Było: `DISPLAY_NAME_PRIMARY LIKE %name%`, gdzie `name`
     *    przychodziło już ZNORMALIZOWANE (bez polskich znaków), a kolumna z
     *    nazwami ogonki ma. `LIKE '%lukasz%'` nie trafia w „Łukasz", więc żaden
     *    kontakt z polskim imieniem nie dawał się znaleźć - a to jest aplikacja
     *    po polsku. Filtrowanie przeniosłem więc z SQL do Kotlina, gdzie obie
     *    strony da się znormalizować tak samo.
     *
     * 2. TYLKO PIERWSZY WIERSZ. Było `if (cursor.moveToFirst())` - jeden wiersz
     *    i koniec. Gdy zapytanie pasowało do kilku kontaktów, braliśmy ten,
     *    który akurat wypadł pierwszy; gdy pierwszy nie przechodził testu
     *    dopasowania, funkcja oddawała `null`, choć dobry kontakt leżał niżej.
     *
     * 3. WYBÓR, A NIE PIERWSZE TRAFIENIE. Punktacja i odmowa przy remisie -
     *    patrz [ContactMatch].
     *
     * Kosztem jest odczyt całej książki adresowej zamiast zapytania z `LIKE`.
     * To dwie kolumny i jedno wywołanie na akcję, na wątku wejścia-wyjścia -
     * przy tysiącu kontaktów nadal ułamek tego, co kosztuje samo połączenie.
     */
    private fun findContactId(name: String): Long? {
        if (name.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )

        val candidates = mutableListOf<Pair<Long, String>>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(1) ?: continue
                if (displayName.isBlank()) continue
                candidates.add(cursor.getLong(0) to displayName)
            }
        }

        val id = ContactMatch.best(name, candidates)
        if (id == null) {
            // Rozróżnienie jest istotne: „nie mam takiego kontaktu" naprawia się
            // inaczej niż „mam dwóch i nie wiem którego".
            Log.i(tag, "Nie wybrałem kontaktu dla \"$name\" spośród ${candidates.size}")
        }
        return id
    }

    /**
     * Normalizacja żyje teraz w [ContactMatch], żeby obie strony porównania
     * przechodziły PRZEZ TĘ SAMĄ funkcję. Rozjazd między nimi był przyczyną
     * usterki z ogonkami.
     */
    private fun normalize(s: String): String = ContactMatch.normalize(s)

    /**
     * Czy `s` wygląda już jak numer telefonu (a nie nazwa kontaktu).
     * Publiczne - wywołujący (np. orkiestrator) musi to sprawdzić, zanim
     * w ogóle spróbuje szukać w kontaktach.
     */
    fun isPhoneNumber(s: String): Boolean {
        // Prosty check: więcej niż 5 cyfr
        val digits = s.filter { it.isDigit() }
        return digits.length >= 5
    }
}
