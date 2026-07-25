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
 * Picks a matching .apk asset for in-app install.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val apiUrl: String = DEFAULT_API_URL,
    private val localVersionName: String = BuildConfig.VERSION_NAME,
    private val applicationId: String = BuildConfig.APPLICATION_ID,
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
                parseLatest(body, localVersionName, applicationId)
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
        internal fun parseLatest(
            json: String,
            localVersionName: String,
            applicationId: String = "app.dtma.one.debug",
        ): AvailableUpdate? {
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
            val asset = pickApkAsset(json, applicationId)
            return AvailableUpdate(
                tag = tag,
                versionName = remoteName,
                releaseUrl = url,
                notes = notes,
                apkUrl = asset?.url,
                apkName = asset?.name,
                apkSizeBytes = asset?.size ?: 0L,
            )
        }

        data class ApkAsset(val name: String, val url: String, val size: Long)

        /**
         * Prefer debug APK when running debug package; otherwise non-debug.
         */
        fun pickApkAsset(json: String, applicationId: String): ApkAsset? {
            val wantDebug = applicationId.endsWith(".debug")
            // Pair browser_download_url with nearby name when possible
            val urls = Regex(""""browser_download_url"\s*:\s*"(https://[^"]+\.apk)"""")
                .findAll(json)
                .map { it.groupValues[1] }
                .toList()
            if (urls.isEmpty()) return null

            val names = Regex(""""name"\s*:\s*"([^"]+\.apk)"""")
                .findAll(json)
                .map { it.groupValues[1] }
                .toList()

            fun score(url: String, nameHint: String?): Int {
                val n = (nameHint ?: url).lowercase()
                var s = 0
                if (n.endsWith(".apk")) s += 1
                if (wantDebug && n.contains("debug")) s += 10
                if (!wantDebug && !n.contains("debug")) s += 10
                if (n.contains("dtma")) s += 2
                return s
            }

            var best: ApkAsset? = null
            var bestScore = -1
            urls.forEachIndexed { i, u ->
                val name = names.getOrNull(i) ?: u.substringAfterLast('/')
                val sc = score(u, name)
                if (sc > bestScore) {
                    bestScore = sc
                    // size optional
                    val size = Regex(""""size"\s*:\s*(\d+)""")
                        .findAll(json)
                        .map { it.groupValues[1].toLongOrNull() ?: 0L }
                        .toList()
                        .getOrNull(i) ?: 0L
                    best = ApkAsset(name, u, size)
                }
            }
            return best
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
