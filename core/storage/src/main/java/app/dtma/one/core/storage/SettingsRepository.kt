package app.dtma.one.core.storage

import android.content.Context
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
    /** User-provided SOCKS5 (not DTMA infrastructure). Empty = local protect SOCKS5. */
    val upstreamSocksHost: String = "",
    val upstreamSocksPort: Int = 1080,
    val upstreamSocksEnabled: Boolean = false,
    /** Check GitHub Releases for new versions (notification + in-app banner). */
    val updateCheckEnabled: Boolean = true,
    val lastUpdateCheckMs: Long = 0L,
    val dismissedUpdateTag: String = "",
    val lastKnownUpdateTag: String = "",
    val lastKnownUpdateUrl: String = "",
    /**
     * For Telegram DC IPs: try alternate underlying network (e.g. cellular vs Wi‑Fi)
     * before default path. No remote proxy / SOCKS5 required when only one path is blocked.
     */
    val telegramMultipath: Boolean = true,
    /** Optional MTProto proxy for Telegram client deep-link (not used by DTMA tunnel). */
    val mtprotoHost: String = "",
    val mtprotoPort: Int = 443,
    val mtprotoSecret: String = "",
) {
    fun toRaceConfig(): RaceConfig = RaceConfig(
        width = raceWidth,
        secondStartDelayMs = secondDelayMs,
        thirdStartDelayMs = thirdDelayMs,
        batterySaver = batterySaver,
        halfLifeHours = rvecHalfLifeHours,
    )

    fun hasUpstreamSocks(): Boolean =
        upstreamSocksEnabled && upstreamSocksHost.isNotBlank() && upstreamSocksPort in 1..65535
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
        val upSocksHost = stringPreferencesKey("up_socks_host")
        val upSocksPort = intPreferencesKey("up_socks_port")
        val upSocksEn = booleanPreferencesKey("up_socks_en")
        val updateCheck = booleanPreferencesKey("update_check")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val dismissedUpdate = stringPreferencesKey("dismissed_update")
        val knownUpdateTag = stringPreferencesKey("known_update_tag")
        val knownUpdateUrl = stringPreferencesKey("known_update_url")
        val tgMultipath = booleanPreferencesKey("tg_multipath")
        val mtprotoHost = stringPreferencesKey("mtproto_host")
        val mtprotoPort = intPreferencesKey("mtproto_port")
        val mtprotoSecret = stringPreferencesKey("mtproto_secret")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        read(p)
    }

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(read(prefs))
            write(prefs, next)
        }
    }

    suspend fun markUpdateChecked(nowMs: Long) {
        context.dataStore.edit { it[Keys.lastUpdateCheck] = nowMs }
    }

    suspend fun setKnownUpdate(tag: String, url: String) {
        context.dataStore.edit {
            it[Keys.knownUpdateTag] = tag
            it[Keys.knownUpdateUrl] = url
        }
    }

    suspend fun clearKnownUpdate() {
        context.dataStore.edit {
            it[Keys.knownUpdateTag] = ""
            it[Keys.knownUpdateUrl] = ""
        }
    }

    suspend fun dismissUpdate(tag: String) {
        context.dataStore.edit { it[Keys.dismissedUpdate] = tag }
    }

    private fun read(p: androidx.datastore.preferences.core.Preferences): UserSettings =
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
            upstreamSocksHost = p[Keys.upSocksHost].orEmpty(),
            upstreamSocksPort = p[Keys.upSocksPort] ?: 1080,
            upstreamSocksEnabled = p[Keys.upSocksEn] ?: false,
            updateCheckEnabled = p[Keys.updateCheck] ?: true,
            lastUpdateCheckMs = p[Keys.lastUpdateCheck] ?: 0L,
            dismissedUpdateTag = p[Keys.dismissedUpdate].orEmpty(),
            lastKnownUpdateTag = p[Keys.knownUpdateTag].orEmpty(),
            lastKnownUpdateUrl = p[Keys.knownUpdateUrl].orEmpty(),
            telegramMultipath = p[Keys.tgMultipath] ?: true,
            mtprotoHost = p[Keys.mtprotoHost].orEmpty(),
            mtprotoPort = p[Keys.mtprotoPort] ?: 443,
            mtprotoSecret = p[Keys.mtprotoSecret].orEmpty(),
        )

    private fun write(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        next: UserSettings,
    ) {
        prefs[Keys.raceWidth] = next.raceWidth.coerceIn(1, 3)
        prefs[Keys.secondDelay] = next.secondDelayMs
        prefs[Keys.thirdDelay] = next.thirdDelayMs
        prefs[Keys.batterySaver] = next.batterySaver
        prefs[Keys.halfLife] = next.rvecHalfLifeHours.toString()
        prefs[Keys.localLogs] = next.localLogsEnabled
        prefs[Keys.rememberUrl] = next.rememberTestUrl
        prefs[Keys.lastUrl] = if (next.rememberTestUrl) next.lastTestUrl else ""
        prefs[Keys.upSocksHost] = next.upstreamSocksHost.trim()
        prefs[Keys.upSocksPort] = next.upstreamSocksPort.coerceIn(1, 65535)
        prefs[Keys.upSocksEn] = next.upstreamSocksEnabled
        prefs[Keys.updateCheck] = next.updateCheckEnabled
        prefs[Keys.lastUpdateCheck] = next.lastUpdateCheckMs
        prefs[Keys.dismissedUpdate] = next.dismissedUpdateTag
        prefs[Keys.knownUpdateTag] = next.lastKnownUpdateTag
        prefs[Keys.knownUpdateUrl] = next.lastKnownUpdateUrl
        prefs[Keys.tgMultipath] = next.telegramMultipath
        prefs[Keys.mtprotoHost] = next.mtprotoHost.trim()
        prefs[Keys.mtprotoPort] = next.mtprotoPort.coerceIn(1, 65535)
        prefs[Keys.mtprotoSecret] = next.mtprotoSecret.trim()
    }
}
