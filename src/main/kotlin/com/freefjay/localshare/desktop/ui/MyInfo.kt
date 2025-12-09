package com.freefjay.localshare.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.freefjay.localshare.desktop.getDevice
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

@Composable
fun MyInfo() {
    Column(
        modifier = Modifier.width(250.dp).padding(top = 5.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var self by remember {
            mutableStateOf(getDevice())
        }

        fun querySelf() {
            self = getDevice()
        }

        SelectionContainer(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("name: ${self.name ?: ""}")
                Text("clientCode: ${self.clientCode ?: ""}")
                Text("ip: ${self.ip ?: ""}")
                Text("port: ${self.port ?: ""}")
                Text("channelType: ${self.channelType ?: ""}")
                Text("osName: ${self.osName ?: ""}")
                Text("networkType: ${self.networkType ?: ""}")
                Text("wifiName: ${self.wifiName ?: ""}")
            }
        }
        val url = "http://${self.ip}:${self.port}/code"
        val byteMatrix = remember(url) {
            Encoder.encode(
                url,
                ErrorCorrectionLevel.H,
                mapOf(
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to 16,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
                )
            ).matrix
        }
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        ) {
            Canvas(
                modifier = Modifier.fillMaxWidth().background(Color.Transparent)
            ) {
                byteMatrix?.let {
                    val cellSize = size.width / byteMatrix.width
                    for (x in 0 until byteMatrix.width) {
                        for (y in 0 until byteMatrix.height) {
                            drawRect(
                                color = if (byteMatrix.get(
                                        x,
                                        y
                                    ) == 1.toByte()
                                ) Color.Black else Color.White,
                                topLeft = Offset(x * cellSize, y * cellSize),
                                size = Size(cellSize, cellSize)
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = { querySelf() }) {
                Text("刷新")
            }
        }
    }
}