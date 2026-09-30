package dev.jellyflix.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyflix.R
import dev.jellyflix.player.DeviceDiagnostics

/** Shows what the device can decode/display, with a button to copy it. */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember { DeviceDiagnostics.report(ctx, runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diagnostics_title)) },
        text = {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
        },
        dismissButton = {
            TextButton({
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Jellyflix", text))
            }) { Text(stringResource(R.string.diagnostics_copy)) }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.back)) } },
    )
}
