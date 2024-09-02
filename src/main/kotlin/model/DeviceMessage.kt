package model

import util.Column
import java.util.Date

data class DeviceMessage(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var createdTime: Date? = null,
    var deviceId: Long? = null,
    var type: String? = null,
    var content: String? = null,
    var filepath: String? = null,
    var filename: String? = null,
    var size: Long? = null,
    var oppositeId: Long? = null,
    var sendSuccess: Boolean? = null,
    var downloadSuccess: Boolean? = null,
    var downloadSize: Long? = null,
    var savePath: String? = null
)

data class DeviceMessageParams(
    var sendId: Long?,
    var clientCode: String?,
    var content: String?,
    var filename: String?,
    var size: Long? = null
)