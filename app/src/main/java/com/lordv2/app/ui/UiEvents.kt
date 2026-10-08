package com.lordv2.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class UiDialog(
    val title: String,
    val message: String,
    val primary: String = "OK",
    val onPrimary: (() -> Unit)? = null,
    val secondary: String? = null,
    val onSecondary: (() -> Unit)? = null,
)

object UiEvents {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val dialog = MutableStateFlow<UiDialog?>(null)
    val openAdd = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun toast(m: String) { messages.tryEmit(m) }
    fun show(d: UiDialog) { dialog.value = d }
    fun dismiss() { dialog.value = null }
    fun invalidConfig() = show(UiDialog("Invalid Configuration", "Please check the configuration and try again."))
    fun requestAdd() { openAdd.tryEmit(Unit) }
}

interface AppActions {
    fun connect(id: String)
    fun disconnect()
    fun toggle()
}

val LocalActions = staticCompositionLocalOf<AppActions> {
    object : AppActions {
        override fun connect(id: String) {}
        override fun disconnect() {}
        override fun toggle() {}
    }
}
