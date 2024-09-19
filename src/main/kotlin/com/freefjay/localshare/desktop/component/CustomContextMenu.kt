package com.freefjay.localshare.desktop.component

import androidx.compose.foundation.*
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomContextMenu(
    items: (textManager: TextContextMenu.TextManager?) -> List<ContextMenuItem>,
    content: @Composable () -> Unit
) {
    ContextMenuArea(
        items = {
            items(null)
        }
    ) {
        val textMenu = LocalTextContextMenu.current
        CompositionLocalProvider(
            LocalTextContextMenu provides object : TextContextMenu {
                @Composable
                override fun Area(
                    textManager: TextContextMenu.TextManager,
                    state: ContextMenuState,
                    content: @Composable () -> Unit
                ) {
                    ContextMenuDataProvider({
                        items(textManager)
                    }) {
                        textMenu.Area(textManager, state, content)
                    }
                }
            }
        ) {
            content()
        }
    }
}