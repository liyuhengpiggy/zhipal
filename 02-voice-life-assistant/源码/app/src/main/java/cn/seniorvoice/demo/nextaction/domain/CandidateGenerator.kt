package cn.seniorvoice.demo.nextaction.domain

class CandidateGenerator(private val policy: ActionPolicy = ActionPolicy()) {
    fun generate(snapshot: UiSnapshot): List<Candidate> {
        val noOp = Candidate("no_op", ActionType.NO_OP, "不操作")
        if (!policy.allowsObservation(snapshot)) return listOf(noOp)
        val candidates = mutableListOf<Candidate>()
        val unique = snapshot.nodes.groupingBy { it.nodeKey }.eachCount()
        fun add(action: ActionType, node: UiNodeSnapshot) {
            if (candidates.size >= AgentLimits.MAX_CANDIDATES - 2) return
            val candidate = Candidate("c${candidates.size + 1}", action, node.label.ifBlank { "可滚动区域" },
                node.nodeKey, node.className, node.viewId, node.bounds)
            if (policy.allows(candidate, snapshot)) candidates.add(candidate)
        }
        for (node in snapshot.nodes.take(AgentLimits.MAX_NODES)) {
            if (unique[node.nodeKey] != 1) continue
            if (policy.allowsNode(node)) add(ActionType.TAP, node)
            if (node.scrollable) { add(ActionType.SCROLL_UP, node); add(ActionType.SCROLL_DOWN, node) }
        }
        candidates.add(Candidate("back", ActionType.BACK, "返回"))
        candidates.add(noOp)
        return candidates.toList()
    }
}
