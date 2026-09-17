package app.fediferry.data.db

import androidx.room.TypeConverter
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Status
import app.fediferry.data.model.Visibility

class Converters {
    @TypeConverter fun visibilityToString(v: Visibility): String = v.name
    @TypeConverter fun stringToVisibility(v: String): Visibility = Visibility.valueOf(v)

    @TypeConverter fun statusToString(v: Status): String = v.name
    @TypeConverter fun stringToStatus(v: String): Status = Status.valueOf(v)

    @TypeConverter fun altModeToString(v: AltTextMode): String = v.name
    @TypeConverter fun stringToAltMode(v: String): AltTextMode = AltTextMode.valueOf(v)
}
