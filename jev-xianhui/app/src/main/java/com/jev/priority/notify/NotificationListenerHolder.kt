package com.jev.priority.notify

/**
 * A one-slot bridge so the overlay can ask the notification listener to
 * dismiss a row's original notification.
 *
 * The listener is bound by the system and the overlay lives in the app process,
 * so a static reference is enough here — there is only ever one listener
 * instance. Keeping it in its own file avoids the overlay depending on the
 * listener class directly.
 */
object NotificationListenerHolder {

    @Volatile
    var instance: MsgNotificationListener? = null

    /**
     * 监听器本次启动以来收到的通知条数（过滤之前）。
     *
     * 用来把「收不到消息」拆成两种完全不同的故障：如果这个数字一直是 0，
     * 说明系统根本没把通知交给我们（绑定或权限问题）；如果数字在涨但列表
     * 不动，说明是通知内容被过滤掉了（或微信在前台压根没发通知）。
     * 只看列表是分不清这两种情况的。
     */
    @Volatile
    var seenCount: Int = 0

    /** 通过过滤、真正进入待处理列表的条数。 */
    @Volatile
    var acceptedCount: Int = 0

    /**
     * 进过列表、之后又被移出的条数。
     *
     * 这一项是诊断「在微信里收不到消息」的关键：如果它跟着 seenCount 一起涨，
     * 说明消息其实收到了、行也建了，只是紧接着被当成「已处理」删掉了 ——
     * 那问题出在移除判定上，不是收不到通知。
     */
    @Volatile
    var droppedCount: Int = 0
}
