package com.kuma.motointercom

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal fun diagnosticExportChooser(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-files", file)
    return diagnosticExportChooser(context, uri)
}

internal fun diagnosticExportChooser(context: Context, uri: Uri): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "MotoCom diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, context.getString(R.string.logs_export_chooser))
}
