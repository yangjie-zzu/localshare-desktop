import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.freefjay.localshare.desktop.*
import com.freefjay.localshare.desktop.component.ActionButton
import com.freefjay.localshare.desktop.component.CustomContextMenu
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.model.DeviceMessage
import com.freefjay.localshare.desktop.model.DeviceMessageParams
import com.freefjay.localshare.desktop.util.*
import com.google.gson.Gson
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.*

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App() {

    val currentCoroutineScope = rememberCoroutineScope()
    MaterialTheme {
        Column {
            Row {
                var devices by remember {
                    mutableStateOf<List<Device>>(listOf())
                }
                val requestDevices = suspend {
                    val list = queryList<Device>("select * from device")
                    logger.info("deviceIds: ${list.joinToString { "${it.clientCode}" }}")
                    devices = list
                }
                LaunchedEffect(Unit) {
                    requestDevices()
                }
                OnEvent(deviceEvent) {
                    logger.info("触发设备事件")
                    CoroutineScope(Dispatchers.IO).launch {
                        requestDevices()
                    }
                }
                var activeDevice by remember {
                    mutableStateOf<Device?>(null)
                }
                val deviceMessages = remember {
                    mutableStateListOf<DeviceMessage>()
                }
                val deviceMessageListState = rememberLazyListState()
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
                LaunchedEffect(Unit) {
                    requestMessages(activeDevice?.id)
                }

                OnEvent(deviceMessageEvent) {
                    if (it.deviceId == activeDevice?.id) {
                        currentCoroutineScope.launch {
                            requestMessages(activeDevice?.id)
                        }
                    }
                }

                var fileProgressMap by remember {
                    mutableStateOf<Map<Long?, FileProgress?>?>(null)
                }

                if (deviceMessages.any { it.type == "receive" && it.downloadSuccess != true }) {
                    logger.info("定时任务")
                    OnTimer(block = {
                        fileProgressMap = mutableMapOf<Long?, FileProgress?>().also {
                            it.putAll(fileProgresses)
                        }
                    })
                }
                LazyColumn(
                    modifier = Modifier.width(200.dp)
                ) {
                    itemsIndexed(devices, { _, it -> it.id ?: "" }) { _, item ->
                        var offsetX by remember {
                            mutableStateOf(0f)
                        }
                        var offsetY by remember {
                            mutableStateOf(0f)
                        }
                        var show by remember {
                            mutableStateOf(false)
                        }
                        Row(
                            modifier = Modifier.clickable {
                                activeDevice = item
                                CoroutineScope(Dispatchers.Default).launch {
                                    requestMessages(item.id)
                                }
                            }.fillMaxWidth()
                                .background(color = if (activeDevice?.id == item.id) Color.LightGray else Color.Transparent)
                                .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) {
                                    show = true
                                }.onPointerEvent(eventType = PointerEventType.Press) {
                                    val position = it.changes.first().position
                                    offsetX = position.x
                                    offsetY = position.y
                                }.padding(5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (show) {
                                Popup(
                                    onDismissRequest = { show = false },
                                    offset = IntOffset(offsetX.toInt(), offsetY.toInt())
                                ) {
                                    Column(
                                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color = Color.White)
                                            .border(
                                                border = BorderStroke(width = 1.dp, color = Color(0, 0, 0, 20)),
                                                shape = RoundedCornerShape(5.dp)
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier.clickable {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    delete<Device>(item.id)
                                                    activeDevice = null
                                                    requestDevices()
                                                }
                                            }.padding(5.dp)
                                        ) {
                                            Text("删除")
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("${item.name}")
                                Text("${item.ip}:${item.port}", fontSize = 12.sp)
                            }
                            Column {
                                Text("${item.osName}", softWrap = false)
                            }
                        }
                        Divider()
                    }
                }
                Box(modifier = Modifier.fillMaxHeight().background(color = Color.LightGray).width(1.dp))
                Box(
                    modifier = Modifier.weight(1f).sizeIn(minWidth = 500.dp).padding(start = 10.dp, end = 10.dp)
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
                                            requestMessages(activeDevice?.id, false)
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
                                                            val fileProgress = fileProgressMap?.get(item.id)
                                                            if (item.filename != null) {
                                                                Text(text = item.filename ?: "")
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                                ) {
                                                                    Box(modifier = Modifier.size(20.dp)) {
                                                                        if (item.filename != null && item.downloadSuccess != true && fileProgress == null) {
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
                                                                        } else if (fileProgress != null) {
                                                                            item.size?.let { size ->
                                                                                CircularProgressIndicator(
                                                                                    progress = fileProgress.handleSize.toFloat()/size
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                    Text(
                                                                        text = "${fileProgress?.let { fileProgress ->  "${readableFileSize(fileProgress.handleSize)}/" } ?: ""}${readableFileSize(item.size ?: 0)}",
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
                            Column(
                                modifier = Modifier.padding(bottom = 5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                var file by remember {
                                    mutableStateOf<File?>(null)
                                }
                                var content by remember {
                                    mutableStateOf<String?>(null)
                                }
                                var showFilePicker by remember { mutableStateOf(false) }

                                LaunchedEffect(activeDevice?.id) {
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
                                suspend fun sendMsg() {
                                    withContext(Dispatchers.IO) {
                                        logger.info("开始发送")
                                        val deviceMessage = DeviceMessage(
                                            type = "send",
                                            content = content,
                                            filepath = file?.absolutePath,
                                            filename = file?.name,
                                            size = file?.length(),
                                            deviceId = activeDevice?.id,
                                            createdTime = Date()
                                        )
                                        save(deviceMessage)
                                        requestMessages(activeDevice?.id)
                                        val response =
                                            httpClient.post("http://${activeDevice?.ip}:${activeDevice?.port}/message") {
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
                                            requestMessages(activeDevice?.id)
                                        }
                                    }
                                }
                                TextField(
                                    value = content ?: "",
                                    onValueChange = { content = it }, placeholder = { Text(text = "输入或粘贴您要发送的文字和文件") },
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
                                ActionButton(
                                    onClick = {
                                        sendMsg()
                                    }
                                ) {
                                    Text(text = "发送${if (file != null) "文件" else if (content?.isNotEmpty() == true) "文字" else ""}")
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("未选中左侧设备")
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxHeight().background(color = Color.LightGray).width(1.dp))
                Column(
                    modifier = Modifier.width(250.dp).padding(top = 5.dp, start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    var self by remember {
                        mutableStateOf(getDevice())
                    }

                    fun querySelf() {
                        self = getDevice()
                    }

                    SelectionContainer(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("name: ${self.name ?: ""}")
                            Text("clientCode: ${self.clientCode ?: ""}")
                            Text("ip: ${self.ip ?: ""}")
                            Text("port: ${self.port ?: ""}")
                            Text("channelType: ${self.channelType ?: ""}")
                            Text("osName: ${self.osName ?: ""}")
                            Text("networkType: ${self.networkType ?: ""}")
                            Text("wifiName: ${self.wifiName ?: ""}")
                        }
                    }
                    val url = "http://${self.ip}:${self.port}/code"
                    val byteMatrix = remember(url) {
                        Encoder.encode(
                            url,
                            ErrorCorrectionLevel.H,
                            mapOf(
                                EncodeHintType.CHARACTER_SET to "UTF-8",
                                EncodeHintType.MARGIN to 16,
                                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
                            )
                        ).matrix
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    ) {
                        Canvas(
                            modifier = Modifier.fillMaxWidth().background(Color.Transparent)
                        ) {
                            byteMatrix?.let {
                                val cellSize = size.width / byteMatrix.width
                                for (x in 0 until byteMatrix.width) {
                                    for (y in 0 until byteMatrix.height) {
                                        drawRect(
                                            color = if (byteMatrix.get(
                                                    x,
                                                    y
                                                ) == 1.toByte()
                                            ) Color.Black else Color.White,
                                            topLeft = Offset(x * cellSize, y * cellSize),
                                            size = Size(cellSize, cellSize)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { querySelf() }) {
                            Text("刷新")
                        }
                    }
                }
            }
        }
    }
}
