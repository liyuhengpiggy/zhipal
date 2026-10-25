package cn.seniorvoice.demo.nextaction.trigger

import cn.seniorvoice.demo.DeviceEvent
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.domain.TriggerResult
import cn.seniorvoice.demo.nextaction.domain.TriggerSource

object BleTriggerAdapter {
    fun matches(event: DeviceEvent) = event.type == "complete" || (event.type == "command" && event.text.trim() == "COMPLETE")
    fun trigger(): TriggerResult = NextActionRuntime.trigger(TriggerSource.BLE)
}
