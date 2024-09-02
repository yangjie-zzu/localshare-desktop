import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import model.Device
import model.DeviceMessage
import org.slf4j.LoggerFactory
import util.queryList
import util.queryOne
import util.save
import java.io.File

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
                    val (_, clientCode, name, ip, port, channelType, osName, networkType, wifiName) = call.receive<Device>()
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
                        deviceEvent.doAction()
                    }
                    call.respond(getDevice())
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
}