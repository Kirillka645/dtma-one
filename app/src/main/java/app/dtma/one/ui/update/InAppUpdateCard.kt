package app.dtma.one.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dtma.one.R
import app.dtma.one.update.ApkInstallState
import app.dtma.one.update.ApkUpdater
import app.dtma.one.update.AvailableUpdate
import app.dtma.one.update.UpdateNotifier
import kotlinx.coroutines.launch

@Composable
fun InAppUpdateCard(
    update: AvailableUpdate,
    modifier: Modifier = Modifier,
    showDismiss: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val install by ApkUpdater.installState.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.update_banner_title, update.versionName),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.update_banner_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!update.apkUrl.isNullOrBlank()) {
                Text(
                    text = update.apkName ?: update.apkUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = install) {
                is ApkInstallState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { s.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.update_downloading,
                            (s.progress * 100).toInt().coerceIn(0, 100),
                        ),
                    )
                }
                is ApkInstallState.Installing -> {
                    Text(stringResource(R.string.update_installing))
                }
                is ApkInstallState.Ready -> {
                    Text("APK готов: ${s.path.substringAfterLast('/')}")
                    Button(
                        onClick = { ApkUpdater.installExisting(context, s.path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.update_install))
                    }
                }
                is ApkInstallState.Error -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> Unit
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canDownload = update.apkUrl != null &&
                    install !is ApkInstallState.Downloading &&
                    install !is ApkInstallState.Installing
                Button(
                    onClick = {
                        if (update.apkUrl == null) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)),
                            )
                        } else {
                            scope.launch {
                                if (install is ApkInstallState.Ready) {
                                    ApkUpdater.installExisting(
                                        context,
                                        (install as ApkInstallState.Ready).path,
                                    )
                                } else {
                                    UpdateNotifier.downloadAndInstall(context)
                                }
                            }
                        }
                    },
                    enabled = canDownload || update.apkUrl == null,
                ) {
                    Text(
                        if (update.apkUrl == null) {
                            stringResource(R.string.update_banner_open)
                        } else {
                            stringResource(R.string.update_download_install)
                        },
                    )
                }
                if (showDismiss) {
                    TextButton(
                        onClick = {
                            scope.launch { UpdateNotifier.dismiss(context) }
                        },
                    ) {
                        Text(stringResource(R.string.update_banner_dismiss))
                    }
                }
            }
        }
    }
}
