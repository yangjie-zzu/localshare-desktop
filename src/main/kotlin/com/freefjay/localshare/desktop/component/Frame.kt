package com.freefjay.localshare.desktop.component

import androidx.compose.runtime.Composable

@Composable
fun Frame(content: (@Composable () -> Unit)? = null) {
    content?.invoke()
}