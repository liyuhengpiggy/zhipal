package cn.seniorvoice.demo.assist

/** A's interpretation snapshot. IDs come from Android; model output never creates new targets. */
data class ACommandRequest(
    val taskId: String,
    val utterance: String,
    val apps: List<ACommandApp>,
    val contacts: List<ACommandContact>,
    val screen: ACommandScreen? = null,
    val version: Int = 1
)

data class ACommandApp(val id: String, val label: String)
data class ACommandContact(val id: String, val name: String)
data class ACommandScreen(val packageName: String, val controls: List<ACommandControl>)
data class ACommandControl(val id: String, val label: String, val actions: List<ACommandAction>)

enum class ACommandAction(val wire: String) {
    CLICK("click"), SCROLL_FORWARD("scroll_forward"), SCROLL_BACKWARD("scroll_backward")
}
enum class ACommandNavigation(val wire: String) { BACK("back"), HOME("home"), RECENTS("recents") }
enum class ACommandVisionMode { OCR, OBJECT, COMBINED }

/** Exactly one intent, requiring the host's current-task and current-page checks before execution. */
sealed interface ACommandIntent {
    data class OpenApp(val target: String) : ACommandIntent
    data class CallContact(val target: String) : ACommandIntent
    data class Navigate(val target: ACommandNavigation) : ACommandIntent
    data class ExitApp(val target: String) : ACommandIntent
    data class ScreenAction(val target: String, val action: ACommandAction) : ACommandIntent
    data class Vision(val mode: ACommandVisionMode) : ACommandIntent
    data class Clarify(val message: String) : ACommandIntent
    data class Unsupported(val message: String) : ACommandIntent
}

data class ACommandResponse(val taskId: String, val intent: ACommandIntent, val version: Int = 1)
