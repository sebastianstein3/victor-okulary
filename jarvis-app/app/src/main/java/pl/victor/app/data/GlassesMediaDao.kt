package pl.victor.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GlassesMediaDao {

    /** Wszystko, co kiedykolwiek widzieliśmy - najnowsze na górze. */
    @Query("SELECT * FROM glasses_media ORDER BY first_seen_at DESC, name DESC")
    suspend fun all(): List<GlassesMediaEntity>

    @Query("SELECT * FROM glasses_media WHERE name = :name")
    suspend fun byName(name: String): GlassesMediaEntity?

    /**
     * Dopisuje plik, ale NIE nadpisuje tego, co już wiemy.
     *
     * `IGNORE`, nie `REPLACE`: wiersz niesie ścieżkę miniatury i datę zapisu w
     * telefonie, a ponowne wczytanie listy z okularów tych rzeczy nie zna.
     * Nadpisanie skasowałoby miniaturę przy każdym odświeżeniu.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(items: List<GlassesMediaEntity>)

    @Query("UPDATE glasses_media SET thumbnail_path = :path WHERE name = :name")
    suspend fun setThumbnail(name: String, path: String)

    @Query("UPDATE glasses_media SET saved_to_phone_at = :atMs WHERE name = :name")
    suspend fun markSavedToPhone(name: String, atMs: Long)

    /** Po wczytaniu listy: oznacz, których plików już na okularach nie ma. */
    @Query("UPDATE glasses_media SET still_on_glasses = 0 WHERE name NOT IN (:present)")
    suspend fun markMissing(present: List<String>)

    /** Gdy okulary zgłosiły pustą pamięć - wszystko z nich zniknęło. */
    @Query("UPDATE glasses_media SET still_on_glasses = 0")
    suspend fun markAllMissing()

    @Query("SELECT COUNT(*) FROM glasses_media WHERE saved_to_phone_at IS NOT NULL")
    suspend fun savedCount(): Int
}
