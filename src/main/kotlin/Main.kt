import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import util.*
import java.net.InetAddress
import java.util.UUID
import kotlin.reflect.KClass

val logger: Logger = LoggerFactory.getLogger("share")

@Composable
@Preview
fun App() {
    var text by remember { mutableStateOf("Hello, World!") }

    MaterialTheme {
        Row {
            Button(onClick = {
                text = "Hello, Desktop!"
            }) {
                Text(text)
            }
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
            LazyColumn {
                logger.info("devices: ${devices.size}")
                items(devices, {it.id ?: ""}) {
                    Text("${it.name}")
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

fun main() = application {
    CoroutineScope(Dispatchers.Default).launch {
        transaction {
            logger.info("测试: ${localTransactionManager.get().schema}")
            logger.info("select 1: ${queryMap("select 1")}")
        }
        logger.info("表结构同步开始")
        listOf<KClass<out Any>>(Device::class).forEach {
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
    Window(onCloseRequest = ::exitApplication) {
        App()
    }
}
