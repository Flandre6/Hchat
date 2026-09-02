package h.Hchat.hooks.items.selectedmessages

internal object ForwardSendPolicy {
    const val MIN_SEND_INTERVAL_MS = 500L
    const val RETRANSMIT_TARGET_BATCH_SIZE = 10
    const val RETRANSMIT_BATCH_INTERVAL_MS = 750L
}
