package cn.seniorvoice.demo.nextaction.trigger

import android.os.Handler
import android.os.Looper
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.domain.TriggerSource
import cn.seniorvoice.demo.nextaction.domain.TriggerResult

/** Delay belongs to the explicit UI trigger, not a second Agent request. */
class AppButtonTrigger {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    fun schedule(mock: Boolean, callback: (TriggerResult) -> Unit): Boolean {
        if (pending != null || NextActionRuntime.isBusy) return false
        val task = Runnable {
            pending = null
            callback(NextActionRuntime.trigger(if (mock) TriggerSource.MOCK_BUTTON else TriggerSource.APP_BUTTON))
        }
        pending = task; main.postDelayed(task, 3000)
        return true
    }
    fun cancel() { pending?.let(main::removeCallbacks); pending = null; NextActionRuntime.cancel("用户取消") }
}
