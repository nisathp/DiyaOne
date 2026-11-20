package com.naminfo.ui.main.chat

import com.naminfo.ui.main.chat.adapter.ConversationEventAdapter
import com.naminfo.ui.main.chat.model.EventLogModel
import com.naminfo.ui.main.chat.model.MessageModel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEventIdentityTest {
    private val diff = ConversationEventAdapter.EventLogDiffCallback()

    @Test
    fun outgoingMessageKeepsItsIdentityWhenHistoryReplacesTheOptimisticRow() {
        val optimistic = outgoing("diya-id-one", "", 1_794_400_200_001L)
        val restored = outgoing("diya-id-one", "sip-id-one", 1_794_400_200_000L)

        assertTrue(diff.areItemsTheSame(optimistic, restored))
    }

    @Test
    fun separateAttachmentsKeepDistinctIdentitiesEvenWithTheSameTimestamp() {
        val first = outgoing("diya-id-one", "", 1_794_400_200_000L)
        val second = outgoing("diya-id-two", "", 1_794_400_200_000L)

        assertFalse(diff.areItemsTheSame(first, second))
    }

    private fun outgoing(stableId: String, nativeId: String, sentAt: Long): EventLogModel {
        val message = mockk<MessageModel> {
            every { customDiyaId } returns stableId
            every { id } returns nativeId
            every { timestampMillis } returns sentAt
            every { isOutgoing } returns true
        }
        return mockk<EventLogModel> {
            every { isEvent } returns false
            every { model } returns message
        }
    }
}
