import com.google.gson.Gson
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import model.Device
import model.DeviceMessage
import model.DeviceMessageParams
import org.slf4j.LoggerFactory
import util.getFileNameAndType
import util.queryList
import util.queryOne
import util.save
import java.io.File
import java.util.*

class Progress(val messageId: Long?, val handleSize: Long, val totalSize: Long)

val deviceMessageDownloadEvent = Event<Progress>()

fun startServer() {
    embeddedServer(Netty, applicationEngineEnvironment {
        log = LoggerFactory.getLogger("ktor.server")
        connector {
            port = 20000
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
                    call.respond(getDevice())
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
                        val filename = deviceMessage.filename
                        if (device != null && filename != null) {
                            val file = run {
                                val downloadPath = "${System.getProperty("user.home")}/Downloads"
                                val dir = File(downloadPath)
                                if (!dir.exists()) {
                                    dir.mkdirs()
                                }
                                var downloadFile = File(downloadPath, filename)
                                if (downloadFile.exists()) {
                                    val nameAndType = getFileNameAndType(filename)
                                    var i = 1
                                    do {
                                        downloadFile = File(downloadPath, "${nameAndType?.get(0)}(${i})${if (nameAndType?.get(1) != null) ".${nameAndType[1]}" else ""}")
                                        i ++
                                    } while (downloadFile.exists())
                                }
                                downloadFile.createNewFile()
                                downloadFile
                            }
                            logger.info("file: ${file.absolutePath}")
                            httpClient.prepareGet("http://${device.ip}:${device.port}/download?messageId=${deviceMessage.oppositeId}") {
                            }.execute { response ->
                                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
                                var downloadSize = 0L
                                withContext(Dispatchers.IO) {
                                    val startTime = Date()
                                    val channel = response.bodyAsChannel()
                                    while (!channel.isClosedForRead) {
                                        val packet =
                                            channel.readRemaining(limit = DEFAULT_BUFFER_SIZE.toLong())
                                        while (!packet.isEmpty) {
                                            val bytes = packet.readBytes()
                                            file.appendBytes(bytes)
                                            downloadSize += bytes.size
                                            logger.info("下载进度: ${downloadSize.toDouble()/contentLength}, ${downloadSize}, ${contentLength}")
                                            async {
                                                deviceMessageDownloadEvent.doAction(Progress(messageId = deviceMessage.id, handleSize = downloadSize, totalSize = contentLength))
                                            }
                                        }
                                    }
                                    logger.info("下载用时: ${(Date().time - startTime.time)/1000}s")
                                    deviceMessage.downloadSuccess = true
                                    deviceMessage.downloadSize = downloadSize
                                    deviceMessage.size = contentLength
                                    deviceMessage.savePath = file.absolutePath
                                    save(deviceMessage)
                                    deviceMessageEvent.doAction(deviceMessage)
                                }
                            }
                        }
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
}