package app.dtma.one.bypass

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object WarpInstaller {
    private const val TAG = "DtmaWarp"
    const val WIREGUARD_PKG = "com.wireguard.android"
    const val CLOUDFLARE_WARP_PKG = "com.cloudflare.onedotonedotonedotone"

    fun writeConf(context: Context, confText: String): File {
        val dir = File(context.cacheDir, "warp")
        dir.mkdirs()
        val f = File(dir, "dtma-warp.conf")
        f.writeText(confText)
        return f
    }

    fun confUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Try import into WireGuard; fall back to share sheet. */
    fun openInWireGuard(context: Context, confFile: File): String {
        val uri = confUri(context, confFile)
        // Prefer WireGuard package
        val wg = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            setPackage(WIREGUARD_PKG)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(wg)
            "Откройте WireGuard → примите импорт → включите туннель."
        } catch (_: ActivityNotFoundException) {
            try {
                val any = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/plain")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(any, "Импорт WireGuard conf"))
                "WireGuard не найден. Установите WireGuard, затем снова «Открыть conf»."
            } catch (e: Exception) {
                Log.w(TAG, "open conf: ${e.message}")
                "Сохранено: ${confFile.absolutePath}. Импортируйте вручную в WireGuard."
            }
        }
    }

    fun openPlayStore(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }

    fun openCloudflareWarpApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(CLOUDFLARE_WARP_PKG)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        } else {
            openPlayStore(context, CLOUDFLARE_WARP_PKG)
        }
    }
}
