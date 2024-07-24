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