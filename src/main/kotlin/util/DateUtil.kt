package util

import org.jetbrains.skia.Pattern
import java.text.SimpleDateFormat
import java.util.Date

fun Date.format(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    val simpleDateFormat = SimpleDateFormat(pattern)
    return simpleDateFormat.format(this)
}