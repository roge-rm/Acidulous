package com.rm.acidulous.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberPermissions(onResult: (Boolean) -> Unit): Permissions {
    val result = rememberUpdatedState(onResult)
    return remember {
        object : Permissions {
            override fun has(name: String) = true
            override fun ask(vararg names: String) = result.value(true)
        }
    }
}
