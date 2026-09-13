package pl.victor.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Galeria plików z okularów - OSOBNA baza od historii rozmów.
 *
 * ## Czemu osobna, a nie nowa tabela w [AppDatabase]
 * Bo dodanie tabeli do istniejącej bazy wymaga podbicia wersji, a tam stoi
 * `fallbackToDestructiveMigration()` - przy zmianie wersji skasowałoby to CAŁĄ
 * HISTORIĘ ROZMÓW użytkownika. Napisanie prawdziwej migracji byłoby możliwe,
 * ale Room jest tu w wersji 2.8, czyli w gałęzi przechodzącej na sterowniki
 * KMP, gdzie sygnatura `Migration.migrate` się zmienia - i nie udało mi się
 * potwierdzić z dokumentacji, który wariant obowiązuje.
 *
 * Osobna baza w wersji 1 nie potrzebuje żadnej migracji, więc pytanie znika.
 * Kosztuje drugą instancję na kilkaset wierszy - cena bez znaczenia, a pewność
 * jest tu warta więcej niż elegancja jednego pliku.
 */
@Database(
    entities = [GlassesMediaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun glassesMediaDao(): GlassesMediaDao

    companion object {
        @Volatile
        private var instance: MediaDatabase? = null

        fun getInstance(context: Context): MediaDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MediaDatabase::class.java,
                    "victor_media.db"
                ).build().also { instance = it }
            }
        }
    }
}
