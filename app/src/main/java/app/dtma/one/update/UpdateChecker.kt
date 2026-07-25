package app.dtma.one.update

import android.util.Log
import app.dtma.one.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest GitHub release and compares with [BuildConfig.VERSION_NAME].
 * No analytics; one GET to public GitHub API only.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val apiUrl: String = DEFAULT_API_URL,
    private val localVersionName: String = BuildConfig.VERSION_NAME,
) {
    suspend fun check(): Result<AvailableUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DTMA-One/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("Empty response")
                parseLatest(body, localVersionName)
            }
        }.onFailure { e ->
            Log.w(TAG, "update check failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DtmaUpdate"
        const val DEFAULT_API_URL =
            "https://api.github.com/repos/Kirillka645/dtma-one/releases/latest"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()

        /**
         * Lightweight JSON field extraction (no org.json — works on JVM unit tests).
         */
        internal fun parseLatest(json: String, localVersionName: String): AvailableUpdate? {
            val tag = jsonString(json, "tag_name")?.takeIf { it.isNotBlank() }
                ?: error("No tag_name")
            if (jsonBool(json, "draft") || jsonBool(json, "prerelease")) {
                return null
            }
            val remoteName = VersionCompare.normalize(tag)
            if (!VersionCompare.isNewer(remoteName, localVersionName)) {
                return null
            }
            val url = jsonString(json, "html_url")?.takeIf { it.isNotBlank() }
                ?: "https://github.com/Kirillka645/dtma-one/releases/tag/$tag"
            val notes = jsonString(json, "body").orEmpty().take(800)
            return AvailableUpdate(
                tag = tag,
                versionName = remoteName,
                releaseUrl = url,
                notes = notes,
            )
        }

        private fun jsonString(json: String, key: String): String? {
            val re = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""")
            val m = re.find(json) ?: return null
            return m.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        private fun jsonBool(json: String, key: String): Boolean {
            val re = Regex(""""$key"\s*:\s*(true|false)""")
            return re.find(json)?.groupValues?.get(1) == "true"
        }
    }
}
