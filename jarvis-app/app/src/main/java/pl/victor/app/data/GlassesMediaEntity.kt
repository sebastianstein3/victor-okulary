package pl.victor.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Jeden plik z okularów - zapamiętany w telefonie.
 *
 * ## Po co w ogóle baza
 * Bo dotąd galeria żyła tylko tak długo, jak otwarty był ekran. Po wyjściu
 * ginęła lista, ginęły miniatury, a każde kolejne wejście oznaczało ponowne
 * podniesienie sieci i pobranie tych samych megabajtów - przez łącze, które
 * jest jedyną drogą także dla wszystkiego innego.
 *
 * Przy stu kilkudziesięciu plikach to nie jest wygoda, tylko różnica między
 * galerią a spisem, który trzeba odtwarzać od zera.
 *
 * ## Nazwa jako klucz
 * Okulary nie podają identyfikatorów - tylko nazwy plików, i to one są w tym
 * świecie tożsamością pliku. Nazwa jako klucz główny daje przy okazji to,
 * czego chcemy: ponowny import tego samego pliku aktualizuje wiersz, zamiast
 * tworzyć duplikat.
 */
@Entity(tableName = "glasses_media")
data class GlassesMediaEntity(

    /** Nazwa pliku na okularach - ich jedyny identyfikator. */
    @PrimaryKey
    @ColumnInfo(name = "name")
    val name: String,

    /** PHOTO, VIDEO, AUDIO albo OTHER - nazwa z `MediaLibrary.Kind`. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /** Kiedy pierwszy raz zobaczyliśmy ten plik na okularach. */
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAtMs: Long,

    /**
     * Ścieżka miniatury na dysku telefonu albo `null`, gdy jeszcze jej nie ma.
     *
     * Na dysku, nie w bazie: miniatura to kilkadziesiąt kilobajtów, a baza
     * czytana jest przy każdym otwarciu ekranu. Sto kilkadziesiąt obrazków
     * wewnątrz wierszy zamieniłoby odczyt listy w odczyt kilkunastu megabajtów.
     */
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    /** Kiedy plik trafił do galerii telefonu; `null` gdy jeszcze nie trafił. */
    @ColumnInfo(name = "saved_to_phone_at")
    val savedToPhoneAtMs: Long? = null,

    /**
     * Czy plik był na okularach przy ostatnim wczytaniu listy.
     *
     * Nie kasujemy wierszy dla plików, których już nie ma: to jest właśnie ta
     * trwałość, o którą chodzi. Zdjęcie zapisane w telefonie i usunięte z
     * okularów ma nadal być widoczne w galerii - z miniaturą i datą.
     */
    @ColumnInfo(name = "still_on_glasses")
    val stillOnGlasses: Boolean = true
)
