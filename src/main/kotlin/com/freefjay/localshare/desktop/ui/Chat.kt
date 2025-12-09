package com.freefjay.localshare.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freefjay.localshare.desktop.OnEvent
import com.freefjay.localshare.desktop.component.CustomContextMenu
import com.freefjay.localshare.desktop.deviceMessageEvent
import com.freefjay.localshare.desktop.logger
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.model.DeviceMessage
import com.freefjay.localshare.desktop.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chat(
    modifier: Modifier = Modifier,
    activeDevice: Device?,
) {

    val currentCoroutineScope = rememberCoroutineScope()
    val deviceMessages = remember {
        mutableStateListOf<DeviceMessage>()
    }
    val deviceMessageListState = rememberLazyListState()
    var downloadProgressMap by remember {
        mutableStateOf<Map<Long?, FileProgress?>?>(null)
    }

    suspend fun requestMessages(deviceId: Long?, scrollToBottom: Boolean = true) {
        val oldSize = deviceMessages.size
        deviceMessages.clear()
        if (deviceId == null) {
            return
        }
        deviceMessages.addAll(queryList("select * from device_message where device_id = $deviceId"))
        if (scrollToBottom) {
            val size = deviceMessages.size
            if (size > oldSize) {
                currentCoroutineScope.launch {
                    delay(50)
                    deviceMessageListState.scrollToItem(size)
                }
            }
        }
    }

    LaunchedEffect(activeDevice?.id) {
        requestMessages(activeDevice?.id)
    }

    OnEvent(deviceMessageEvent) {
        if (it.deviceId == activeDevice?.id) {
            currentCoroutineScope.launch {
                requestMessages(activeDevice?.id)
            }
        }
    }

    if (deviceMessages.any { it.type == "receive" && it.downloadSuccess != true }) {
        logger.info("下载进度处理")
        OnTimer(block = {
            downloadProgressMap = mutableMapOf<Long?, FileProgress?>().also {
                it.putAll(com.freefjay.localshare.desktop.util.downloadProgressMap)
            }
        })
    }
    Box(
        modifier = modifier
    ) {
        if (activeDevice != null) {
            Column {
                LazyColumn(
                    state = deviceMessageListState,
                    modifier = Modifier.weight(1f).padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        items = deviceMessages,
                        key = { _, item -> item.id.toString() }
                    ) { _, item ->
                        fun deleteItem() {
                            CoroutineScope(Dispatchers.IO).launch {
                                delete<DeviceMessage>(item.id)
                                requestMessages(activeDevice.id, false)
                            }
                        }
                        fun openFile() {
                            (if (item.type == "receive") item.savePath else item.filepath)?.let {
                                logger.info("path: ${it}")
                                Runtime.getRuntime().exec("explorer /e,/select,${it}")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (item.type == "send") Arrangement.End else Arrangement.Start
                        ) {
                            CustomContextMenu(
                                items = {
                                    listOf(
                                        ContextMenuItem("删除") {
                                            deleteItem()
                                        },
                                        ContextMenuItem("打开文件") {
                                            openFile()
                                        }
                                    )
                                }
                            ) {
                                if (item.type == "receive") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(5.dp))
                                            .background(Color.Green).padding(start = 5.dp, top = 0.dp, end = 5.dp, bottom = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        SelectionContainer {
                                            Column(
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                if (item.filename != null) {
                                                    Text(text = item.filename ?: "")
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        val downloadProgress = com.freefjay.localshare.desktop.util.downloadProgressMap.get(item.id)
                                                        Box(modifier = Modifier.size(20.dp)) {
                                                            if (item.filename != null && item.downloadSuccess != true && downloadProgress == null) {
                                                                Image(painter = painterResource("下载(2).svg"), contentDescription = "",
                                                                    modifier = Modifier.clickable {
                                                                        currentCoroutineScope.launch {
                                                                            downloadMessageFile(activeDevice, item)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                            if (item.downloadSuccess == true) {
                                                                Image(painter = painterResource("下载完成(3).svg"), contentDescription = "")
                                                            } else if (downloadProgress != null) {
                                                                item.size?.let { size ->
                                                                    CircularProgressIndicator(
                                                                        progress = downloadProgress.handleSize.toFloat()/size
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        Text(
                                                            text = "${downloadProgress?.let { it ->  "${readableFileSize(it.handleSize)}/" } ?: ""}${readableFileSize(item.size ?: 0)}",
                                                            fontWeight = FontWeight.Light, fontSize = 14.sp
                                                        )
                                                    }
                                                }
                                                if (item.content != null) {
                                                    Text(
                                                        text = item.content ?: ""
                                                    )
                                                }
                                                Text(item.createdTime?.format("yyyy-MM-dd HH:mm:ss E") ?: "",
                                                    fontSize = 13.sp, fontWeight = FontWeight.Light,
                                                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                                            }
                                        }
                                    }
                                }
                                if (item.type == "send") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(5.dp))
                                            .background(Color(141, 242, 242))
                                            .padding(start = 5.dp, top = 0.dp, end = 5.dp, bottom = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        SelectionContainer {
                                            Column(
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                if (item.filename != null) {
                                                    Text(
                                                        text = buildAnnotatedString {
                                                            append(item.filename ?: "")
                                                            if (item.size != null) {
                                                                withStyle(SpanStyle(fontWeight = FontWeight.Light, fontSize = 14.sp)) {
                                                                    append("\n" + readableFileSize(item.size))
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                                if (item.content != null) {
                                                    Text(
                                                        text = item.content ?: ""
                                                    )
                                                }
                                                Text(item.createdTime?.format("yyyy-MM-dd HH:mm:ss E") ?: "",
                                                    fontSize = 13.sp, fontWeight = FontWeight.Light,
                                                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                ChatForm(
                    activeDevice = activeDevice,
                    onSave = {
                        currentCoroutineScope.launch {
                            requestMessages(activeDevice.id)
                        }
                    },
                    onSend = {
                        currentCoroutineScope.launch {
                            requestMessages(activeDevice.id)
                        }
                    }
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("未选中设备")
            }
        }
    }
}