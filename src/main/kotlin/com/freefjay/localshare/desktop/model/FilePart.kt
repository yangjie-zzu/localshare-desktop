package com.freefjay.localshare.desktop.model

import com.freefjay.localshare.desktop.util.Column

data class FilePart(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var deviceMessageId: Long? = null,
    var fileHash: String? = null,
    var start: Long? = null,
    var end: Long? = null
)