package app.dtma.one.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import app.dtma.one.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Download GitHub release APK into cache and launch the system package installer.
 * Same pattern as self-updating proxy clients (KupuProxy-style).
 */
object ApkUpdater {
    private const val TAG = "DtmaApkUpdate"

    private val _installState = MutableStateFlow<ApkInstallState>(ApkInstallState.Idle)
    val installState: StateFlow<ApkInstallState> = _installState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // no overall limit for large APK
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    suspend fun downloadAndInstall(context: Context, update: AvailableUpdate): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = update.apkUrl?.takeIf { it.isNotBlank() }
                    ?: error("В релизе нет APK — откройте страницу GitHub")
                val app = context.applicationContext
                val dir = File(app.cacheDir, "updates")
                dir.mkdirs()
                val name = update.apkName?.takeIf { it.endsWith(".apk", true) }
                    ?: "dtma-one-${update.versionName}.apk"
                val out = File(dir, name)

                _installState.value = ApkInstallState.Downloading(0f, 0, update.apkSizeBytes)
                Log.i(TAG, "download $url → ${out.absolutePath}")

                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "DTMA-One/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})",
                    )
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: update.apkSizeBytes
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                received += n
                                val p = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
                                _installState.value = ApkInstallState.Downloading(p, received, total)
                            }
                            output.flush()
                        }
                    }
                }

                if (!out.exists() || out.length() < 10_000) {
                    error("APK слишком маленький или не скачался")
                }
                // crude zip magic
                RandomAccessFile(out, "r").use { raf ->
                    val b0 = raf.read()
                    val b1 = raf.read()
                    if (b0 != 0x50 || b1 != 0x4B) error("Файл не похож на APK (ZIP)")
                }

                _installState.value = ApkInstallState.Ready(out.absolutePath)
                withContext(Dispatchers.Main) {
                    if (!canInstallPackages(app)) {
                        openInstallPermissionSettings(app)
                        _installState.value = ApkInstallState.Error(
                            "Разрешите установку из этого приложения, затем нажмите «Установить» снова",
                        )
                        return@withContext
                    }
                    launchInstaller(app, out)
                }
                Unit
            }.onFailure { e ->
                Log.e(TAG, "download/install failed", e)
                _installState.value = ApkInstallState.Error(e.message ?: "download failed")
            }
        }

    fun installExisting(context: Context, path: String) {
        val file = File(path)
        if (!file.exists()) {
            _installState.value = ApkInstallState.Error("APK не найден")
            return
        }
        if (!canInstallPackages(context)) {
            openInstallPermissionSettings(context)
            _installState.value = ApkInstallState.Error(
                "Разрешите установку из этого приложения, затем снова «Установить»",
            )
            return
        }
        _installState.value = ApkInstallState.Installing
        launchInstaller(context.applicationContext, file)
    }

    private fun launchInstaller(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _installState.value = ApkInstallState.Installing
            Log.i(TAG, "installer launched for ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "installer launch failed", e)
            _installState.value = ApkInstallState.Error(e.message ?: "install failed")
        }
    }

    fun reset() {
        _installState.value = ApkInstallState.Idle
    }
}
