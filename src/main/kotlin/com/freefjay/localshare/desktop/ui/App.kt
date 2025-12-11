import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freefjay.localshare.desktop.model.Device
import com.freefjay.localshare.desktop.ui.Chat
import com.freefjay.localshare.desktop.ui.DeviceList
import com.freefjay.localshare.desktop.ui.MyInfo
import com.freefjay.localshare.desktop.ui.VerticalDivider

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
