package util

import FileProgress
import deviceMessageDownloadEvent
import deviceMessageEvent
import httpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import logger
import model.Device
import model.DeviceMessage
import java.io.File
import java.util.*

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