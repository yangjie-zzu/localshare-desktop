package com.freefjay.localshare.desktop

import App
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.freefjay.localshare.desktop.component.Frame
import com.freefjay.localshare.desktop.model.*
import com.freefjay.localshare.desktop.util.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.net.InetAddress
import java.util.*

val logger: Logger = LoggerFactory.getLogger("share")

val httpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 60000
        requestTimeoutMillis = 60000
    }
}

val LocalApplication = compositionLocalOf<ApplicationScope?> { null }

val LocalWindow = compositionLocalOf<WindowState?> { null }

var serverPort: Int? = null

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
    val app = this
    CoroutineScope(Dispatchers.Default).launch {
        initDbPath()
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
            undecorated = true,
            icon = painterResource("logo.ico")
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
                                        Text("菜单(${Locale.getDefault().displayName})", fontSize = 12.sp, lineHeight = 12.sp)
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
