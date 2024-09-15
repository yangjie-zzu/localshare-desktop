package util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.google.gson.Gson
import deviceEvent
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logger
import model.*
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*
import kotlin.experimental.and
import kotlin.math.min

class FileProgress(val messageId: Long?, val handleSize: Long)

val fileProgresses = mutableMapOf<Long?, FileProgress?>()
val fileProgressMutex = Mutex()

suspend fun downloadMessageFile(device: Device?, deviceMessage: DeviceMessage) {
    val filename = deviceMessage.filename
    if (device != null && filename != null) {
        try {
            fileProgressMutex.withLock {
                if (fileProgresses[deviceMessage.id] != null) {
                    logger.info("正在下载, ${deviceMessage.id}")
                    return
                }
                fileProgresses[deviceMessage.id] = FileProgress(
                    messageId = deviceMessage.id,
                    handleSize = 0
                )
            }
            val downloadInfo = run {
                val call = httpClient.get("http://${device.ip}:${device.port}/downloadInfo?messageId=${deviceMessage.oppositeId}").call
                val status = call.response.status
                if (status == HttpStatusCode.OK) {
                    Gson().fromJson(call.response.bodyAsText(), DownloadInfo::class.java)
                } else {
                    null
                }
            }
            if (downloadInfo == null) {
                logger.info("downloadMessageFile: 下载失败")
                return
            }
            var downloadSize = run {
                if (downloadInfo.hash == deviceMessage.fileHash) {
                    deviceMessage.downloadSize ?: 0L
                } else {
                    0L
                }
            }
            val totalSize = downloadInfo.size
            deviceMessage.fileHash = downloadInfo.hash
            deviceMessage.size = totalSize
            deviceMessage.downloadSize = downloadSize
            logger.info("已下载: ${downloadSize}, ${downloadInfo.hash}, ${deviceMessage.fileHash}")
            val file = run {
                deviceMessage.savePath.let { path ->
                    if (downloadSize > 0 && path != null) {
                        File(path)
                    } else {
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
                }
            }
            logger.info("保存: ${file.absolutePath}")
            deviceMessage.savePath = file.absolutePath
            save(deviceMessage)
            deviceMessageEvent.doAction(deviceMessage)
            val startTime = Date()
            var processSize = downloadSize
            var handleSize = 0L
            val chunkSize = queryOne<SysInfo>("select * from sys_info where name = 'chunkSize'")?.value?.toLong()
                ?: (10 * 1024 * 1024L)
            val concurrentCount = queryOne<SysInfo>("select * from sys_info where name = 'concurrentCount'")?.value?.toLong()
                ?: 10
            val fileParts = queryList<FilePart>("select * from file_part where device_message_id = ${deviceMessage.id} and file_hash = '${downloadInfo.hash}'")
            val filePartMap = fileParts.groupBy { "${it.start}-${it.end}" }
            val mutex = Mutex()
            val processMutex = Mutex()
            val saveMutex = Mutex()
            suspend fun getRange(): Pair<Long, Long>? {
                mutex.withLock {
                    return if (handleSize < totalSize) {
                        val range = Pair(handleSize, min(handleSize + chunkSize - 1, totalSize - 1))
                        handleSize += range.second - range.first + 1
                        logger.info("还有可下载数据, handleSize: ${handleSize}")
                        range
                    } else {
                        logger.info("没有可下载数据, handleSize: ${handleSize}")
                        null
                    }
                }
            }
            withContext(Dispatchers.IO) {
                val fileChannel = RandomAccessFile(file, "rws").also {
                    it.setLength(totalSize)
                }.channel
                fileChannel.use {
                    (1..concurrentCount).map {
                        async {
                            withContext(Dispatchers.IO) {
                                var range = getRange()
                                while (range != null) {
                                    val start = range.first
                                    val end = range.second
                                    if (filePartMap["${start}-${end}"] == null) {
                                        var subHandleSize = 0L
                                        httpClient.prepareGet("http://${device.ip}:${device.port}/download?messageId=${deviceMessage.oppositeId}") {
                                            timeout {
                                                connectTimeoutMillis = 300000
                                                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                                            }
                                            header("Range", "bytes=${start}-${end}")
                                        }.execute { response ->
                                            val contentLength = response.headers[HttpHeaders.ContentLength]?.toLong() ?: 0L
                                            withContext(Dispatchers.IO) {
                                                val channel = response.bodyAsChannel()
                                                while (!channel.isClosedForRead) {
                                                    val packet =
                                                        channel.readRemaining(limit = DEFAULT_BUFFER_SIZE.toLong())
                                                    while (!packet.isEmpty) {
                                                        val bytes = packet.readBytes()
                                                        fileChannel.write(ByteBuffer.wrap(bytes), start + subHandleSize)
                                                        subHandleSize += bytes.size
                                                        processMutex.withLock {
                                                            processSize += bytes.size
                                                            fileProgresses[deviceMessage.id] = FileProgress(
                                                                messageId = deviceMessage.id,
                                                                handleSize = processSize
                                                            )
                                                        }
                                                    }
                                                }
                                                if (subHandleSize != contentLength) {
                                                    throw RuntimeException("下载失败，流提前结束")
                                                }
                                            }
                                        }
                                        fileChannel.force(true)
                                        saveMutex.withLock {
                                            downloadSize += subHandleSize
                                            deviceMessage.downloadSize = downloadSize
                                            save(deviceMessage)
                                            save(FilePart(
                                                deviceMessageId = deviceMessage.id,
                                                fileHash = downloadInfo.hash,
                                                start = start,
                                                end = end
                                            ))
                                        }
                                    } else {
                                        logger.info("已下载: ${start}, ${end}")
                                    }
                                    range = getRange()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            val saveHash = withContext(Dispatchers.IO) {
                FileInputStream(file).use {
                    hash(it)
                }
            }
            logger.info("下载用时: ${(Date().time - startTime.time)/1000}s")
            if (saveHash != downloadInfo.hash) {
                deviceMessage.downloadSize = 0L
                throw RuntimeException("数据完整性验证不通过: ${saveHash}, ${downloadInfo.hash}")
            }
            deviceMessage.downloadSuccess = true
            deviceMessage.downloadSize = downloadSize
            save(deviceMessage)
            fileProgresses[deviceMessage.id] = null
            deviceMessageEvent.doAction(deviceMessage)
        } catch (e : Exception) {
            logger.info("下载失败: ", e)
            fileProgresses[deviceMessage.id] = null
            deviceMessageEvent.doAction(deviceMessage)
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

@Composable
fun OnTimer(delay: Long = 500, block: suspend () -> Unit) {
    val currentCoroutineScope = rememberCoroutineScope()
    LaunchedEffect(delay, block = {
        currentCoroutineScope.launch {
            while (true) {
                block()
                delay(delay)
            }
        }
    })
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