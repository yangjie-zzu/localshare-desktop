import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.google.gson.Gson
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import component.CustomContextMenu
import component.Frame
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import model.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import util.*
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.List

val logger: Logger = LoggerFactory.getLogger("share")

val httpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 60000
        requestTimeoutMillis = 60000
    }
}

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
                    logger.info("deviceIds: ${list.joinToString { "${it.id}" }}")
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
                                fun sendMsg() {
                                    CoroutineScope(Dispatchers.IO).launch {
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
                                                val deviceMessageParams = Gson().toJson(DeviceMessageParams(
                                                    sendId = deviceMessage.id,
                                                    clientCode = clientCode,
                                                    content = deviceMessage.content,
                                                    filename = deviceMessage.filename,
                                                    size = deviceMessage.size
                                                ))
                                                logger.info("deviceMessageParams: {}", deviceMessageParams)
                                                setBody(
                                                    deviceMessageParams
                                                )
                                                contentType(ContentType.Application.Json)
                                            }
                                        if (response.status == HttpStatusCode.OK) {
                                            logger.info("发送成功")
                                            val body = response.bodyAsText()
                                            deviceMessage.sendSuccess = true
                                            save(deviceMessage)
                                            requestMessages(activeDevice?.id)
                                        }
                                    }
                                }
                                TextField(
                                    value = content ?: "",
                                    onValueChange = { content = it }, placeholder = { Text(text = "输入要发送的文字") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
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

val LocalApplication = compositionLocalOf<ApplicationScope?> { null }

val LocalWindow = compositionLocalOf<WindowState?> { null }

var serverPort: Int? = 2000

var clientCode: String? = null

fun getDevice(): Device {
    val device = Device()
    device.clientCode = clientCode
    device.name = InetAddress.getLocalHost().hostName
    device.ip = InetAddress.getLocalHost().hostAddress
    device.port = serverPort
    device.channelType = "desktop"
    device.osName = System.getProperty("os.name")
    return device
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) = application {
    logger.info("args: ${args.joinToString { it }}")
    CoroutineScope(Dispatchers.IO).launch {
        val taskQueue = TaskQueue()
        taskQueue.execute {
            logger.info("任务1")
            taskQueue.execute {
                logger.info("任务2")
                taskQueue.execute {
                    logger.info("任务2.1")
                }
            }
            logger.info("任务3")
        }
    }
    serverPort = kotlin.run {
        if (args.isNotEmpty()) {
            args[0].toInt()
        } else {
            20000
        }
    }
    val app = this
    CoroutineScope(Dispatchers.Default).launch {
        transaction {
            logger.info("测试: ${localTransactionManager.get()?.connection?.schema}")
            logger.info("select 1: ${queryMap("select 1")}")
            logger.info("表结构同步开始")
            listOf(Device::class, DeviceMessage::class, SysInfo::class, FilePart::class).forEach {
                logger.info("单表同步${it.simpleName}开始")
                updateTableStruct(it)
                logger.info("单表同步${it.simpleName}结束")
            }
            logger.info("表结构同步成功")
        }
        clientCode = transaction("client_id") {
            var sysInfo = queryOne<SysInfo>("select * from sys_info where name = 'client_id'")
            if (sysInfo == null) {
                sysInfo = SysInfo(
                    name = "client_id",
                    value = UUID.randomUUID().toString()
                )
                save(sysInfo)
            }
            sysInfo.value
        }
        logger.info("启动http服务")
        startServer()
    }
    CompositionLocalProvider(LocalApplication provides app) {
        val windowState = rememberWindowState(position = WindowPosition(alignment = Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            title = "文件分享",
            state = windowState,
            undecorated = true
        ) {
            CompositionLocalProvider(
                LocalLocalization provides object : PlatformLocalization {
                    override val copy: String
                        get() = "复制"

                    override val cut: String
                        get() = "剪切"
                    override val paste: String
                        get() = "粘贴"
                    override val selectAll: String
                        get() = "全选"
                }
            ) {
                CompositionLocalProvider(LocalWindow provides windowState) {
                    Column {
                        WindowDraggableArea {
                            Row(
                                modifier = Modifier.height(25.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.clickable {

                                        }.fillMaxHeight().padding(start = 5.dp, end = 5.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("菜单", fontSize = 12.sp, lineHeight = 12.sp)
                                    }
                                }
                                Row {
                                    Frame {
                                        var hover by remember {
                                            mutableStateOf(false)
                                        }
                                        Box(
                                            modifier = Modifier.clickable { windowState.isMinimized = true }
                                                .background(color = if (hover) Color.Gray else Color.Transparent)
                                                .fillMaxHeight().padding(5.dp).width(40.dp)
                                                .onPointerEvent(eventType = PointerEventType.Move) {
                                                    hover = true
                                                }.onPointerEvent(eventType = PointerEventType.Exit) {
                                                    hover = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (hover) {
                                                Image(
                                                    painter = painterResource("最小化1.svg"),
                                                    contentDescription = null
                                                )
                                            } else {
                                                Image(
                                                    painter = painterResource("最小化.svg"),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    }
                                    var isMax by remember {
                                        mutableStateOf(false)
                                    }
                                    var windowSize by remember {
                                        mutableStateOf(windowState.size)
                                    }
                                    var windowPosition by remember {
                                        mutableStateOf(windowState.position)
                                    }
                                    if (isMax) {
                                        Frame {
                                            var hover by remember {
                                                mutableStateOf(false)
                                            }
                                            Box(
                                                modifier = Modifier.clickable {
                                                    isMax = false
                                                    windowState.position = windowPosition
                                                    windowState.size = windowSize
                                                }
                                                    .background(color = if (hover) Color.Gray else Color.Transparent)
                                                    .fillMaxHeight().padding(5.dp).width(40.dp)
                                                    .onPointerEvent(eventType = PointerEventType.Move) {
                                                        hover = true
                                                    }.onPointerEvent(eventType = PointerEventType.Exit) {
                                                        hover = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (hover) {
                                                    Image(
                                                        painter = painterResource("还原1.svg"),
                                                        contentDescription = null
                                                    )
                                                } else {
                                                    Image(
                                                        painter = painterResource("还原.svg"),
                                                        contentDescription = null
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Frame {
                                            var hover by remember {
                                                mutableStateOf(false)
                                            }
                                            val localDensity = LocalDensity.current
                                            Box(
                                                modifier = Modifier.clickable {
                                                    isMax = true
                                                    windowPosition = windowState.position
                                                    windowSize = windowState.size
                                                    logger.info("density: ${localDensity.density}, x: ${GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.x}, y: ${GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.y}, w: ${GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.width}, h: ${GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height}")
                                                    windowState.position = WindowPosition(x = 0.dp, y = 0.dp)
                                                    windowState.size = DpSize(
                                                        (GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.width).dp,
                                                        (GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height).dp
                                                    )
                                                }
                                                    .background(color = if (hover) Color.Gray else Color.Transparent)
                                                    .fillMaxHeight().padding(5.dp).width(40.dp)
                                                    .onPointerEvent(eventType = PointerEventType.Move) {
                                                        hover = true
                                                    }.onPointerEvent(eventType = PointerEventType.Exit) {
                                                        hover = false
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (hover) {
                                                    Image(
                                                        painter = painterResource("最大化1.svg"),
                                                        contentDescription = null
                                                    )
                                                } else {
                                                    Image(
                                                        painter = painterResource("最大化.svg"),
                                                        contentDescription = null
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Frame {
                                        var hover by remember {
                                            mutableStateOf(false)
                                        }
                                        Box(
                                            modifier = Modifier.clickable { app.exitApplication() }
                                                .background(color = if (hover) Color.Red else Color.Transparent)
                                                .fillMaxHeight().width(40.dp)
                                                .onPointerEvent(eventType = PointerEventType.Move) {
                                                    hover = true
                                                }.onPointerEvent(eventType = PointerEventType.Exit) {
                                                    hover = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (hover) {
                                                Image(
                                                    painter = painterResource("关闭1.svg"),
                                                    contentDescription = null
                                                )
                                            } else {
                                                Image(
                                                    painter = painterResource("关闭.svg"),
                                                    contentDescription = null
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        App()
                    }
                }
            }
        }
    }
}
