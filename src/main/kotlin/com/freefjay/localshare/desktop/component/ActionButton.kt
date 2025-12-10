package com.freefjay.localshare.desktop.component

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.freefjay.localshare.desktop.logger
import io.ktor.util.logging.*
import kotlinx.coroutines.launch

@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    onClick: suspend () -> Unit,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(true)
    }
    var errMsg by remember {
        mutableStateOf<String?>(null)
    }
    Button(
        modifier = modifier,
        enabled = enabled,
        onClick = {
            coroutineScope.launch {
                try {
                    enabled = false
                    errMsg = null
                    onClick()
                } catch (e : Exception) {
                    logger.error(e)
                    errMsg = e.message
                } finally {
                    enabled = true
                }
            }
        }
    ) {
        content()
    }
    if (errMsg != null) {
        Text(errMsg ?: "", color = Color.Red)
    }
}