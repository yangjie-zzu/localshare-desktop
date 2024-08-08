package model

import util.Column

data class SysInfo(
    @Column(isPrimaryKey = true)
    var id: Long? = null,
    var name: String? = null,
    var value: String? = null
)