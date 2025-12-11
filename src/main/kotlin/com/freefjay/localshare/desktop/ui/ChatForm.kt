package com.freefjay.localshare.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.freefjay.localshare.desktop.clientCode
import com.freefjay.localshare.desktop.component.AsyncButton
import com.freefjay.localshare.desktop.httpClient
import com.freefjay.localshare.desktop.logger
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.model.DeviceMessage
import com.freefjay.localshare.desktop.model.DeviceMessageParams
import com.freefjay.localshare.desktop.util.getFileNameAndType
import com.freefjay.localshare.desktop.util.onPasted
import com.freefjay.localshare.desktop.util.readableFileSize
import com.freefjay.localshare.desktop.util.save
import com.google.gson.Gson
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatForm(
    activeDevice: Device,
    onSave: (deviceMessage: DeviceMessage) -> Unit,
    onSend: (deviceMessage: DeviceMessage) -> Unit
) {
    var file by remember {
        mutableStateOf<File?>(null)
    }
    var content by remember {
        mutableStateOf<String?>(null)
    }
    var showFilePicker by remember { mutableStateOf(false) }

    LaunchedEffect(activeDevice.id) {
        file = null
        content = null
        showFilePicker = false
    }
    val fileType = listOf("*")
    FilePicker(show = showFilePicker, fileExtensions = fileType) { platformFile ->
        showFilePicker = false
        platformFile?.path?.let {
            file = File(it)
        }
    }

    suspend fun sendMsg() {
        withContext(Dispatchers.IO) {
            logger.info("开始发送")
            val deviceMessage = DeviceMessage(
                type = "send",
                content = content,
                filepath = file?.absolutePath,
                filename = file?.name,
                size = file?.length(),
                deviceId = activeDevice.id,
                createdTime = Date()
            )
            save(deviceMessage)
            onSave(deviceMessage)
            val response =
                httpClient.post("http://${activeDevice.ip}:${activeDevice.port}/message") {
                    timeout {
                        connectTimeoutMillis = 30000
                        requestTimeoutMillis = 5000
                    }
                    val deviceMessageParams = Gson().toJson(
                        DeviceMessageParams(
                            sendId = deviceMessage.id,
                            clientCode = clientCode,
                            content = deviceMessage.content,
                            filename = deviceMessage.filename,
                            size = deviceMessage.size
                        )
                    )
                    logger.info("deviceMessageParams: {}", deviceMessageParams)
                    setBody(
                        deviceMessageParams
                    )
                    contentType(ContentType.Application.Json)
                }
            if (response.status == HttpStatusCode.OK) {
                logger.info("发送成功")
                deviceMessage.sendSuccess = true
                save(deviceMessage)
                onSend(deviceMessage)
            }
        }
    }

    Column(
        modifier = Modifier.padding(bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Button(
                    onClick = {
                        showFilePicker = true
                    }
                ) {
                    TooltipArea(
                        tooltip = {
                            if (file?.name != null) {
                                Box(
                                    modifier = Modifier.background(color = Color.White)
                                ) {
                                    Text(file?.name ?: "", color = Color.Black, fontWeight = FontWeight.Light, fontSize = 12.sp)
                                }
                            }
                        },
                        delayMillis = 200
                    ) {
                        Row {
                            file.let {
                                if (it != null) {
                                    val names = getFileNameAndType(it.name)
                                    Text(
                                        text = names?.get(0) ?: "",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Text(
                                        text = names?.get(1)?.let { ".${it}" } ?: "",
                                    )
                                    Text(
                                        text = " ${readableFileSize(it.length()) ?: ""}",
                                        fontWeight = FontWeight.Light, maxLines = 1
                                    )
                                } else {
                                    Text(
                                        text = "选择文件",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (file != null) {
                Box(
                    modifier = Modifier.size(48.dp).padding(5.dp).clickable {
                        file = null
                    }
                ) {
                    Image(
                        painter = painterResource("删除(1).svg"),
                        contentDescription = null
                    )
                }
            }
        }
        TextField(
            value = content ?: "",
            onValueChange = { content = it }, placeholder = { Text(text = "输入或粘贴要发送的文本或文件") },
            modifier = Modifier.fillMaxWidth().onPasted {
                val systemClipboard = Toolkit.getDefaultToolkit().systemClipboard
                if (systemClipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                    val obj = systemClipboard.getData(DataFlavor.javaFileListFlavor)
                    if (obj is List<*>) {
                        if (obj.isNotEmpty()) {
                            val first = obj.firstOrNull()
                            if (first is File) {
                                file = first
                            }
                        }
                    }
                }
            },
        )
        AsyncButton(
            onClick = {
                sendMsg()
            }
        ) {
            Text(text = "发送${if (file != null) "文件" else if (content?.isNotEmpty() == true) "文本" else ""}")
        }
    }
}