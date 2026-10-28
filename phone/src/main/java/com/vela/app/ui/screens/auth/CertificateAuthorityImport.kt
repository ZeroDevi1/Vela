package com.vela.app.ui.screens.auth

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vela.data.network.ServerTrust
import com.vela.data.network.TrustedAuthority
import com.vela.shared.R
import java.security.cert.X509Certificate

/** 返回一个启动函数：选择 CA 证书文件 → 展示指纹核对 → 用户确认后导入。 */
@Composable
fun rememberCertificateAuthorityImport(onImported: (TrustedAuthority) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<X509Certificate?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            Toast.makeText(context, context.getString(R.string.auth_ca_read_failed), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        ServerTrust.parseAuthority(bytes)
            .onSuccess { pending = it }
            .onFailure { error ->
                Toast.makeText(context, error.message ?: context.getString(R.string.auth_ca_read_failed), Toast.LENGTH_LONG).show()
            }
    }
    pending?.let { certificate ->
        val info = remember(certificate) { ServerTrust.describeAuthority(certificate) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.auth_ca_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.auth_ca_confirm_message))
                    Text(info.subject, style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256", style = MaterialTheme.typography.labelMedium)
                    Text(info.sha256, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text("SHA-1", style = MaterialTheme.typography.labelMedium)
                    Text(info.sha1, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text(stringResource(R.string.auth_ca_valid_until, info.notAfter), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onImported(ServerTrust.importAuthority(certificate))
                }) { Text(stringResource(R.string.auth_certificate_trust)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
    return remember(launcher) {
        { launcher.launch(arrayOf("application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert", "application/octet-stream", "*/*")) }
    }
}
