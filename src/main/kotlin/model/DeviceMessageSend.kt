package model

import util.Column

data class DeviceMessageSend(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var deviceMessageId: Long? = null,
    var filepath: String? = null,
    var clientId: String? = null,
    var success: Boolean? = null
)