package com.freefjay.localshare.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.freefjay.localshare.desktop.OnEvent
import com.freefjay.localshare.desktop.deviceEvent
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.util.delete
import com.freefjay.localshare.desktop.util.queryList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.floor

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DeviceList(
    activeDevice: Device?,
    onRowClick: (device: Device) -> Unit,
    onDelete: (device: Device) -> Unit
) {
    var devices by remember {
        mutableStateOf<List<Device>>(listOf())
    }
    val currentCoroutineScope = rememberCoroutineScope()
    val requestDevices = suspend {
        val list = queryList<Device>("select * from device")
        devices = list
    }
    LaunchedEffect(Unit) {
        requestDevices()
    }
    OnEvent(deviceEvent) {
//        logger.info("订阅设备事件")
        CoroutineScope(Dispatchers.IO).launch {
            requestDevices()
        }
    }
    Column(modifier = Modifier.width(200.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "设备数量: ${devices.size}")
            Button(onClick = {
                currentCoroutineScope.launch {
                    requestDevices()
                }
            }) {
                Text(text = "刷新")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            itemsIndexed(devices, { _, it -> it.id ?: "" }) { _, item ->
                var offsetX by remember {
                    mutableStateOf(0f)
                }
                var offsetY by remember {
                    mutableStateOf(0f)
                }
                var showMenu by remember {
                    mutableStateOf(false)
                }
                Row(
                    modifier = Modifier.clickable {
                        onRowClick(item)
                    }.fillMaxWidth()
                        .background(color = if (activeDevice?.id == item.id) Color.LightGray else Color.Transparent)
                        .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) {
                            showMenu = true
                        }.onPointerEvent(eventType = PointerEventType.Press) {
                            val position = it.changes.first().position
                            offsetX = position.x
                            offsetY = position.y
                        }.padding(5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showMenu) {
                        Popup(
                            onDismissRequest = { showMenu = false },
                            offset = IntOffset(offsetX.toInt(), offsetY.toInt())
                        ) {
                            Column(
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color = Color.White)
                                    .border(
                                        border = BorderStroke(width = 1.dp, color = Color(0, 0, 0, 20)),
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.clickable {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            delete<Device>(item.id)
                                            requestDevices()
                                        }
                                        onDelete(item)
                                    }.padding(5.dp)
                                ) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    Box {
                        Image(
                            modifier = Modifier.size(width = 40.dp, height = 40.dp).padding(end = 8.dp),
                            painter = if (item.osName?.lowercase()?.startsWith("windows") == true) {
                                painterResource("windows.svg")
                            } else if (item.osName?.lowercase() == "android") {
                                painterResource("安卓.svg")
                            } else {
                                painterResource("")
                            },
                            contentDescription = item.osName
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("${item.name}")
                        Text("${item.ip}:${item.port}", fontSize = 12.sp)
                        val now = System.currentTimeMillis()
                        val lastTime = item.lastTime
                        Text(
                            if (lastTime == null) {
                                ""
                            } else {
                                val delta = now - lastTime
                                if (delta <= 6000) {
                                    "最近"
                                } else if (delta < 60 * 60 * 1000) {
                                    "${floor((delta / (60 * 1000)).toDouble()).toInt()}分钟前"
                                } else if (delta < 24 * 60 * 60 * 1000) {
                                    "${floor((delta / (60 * 60 * 1000)).toDouble()).toInt()}小时前"
                                } else {
                                    "${floor((delta / (24 * 60 * 60 * 1000)).toDouble()).toInt()}天前"
                                }
                            }, fontSize = 10.sp)
                    }
                }
                Divider()
            }
        }
    }
}