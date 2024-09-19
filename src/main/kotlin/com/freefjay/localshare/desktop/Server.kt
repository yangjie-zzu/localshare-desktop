package com.freefjay.localshare.desktop

import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.model.DeviceMessage
import com.freefjay.localshare.desktop.model.DeviceMessageParams
import com.freefjay.localshare.desktop.model.DownloadInfo
import com.freefjay.localshare.desktop.util.*
import io.ktor.server.cio.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.URLEncoder
import java.util.*
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

suspend fun startServer() {
    val freePort = getFreePort() ?: throw RuntimeException("not found port")
    serverPort = freePort
    embeddedServer(CIO, applicationEngineEnvironment {
        log = LoggerFactory.getLogger("ktor.server")
        connector {
            port = freePort
        }
        module {
            install(PartialContent)
            routing {
                get("/code") {

                }
                post("/exchange") {
                    logger.info("exchange")
                    val body = call.receiveText()
                    val (_, clientCode, name, ip, port, channelType, osName, networkType, wifiName) = Gson().fromJson(body, Device::class.java)
                    logger.info("clientCode: ${clientCode}")
                    var otherDevice =
                        queryList<Device>("select * from device where client_code = '${clientCode}'").firstOrNull()
                    logger.info("otherDevice: {}", otherDevice)
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
                    call.respond(status = HttpStatusCode.OK, message = NullBody)
                    logger.info("/message回复")
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
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName,
                                    withContext(Dispatchers.IO) {
                                        URLEncoder.encode(file.name ?: "", "UTF-8")
                                    }).toString()
                            )
                            call.respondFile(file = file)
                        }
                    }
                }

                get("/downloadInfo") {
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
                            FileInputStream(file).use {
                                call.respondText(Gson().toJson(
                                    DownloadInfo(
                                    size = file.length(),
                                    hash = hash(it)
                                )
                                ))
                            }
                        }
                    }
                }
            }
        }
    }).start()
    startMDns()
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
            logger.info("发现设备: ${event?.type}, ${event?.name}, ${device.clientCode}")
            if (event?.type == serviceType && event.name != device.clientCode) {
                logger.info("解析")
                jmDNS.requestServiceInfo(event.type, event.name, 1)
            }
        }

        override fun serviceRemoved(event: ServiceEvent?) {
            logger.info("serviceRemoved: ${event?.type}, ${event?.name}")
        }

        override fun serviceResolved(event: ServiceEvent?) {
            logger.info("解析设备: ${event?.type}, ${event?.name}, ${event?.info?.inet4Addresses?.joinToString { it.toString() }}, ${event?.info?.port}")
            val ip = event?.info?.inet4Addresses?.firstOrNull()
            val port = event?.info?.port
            if (event?.type == serviceType && event.name != device.clientCode) {
                CoroutineScope(Dispatchers.IO).launch {
                    exchangeDevice(ip?.toString(), port)
                }
            }
        }

    })
}