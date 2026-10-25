package cn.seniorvoice.demo.nextaction.domain

enum class ActionType { TAP, SCROLL_UP, SCROLL_DOWN, BACK, NO_OP }
enum class TriggerSource { APP_BUTTON, BLE, ACCESSIBILITY_BUTTON, MOCK_BUTTON }
enum class AgentState { IDLE, CAPTURING, PREDICTING, VALIDATING, EXECUTING, FINISHED }

object AgentLimits {
    const val SCHEMA_VERSION = 1
    const val MAX_NODES = 160
    const val MAX_CANDIDATES = 40
    const val MAX_HISTORY = 20
    const val MAX_JPEG_BYTES = 1024 * 1024
    const val PRACTICE_ROOT_ID = "cn.seniorvoice.demo:id/next_action_practice_root"
}

data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
    val isValid get() = left >= 0 && top >= 0 && right > left && bottom > top
    fun fits(width: Int, height: Int) = isValid && right <= width && bottom <= height
}

data class UiNodeSnapshot(
    val nodeKey: String, val className: String, val viewId: String?, val label: String,
    val bounds: Bounds, val visible: Boolean, val enabled: Boolean, val clickable: Boolean,
    val scrollable: Boolean, val password: Boolean, val editable: Boolean
)

data class UiSnapshot(
    val captureId: String, val packageName: String, val windowId: Int, val capturedAtMs: Long,
    val uiFingerprint: String, val revision: Long, val screenWidth: Int, val screenHeight: Int,
    val nodes: List<UiNodeSnapshot>
)

data class ScreenImage(val jpeg: ByteArray, val width: Int, val height: Int)
data class InteractionEvent(
    val sequence: Long, val occurredAtMs: Long, val eventType: String,
    val packageName: String, val windowId: Int, val label: String
)
data class CapturedObservation(val snapshot: UiSnapshot, val screenshot: ScreenImage, val history: List<InteractionEvent>)
data class Candidate(
    val candidateId: String, val action: ActionType, val targetLabel: String,
    val nodeKey: String? = null, val className: String? = null,
    val viewId: String? = null, val bounds: Bounds? = null
)
data class ObservationRequest(
    val requestId: String, val captured: CapturedObservation, val candidates: List<Candidate>,
    val schemaVersion: Int = AgentLimits.SCHEMA_VERSION
)
data class Decision(val action: ActionType, val candidateId: String, val confidence: Double, val reason: String)
data class PredictionResponse(val schemaVersion: Int, val requestId: String, val captureId: String, val decision: Decision)

fun interface AgentClock { fun nowMs(): Long }

interface ObservationSource {
    /** Blocking operation. Called on the coordinator worker, never on the Android main thread. */
    fun capture(requestId: String): CapturedObservation
    fun currentSnapshot(): UiSnapshot
    fun currentRevision(): Long
}

interface PredictionService {
    val isMock: Boolean get() = false
    /** One call only; implementations must not perform an automatic model retry. */
    fun predict(request: ObservationRequest): PredictionResponse
    /** Must return promptly; late predict results are separately invalidated by the gate. */
    fun cancel(requestId: String) {}
}

fun interface ActionExecutor {
    /** Revalidate on Android main, then permit.claim() immediately before one platform action. */
    fun execute(request: ExecutionRequest): ExecutionResult
}
data class ExecutionRequest(
    val requestId: String, val originalSnapshot: UiSnapshot, val candidate: Candidate,
    val deadlineMs: Long, val permit: ExecutionGate.Permit
)
data class ExecutionResult(val attempted: Boolean, val succeeded: Boolean, val message: String)
data class TriggerResult(val accepted: Boolean, val requestId: String?, val message: String)
data class AgentDiagnostic(
    val state: AgentState, val source: TriggerSource?, val requestId: String?, val message: String,
    val packageName: String? = null, val candidates: List<Candidate> = emptyList(),
    val decision: Decision? = null, val elapsedMs: Long = 0, val result: ExecutionResult? = null,
    val predictionElapsedMs: Long? = null
)
