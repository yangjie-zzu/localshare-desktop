package com.freefjay.localshare.desktop.model

import com.freefjay.localshare.desktop.util.Column

data class SysInfo(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var name: String? = null,
    var value: String? = null
)