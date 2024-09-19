package com.freefjay.localshare.desktop.util

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow


fun String.toUnderCase(): String {
    val len = this.length
    val res = StringBuilder(len + 2);
    var pre = '\u0000';
    val charAry = this.toCharArray()
    for ( i in 0 until len) {
        val ch = charAry[i];
        if (Character.isUpperCase(ch)) {
            if (pre != '_') {
                res.append("_");
            }
            res.append(Character.toLowerCase(ch));
        } else {
            res.append(ch);
        }
        pre = ch;
    }
    return res.toString();
}

fun String.toFirstLower(): String {
    if (this.isEmpty()) {
        return this
    }
    return this.first().lowercaseChar() + this.substring(1)
}

fun String.toDate(pattern: String = "yyyy-MM-dd HH:mm:ss"): Date {
    val simpleDateFormat = SimpleDateFormat(pattern)
    return simpleDateFormat.parse(this)
}

fun readableFileSize(size: Long?): String? {
    if (size == null) return null
    if (size <= 0) return "0"
    val units = arrayOf("B", "kB", "MB", "GB", "TB", "PB", "EB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
    return DecimalFormat("#,##0.##").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
}

fun getFileNameAndType(filename: String?): Array<String?>? {
    if (filename == null) {
        return null
    }
    val index = filename.lastIndexOf('.')
    return arrayOf(if (index > -1) filename.substring(0, index) else filename, if (index > -1) filename.substring(index + 1) else null)
}