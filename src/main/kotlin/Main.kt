import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import model.Device
import model.DeviceTransfer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import util.*
import java.awt.FileDialog
import java.awt.GraphicsEnvironment
import java.net.InetAddress
import java.util.UUID
import javax.swing.JFileChooser
import kotlin.reflect.KClass

val logger: Logger = LoggerFactory.getLogger("share")

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
@Preview
fun App() {

    MaterialTheme {
        Column {
            Row {
                val devices = remember {
                    mutableStateListOf<Device>()
                }
                val requestDevices = suspend {
                    val list = queryList<Device>("select * from device")
                    logger.info("${list.size}")
                    devices.clear()
                    devices.addAll(list)
                }
                LaunchedEffect(Unit) {
                    requestDevices()
                }
                onDeviceEvent {
                    suspend {
                        requestDevices()
                    }
                }
                var activeDevice by remember {
                    mutableStateOf<Device?>(null)
                }
                val deviceTransfers = remember {
                    mutableStateListOf<DeviceTransfer>()
                }
                suspend fun requestTransfers(deviceId: Long?) {
                    deviceTransfers.clear()
                    if (deviceId == null) {
                        return
                    }
                    deviceTransfers.addAll(queryList("select * from device_transfer where device_id = $deviceId"))
                }
                LaunchedEffect(Unit) {
                    requestTransfers(activeDevice?.id)
                }
                LazyColumn(
                    modifier = Modifier.width(200.dp)
                ) {
                    logger.info("devices: ${devices.size}")
                    itemsIndexed(devices, { _, it -> it.id ?: ""}) { _, item ->
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
                                    requestTransfers(item.id)
                                }
                            }.fillMaxWidth()
                                .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) {
                                    show = true
                                }.onPointerEvent(eventType = PointerEventType.Press) {
                                    val position = it.changes.first().position
                                    logger.info("位置: ${position.x}, ${position.y}")
                                    offsetX = position.x
                                    offsetY = position.y
                                }
                        ) {
                            if (show) {
                                Popup(
                                    onDismissRequest = {show = false},
                                    offset = IntOffset(offsetX.toInt(), offsetY.toInt())
                                ) {
                                    Column(
                                        modifier = Modifier.background(color = Color.White)
                                            .border(
                                                border = BorderStroke(width = 1.dp, color = Color(0, 0, 0, 20)),
                                                shape = RoundedCornerShape(5.dp)
                                            ).padding(5.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.clickable {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    delete<Device>(item.id)
                                                    requestDevices()
                                                }
                                            }
                                        ) {
                                            Text("删除")
                                        }
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier.fillMaxSize().padding(10.dp)
                            ) {
                                Text("${item.name}")
                            }
                        }
                        Divider()
                    }
                }
                Box(modifier = Modifier.fillMaxHeight().background(color = Color.LightGray).width(1.dp))
                Column(
                    modifier = Modifier.weight(1f).sizeIn(minWidth = 500.dp).padding(start = 10.dp)
                ) {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(
                            items = deviceTransfers,
                            key = {_, item -> item.id.toString()}
                        ) {_, item ->
                            if (item.type == "receive") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box {
                                        Text(text = "收")
                                    }
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(text = item.filename ?: "")
                                        SelectionContainer {
                                            Text(text = item.content ?: "")
                                        }
                                    }
                                }
                            }
                            if (item.type == "send") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(text = item.filename ?: "")
                                        SelectionContainer {
                                            Text(text = item.content ?: "")
                                        }
                                    }
                                    Box {
                                        Text(text = "发")
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        var filePath by remember {
                            mutableStateOf<String?>(null)
                        }
                        var content by remember {
                            mutableStateOf<String?>(null)
                        }
                        var showFilePicker by remember { mutableStateOf(false) }

                        val fileType = listOf("*")
                        FilePicker(show = showFilePicker, fileExtensions = fileType) { platformFile ->
                            showFilePicker = false
                            filePath = platformFile?.path
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier.weight(1f)
                            ) {
                                Button(
                                    onClick = {
                                        showFilePicker = true
                                    }
                                ) {
                                    Text(text = filePath ?: "选择文件")
                                }
                            }
                            if (filePath != null) {
                                Box(
                                    modifier = Modifier.size(48.dp).padding(5.dp).clickable {
                                        filePath = null
                                    }
                                ) {
                                    Image(
                                        painter = painterResource("删除(1).svg"),
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                        TextField(value = content ?: "", onValueChange = {content = it}, placeholder = { Text(text = "输入要发送的文字") })
                        Button(
                            onClick = {}
                        ) {
                            Text(text = "发送${if (filePath != null) "文件" else if (content?.isNotEmpty() == true) "文字" else ""}")
                        }
                    }
                }
            }
        }
    }
}

val deviceEvent = Event()

@Composable
fun onDeviceEvent(block: () -> Unit) {
    DisposableEffect(Unit) {
        val removeAction = deviceEvent.registerAction {
            block()
        }
        onDispose {
            removeAction()
        }
    }
}

val LocalApplication = compositionLocalOf<ApplicationScope?> { null }

val LocalWindow = compositionLocalOf<WindowState?> { null }

var serverPort: Int? = 20000

fun getDevice(): Device {
    val device = Device()
    device.clientId = UUID.randomUUID().toString()
    device.name = InetAddress.getLocalHost().hostName
    device.ip = InetAddress.getLocalHost().hostAddress
    device.port = serverPort
    device.channelType = "desktop"
    device.osName = System.getProperty("os.name")
    return device
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val app = this
    CoroutineScope(Dispatchers.Default).launch {
        transaction {
            logger.info("测试: ${localTransactionManager.get().schema}")
            logger.info("select 1: ${queryMap("select 1")}")
        }
        logger.info("表结构同步开始")
        listOf(Device::class, DeviceTransfer::class).forEach {
            updateTableStruct(it)
        }
        logger.info("表结构同步成功")
    }
    embeddedServer(Netty, applicationEngineEnvironment {
        log = LoggerFactory.getLogger("ktor.server")
        connector {
            port = 20000
        }
        module {
            routing {
                post("/exchange") {
                    logger.info("exchange")
                    val (_, clientId, name, ip, port, channelType, osName, networkType, wifiName) = call.receive<Device>()
                    var otherDevice = queryList<Device>("select * from device where client_id = '${clientId}'").firstOrNull()
                    if (otherDevice == null) {
                        otherDevice = Device()
                    }
                    otherDevice.clientId = clientId
                    otherDevice.name = name
                    otherDevice.ip = ip
                    otherDevice.port = port
                    otherDevice.channelType = channelType
                    otherDevice.osName = osName
                    otherDevice.networkType = networkType
                    otherDevice.wifiName = wifiName
                    save(otherDevice)
                    async {
                        deviceEvent.doAction()
                    }
                    call.respond(getDevice())
                }
            }
            install(ContentNegotiation) {
                jackson {
                    configure(SerializationFeature.INDENT_OUTPUT, true)
                    setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
                        indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
                        indentObjectsWith(DefaultIndenter("  ", "\n"))
                    })
                    registerModule(JavaTimeModule())  // support java.time.* types
                }
            }
        }
    }).start()
    CompositionLocalProvider(LocalApplication provides app) {
        val windowState = rememberWindowState(position = WindowPosition(alignment = Alignment.Center))
        Window(
            onCloseRequest = ::exitApplication,
            title = "文件分享",
            state = windowState,
            undecorated = true
        ) {
            CompositionLocalProvider(LocalWindow provides windowState) {
                Column {
                    WindowDraggableArea{
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
                                                windowState.size = DpSize((GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.width).dp, (GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height).dp)
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
                    Divider()
                    App()
                }
            }
        }
    }
}
