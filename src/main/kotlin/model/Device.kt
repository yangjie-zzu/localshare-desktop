package model

import util.Column

data class Device(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var clientId: String? = null,
    var name: String? = null,
    var ip: String? = null,
    var port: Int? = null,
    var channelType: String? = null,
    var osName: String? = null,
    var networkType: String? = null,
    var wifiName: String? = null
)