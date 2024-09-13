package util

import FileProgress
import com.google.gson.Gson
import deviceEvent
import deviceMessageDownloadEvent
import deviceMessageEvent
import getDevice
import httpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import logger
import model.Device
import model.DeviceMessage
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.*
import kotlin.experimental.and

suspend fun downloadMessageFile(device: Device?, deviceMessage: DeviceMessage) {
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
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
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
//                        logger.info("下载进度: ${downloadSize.toDouble()/contentLength}, ${downloadSize}, ${contentLength}")
                        async {
                            deviceMessageDownloadEvent.doAction(FileProgress(messageId = deviceMessage.id, handleSize = downloadSize, totalSize = contentLength))
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

suspend fun exchangeDevice(ip: String?, port: Int?): Device? {
    return transaction {
        if (ip == null || port == null) {
            return@transaction null
        }
        val response = httpClient.post("http://${ip}:${port}/exchange") {
            setBody(Gson().toJson(getDevice()))
            contentType(ContentType.Application.Json)
        }
        logger.info("status: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            val body = response.body<String>()
            logger.info("body: $body")
            val deviceResult = Gson().fromJson(body, Device::class.java)
            var otherDevice = queryList<Device>("select * from device where client_code = '${deviceResult.clientCode}'").firstOrNull()
            logger.info("otherDevice: {}", otherDevice)
            if (otherDevice == null) {
                otherDevice = Device()
            }
            otherDevice.clientCode = deviceResult.clientCode
            otherDevice.name = deviceResult.name
            otherDevice.ip = deviceResult.ip
            otherDevice.port = deviceResult.port
            otherDevice.channelType = deviceResult.channelType
            otherDevice.osName = deviceResult.osName
            otherDevice.networkType = deviceResult.networkType
            otherDevice.wifiName = deviceResult.wifiName
            save(otherDevice)
            CoroutineScope(Dispatchers.IO).launch {
                deviceEvent.doAction(Unit)
            }
            return@transaction otherDevice
        } else {
            return@transaction null
        }
    }
}

fun hash(inputStream: InputStream): String {
    val messageDigest = MessageDigest.getInstance("MD5")
    val byteArray = ByteArray(1024)
    var byteCount = 0
    while (run {
            byteCount = inputStream.read(byteArray)
            byteCount
    } != -1) {
        messageDigest.update(byteArray, 0, byteCount)
    }
    val bytes = messageDigest.digest()
    val sb = StringBuilder()
    for (byte in bytes) {
        sb.append(((byte and 0xff.toByte()) + 0x100).toString(16).substring(1))
    }
    return sb.toString()
}