package cn.seniorvoice.demo.nextaction.network

import cn.seniorvoice.demo.nextaction.domain.*

/** Deterministic, never a cloud substitute. Domain coordinator limits this to the practice marker. */
class MockPredictionService : PredictionService {
    override val isMock = true
    override fun predict(request: ObservationRequest): PredictionResponse {
        val candidate = request.candidates.firstOrNull { it.action == ActionType.TAP && it.targetLabel == "测试下一页" }
            ?: request.candidates.single { it.action == ActionType.NO_OP }
        return PredictionResponse(1, request.requestId, request.captured.snapshot.captureId,
            Decision(candidate.action, candidate.candidateId, 1.0,
                if (candidate.action == ActionType.NO_OP) "练习页没有测试下一页按钮，停止" else "模拟模式只点击练习页的测试下一页一次"))
    }
}
