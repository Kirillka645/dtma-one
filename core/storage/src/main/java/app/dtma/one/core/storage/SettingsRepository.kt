package app.dtma.one.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.dtma.one.core.model.PaerLimits
import app.dtma.one.core.model.RaceConfig
import app.dtma.one.core.model.ScoringWeights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "dtma_settings")

data class UserSettings(
    val raceWidth: Int = PaerLimits.DEFAULT_SIMULTANEOUS_CANDIDATES,
    val secondDelayMs: Long = 250L,
    val thirdDelayMs: Long = 750L,
    val batterySaver: Boolean = false,
    val rvecHalfLifeHours: Double = ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS,
    val localLogsEnabled: Boolean = false,
    val rememberTestUrl: Boolean = false,
    val lastTestUrl: String = "https://example.com/",
) {
    fun toRaceConfig(): RaceConfig = RaceConfig(
        width = raceWidth,
        secondStartDelayMs = secondDelayMs,
        thirdStartDelayMs = thirdDelayMs,
        batterySaver = batterySaver,
        halfLifeHours = rvecHalfLifeHours,
    )
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val raceWidth = intPreferencesKey("race_width")
        val secondDelay = longPreferencesKey("second_delay")
        val thirdDelay = longPreferencesKey("third_delay")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val halfLife = stringPreferencesKey("half_life")
        val localLogs = booleanPreferencesKey("local_logs")
        val rememberUrl = booleanPreferencesKey("remember_url")
        val lastUrl = stringPreferencesKey("last_url")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            raceWidth = p[Keys.raceWidth] ?: PaerLimits.DEFAULT_SIMULTANEOUS_CANDIDATES,
            secondDelayMs = p[Keys.secondDelay] ?: 250L,
            thirdDelayMs = p[Keys.thirdDelay] ?: 750L,
            batterySaver = p[Keys.batterySaver] ?: false,
            rvecHalfLifeHours = p[Keys.halfLife]?.toDoubleOrNull()
                ?: ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS,
            localLogsEnabled = p[Keys.localLogs] ?: false,
            rememberTestUrl = p[Keys.rememberUrl] ?: false,
            lastTestUrl = p[Keys.lastUrl] ?: "https://example.com/",
        )
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            val current = UserSettings(
                raceWidth = prefs[Keys.raceWidth] ?: PaerLimits.DEFAULT_SIMULTANEOUS_CANDIDATES,
                secondDelayMs = prefs[Keys.secondDelay] ?: 250L,
                thirdDelayMs = prefs[Keys.thirdDelay] ?: 750L,
                batterySaver = prefs[Keys.batterySaver] ?: false,
                rvecHalfLifeHours = prefs[Keys.halfLife]?.toDoubleOrNull()
                    ?: ScoringWeights.DEFAULT_CACHE_HALF_LIFE_HOURS,
                localLogsEnabled = prefs[Keys.localLogs] ?: false,
                rememberTestUrl = prefs[Keys.rememberUrl] ?: false,
                lastTestUrl = prefs[Keys.lastUrl] ?: "https://example.com/",
            )
            val next = transform(current)
            prefs[Keys.raceWidth] = next.raceWidth.coerceIn(1, 3)
            prefs[Keys.secondDelay] = next.secondDelayMs
            prefs[Keys.thirdDelay] = next.thirdDelayMs
            prefs[Keys.batterySaver] = next.batterySaver
            prefs[Keys.halfLife] = next.rvecHalfLifeHours.toString()
            prefs[Keys.localLogs] = next.localLogsEnabled
            prefs[Keys.rememberUrl] = next.rememberTestUrl
            prefs[Keys.lastUrl] = if (next.rememberTestUrl) next.lastTestUrl else ""
        }
    }
}
