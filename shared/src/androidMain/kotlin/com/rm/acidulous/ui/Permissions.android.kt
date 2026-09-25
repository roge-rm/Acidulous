package com.rm.acidulous.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        onResult(grants.values.all { it })
    }
    return remember(context, launcher) {
        object : Permissions {
            override fun has(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
            override fun ask(vararg names: String) = launcher.launch(arrayOf(*names))
        }
    }
}
