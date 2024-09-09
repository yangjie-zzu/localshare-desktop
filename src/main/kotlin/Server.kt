import androidx.compose.runtime.*
import com.google.gson.Gson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import model.Device
import model.DeviceMessage
import model.DeviceMessageParams
import org.slf4j.LoggerFactory
import util.*
import java.io.File
import java.net.InetAddress
import java.util.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceTypeListener

class FileProgress(val messageId: Long?, val handleSize: Long, val totalSize: Long)

val deviceMessageDownloadEvent = Event<FileProgress>()

fun startServer() {
    val httpPort = serverPort ?: return
    embeddedServer(Netty, applicationEngineEnvironment {
        log = LoggerFactory.getLogger("ktor.server")
        connector {
            port = httpPort
        }
        module {
            routing {
                post("/exchange") {
                    logger.info("exchange")
                    val body = call.receiveText()
                    val (_, clientCode, name, ip, port, channelType, osName, networkType, wifiName) = Gson().fromJson(body, Device::class.java)
                    var otherDevice =
                        queryList<Device>("select * from device where client_code = '${clientCode}'").firstOrNull()
                    if (otherDevice == null) {
                        otherDevice = Device()
                    }
                    otherDevice.clientCode = clientCode
                    otherDevice.name = name
                    otherDevice.ip = ip
                    otherDevice.port = port
                    otherDevice.channelType = channelType
                    otherDevice.osName = osName
                    otherDevice.networkType = networkType
                    otherDevice.wifiName = wifiName
                    save(otherDevice)
                    async {
                        deviceEvent.doAction(Unit)
                    }
                    call.respondText(contentType = ContentType.Application.Json) {
                        Gson().toJson(getDevice())
                    }
                }

                post("/message") {
                    val body = call.receiveText()
                    logger.info("body: ${body}")
                    val deviceSendParams = Gson().fromJson(body, DeviceMessageParams::class.java)
                    val deviceMessage = DeviceMessage()
                    deviceMessage.oppositeId = deviceSendParams.sendId
                    deviceMessage.content = deviceSendParams.content
                    deviceMessage.filename = deviceSendParams.filename
                    deviceMessage.createdTime = Date()
                    deviceMessage.type = "receive"
                    deviceMessage.size = deviceSendParams.size
                    val device = queryOne<Device>("select * from device where client_code = '${deviceSendParams.clientCode}'")
                    deviceMessage.deviceId = device?.id
                    save(deviceMessage)
                    logger.info("接收到消息: ${Gson().toJson(deviceMessage)}")
                    async {
                        deviceMessageEvent.doAction(deviceMessage)
                        downloadMessageFile(device, deviceMessage)
                    }
                    call.response.status(HttpStatusCode.OK)
                }

                get("/download") {
                    val messageId = call.parameters.get("messageId")?.toLong()
                    val deviceMessage = queryOne<DeviceMessage>("select * from device_message where id = ${messageId}")
                    val filepath = deviceMessage?.filepath
                    if (filepath == null) {
                        call.respond(status = HttpStatusCode.NotFound, "not found path")
                    } else {
                        val file = File(filepath)
                        if (!file.exists() || file.isDirectory) {
                            call.respond(status = HttpStatusCode.NotFound, "not found file")
                        } else {
                            call.respondFile(file = file)
                        }
                    }
                }
            }
        }
    }).start()
    startMDns()
}

@Composable
fun OnDownloadProgressEvent(block: (data: FileProgress) -> Unit) {

    var processTime by remember {
        mutableStateOf(Date())
    }

    OnEvent(event = deviceMessageDownloadEvent, block = {
        if (Date().time - processTime.time > 200 || (it.handleSize >= it.totalSize)) {
            block(it)
            processTime = Date()
        }
    })
}

fun startMDns() {
    val device = getDevice()
    val httpPort = device.port ?: return
    val serviceType = "_share._tcp.local."
    val serviceName = device.clientCode ?: return
    logger.info("startMDns: ${serviceType}, ${serviceName}")
    val jmDNS = JmDNS.create(InetAddress.getLocalHost().also {
        logger.info("startMDns: ${it.hostAddress}")
    }, serviceName)
    jmDNS.registerService(ServiceInfo.create(serviceType, serviceName, httpPort, ""))
    jmDNS.addServiceListener(serviceType, object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent?) {
            logger.info("发现设备: ${event?.type}, ${event?.name}")
            if (event?.type == serviceType && event.name != device.clientCode) {
                logger.info("解析")
                jmDNS.requestServiceInfo(event.type, event.name, 1)
            }
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            logger.info("serviceRemoved: ${event?.type}, ${event?.name}")
        }

        override fun serviceResolved(event: ServiceEvent?) {
            logger.info("serviceResolved: ${event?.type}, ${event?.name}, ${event?.info?.inet4Addresses?.joinToString { it.toString() }}, ${event?.info?.port}")
            val ip = event?.info?.inet4Addresses?.firstOrNull()
            val port = event?.info?.port
            CoroutineScope(Dispatchers.IO).launch {
                exchangeDevice(ip?.toString(), port)
            }
        }

    })
}