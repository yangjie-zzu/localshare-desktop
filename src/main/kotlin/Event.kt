import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

class Event {
    private val actions = mutableListOf<() -> Unit>()
    fun doAction() {
        actions.forEach {
            it()
        }
    }
    fun registerAction(onAction: () -> Unit): () -> Unit {
        actions.add(onAction)
        return {
            actions.remove(onAction)
        }
    }
}
val deviceEvent = Event()

val deviceMessageEvent = Event()

@Composable
fun onEvent(event: Event, block: () -> Unit) {
    DisposableEffect(event) {
        val removeAction = event.registerAction {
            block()
        }
        onDispose {
            removeAction()
        }
    }
}