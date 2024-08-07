package model

import util.Column
import java.util.Date

data class DeviceMessage(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var deviceId: Long? = null,
    var createdTime: Date? = null,
    var type: String? = null,
    var dataType: String? = null,
    var content: String? = null,
    var filename: String? = null,
    var seen: Boolean? = null
)