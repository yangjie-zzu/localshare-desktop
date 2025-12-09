import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.darkrockstudios.libraries.mpfilepicker.FilePicker
import com.freefjay.localshare.desktop.*
import com.freefjay.localshare.desktop.component.ActionButton
import com.freefjay.localshare.desktop.component.CustomContextMenu
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.model.DeviceMessage
import com.freefjay.localshare.desktop.model.DeviceMessageParams
import com.freefjay.localshare.desktop.ui.Chat
import com.freefjay.localshare.desktop.util.*
import com.google.gson.Gson
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import com.freefjay.localshare.desktop.ui.DeviceList
import com.freefjay.localshare.desktop.ui.MyInfo
import com.freefjay.localshare.desktop.ui.VerticalDivider
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.util.*

@Composable
@Preview
fun App() {

    var activeDevice by remember {
        mutableStateOf<Device?>(null)
    }

    MaterialTheme {
        Column {
            Row {
                DeviceList(
                    activeDevice,
                    onRowClick = {
                        activeDevice = it
                    },
                    onDelete = {
                        activeDevice = null
                    }
                )
                VerticalDivider()
                Chat(
                    modifier = Modifier.weight(1f).sizeIn(minWidth = 500.dp).padding(start = 10.dp, end = 10.dp),
                    activeDevice = activeDevice,
                )
                VerticalDivider()
                MyInfo()
            }
        }
    }
}
