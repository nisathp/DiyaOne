package com.naminfo.ui.main.chat

class ChatRegressionTest {
   /* @Test
    fun composingIndicatorFallsBackToPeerNameWhenParticipantListIsEmpty() {
        assertEquals(
            listOf("B Party"),
            resolveComposingNames(emptyList(), isOneToOne = true, peerDisplayName = "B Party")
        )
    }

    @Test
    fun remoteComposingCallbackDrivesNamesUntilParticipantStops() {
        val state = RemoteComposingState()

        assertEquals(null, state.participantNamesOrNull())

        state.update("sip:7339318185@example.test", "Vivo", isComposing = true)
        assertEquals(listOf("Vivo"), state.participantNamesOrNull())

        state.update("sip:7339318185@example.test", "Vivo", isComposing = false)
        assertEquals(emptyList<String>(), state.participantNamesOrNull())
    }

    @Test
    fun composingIndicatorDoesNotUsePeerFallbackForGroups() {
        assertEquals(
            emptyList<String>(),
            resolveComposingNames(emptyList(), isOneToOne = false, peerDisplayName = "B Party")
        )
    }

    @Test
    fun suggestionPrefersContactNameOverSipUsername() {
        assertEquals(
            "B Party",
            resolveConversationDisplayName(
                conversationSubject = null,
                contactName = null,
                addressDisplayName = "B Party",
                username = "7339318185",
                domain = "192.168.1.82"
            )
        )
    }

    @Test
    fun suggestionUsesUsernameWhenNoDisplayNameExists() {
        assertEquals(
            "7339318185",
            resolveConversationDisplayName(
                conversationSubject = null,
                contactName = null,
                addressDisplayName = null,
                username = "7339318185",
                domain = "192.168.1.82"
            )
        )
    }

    @Test
    fun inFlightSendOrRoomCreationRejectsDuplicateRequestsUntilCompleted() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())

        gate.complete()

        assertTrue(gate.tryEnter())
    }

    @Test
    fun replyAndTypedTextAreAddedToTheSameOutgoingMessage() {
        val chatRoom = mockk<ChatRoom>()
        val originalMessage = mockk<ChatMessage>()
        val replyMessage = mockk<ChatMessage>(relaxed = true)
        every { chatRoom.createReplyMessage(originalMessage) } returns replyMessage
        every { originalMessage.messageId } returns "original-message-id"
        every { originalMessage.contents } returns emptyArray()
        every { originalMessage.fromAddress.displayName } returns "Alice"
        every { originalMessage.fromAddress.username } returns "alice"

        val outgoingMessage = createOutgoingTextMessage(
            chatRoom,
            replyTo = originalMessage,
            edit = null,
            text = "typed follow-up"
        )

        assertSame(replyMessage, outgoingMessage)
        verify(exactly = 1) { chatRoom.createReplyMessage(originalMessage) }
        verify(exactly = 1) {
            replyMessage.addCustomHeader(DIYA_REPLY_MESSAGE_ID_HEADER, "original-message-id")
        }
        verify(exactly = 1) {
            replyMessage.addUtf8TextContent(
                match { body ->
                decodeReplyPreviewEnvelope(body)?.let { (preview, text) ->
                    preview.messageId == "original-message-id" &&
                        preview.sender == "Alice" && text == "typed follow-up"
                } == true
            }
            )
        }
        verify(exactly = 0) { chatRoom.createEmptyMessage() }
    }

    @Test
    fun replyPreviewEnvelopeSurvivesTransportWithoutShowingMetadataInMessageText() {
        val encoded = encodeReplyPreviewEnvelope(
            messageId = "id-123",
            sender = "அம்மா",
            previewText = "வணக்கம், hello",
            messageText = "reply text"
        )

        val decoded = decodeReplyPreviewEnvelope(encoded.trim())

        assertEquals(ReplyPreviewEnvelope("id-123", "அம்மா", "வணக்கம், hello"), decoded?.first)
        assertEquals("reply text", decoded?.second)
        val metadataOnly = decodeReplyPreviewEnvelope(
            encodeReplyPreviewEnvelope("id-only", "Bob", "quote", "").trim()
        )
        assertEquals("", metadataOnly?.second)
        assertNull(decodeReplyPreviewEnvelope("ordinary message"))
    }

    @Test
    fun replyPreviewUsesCustomTargetWhenNativeReplyIdIsMissing() {
        assertEquals("original-message-id", resolveReplyMessageId(null, "original-message-id"))
        assertEquals("native-id", resolveReplyMessageId("native-id", "custom-id"))
        assertNull(resolveReplyMessageId(null, null))
        assertTrue(hasReplyTarget(nativeIsReply = false, replyMessageId = "original-message-id"))
        assertFalse(hasReplyTarget(nativeIsReply = false, replyMessageId = null))
    }

    @Test
    fun visibleUnreadCountIgnoresReceiptAndOutgoingMessages() {
        val visibleIncoming = mockk<ChatMessage>()
        every { visibleIncoming.isRead } returns false
        every { visibleIncoming.isOutgoing } returns false
        every { visibleIncoming.utf8Text } returns "hello"
        every { visibleIncoming.appdata } returns null
        every { visibleIncoming.getCustomHeader("X-Diya-Message-ID") } returns null

        val receipt = mockk<ChatMessage>()
        every { receipt.isRead } returns false
        every { receipt.isOutgoing } returns false
        every { receipt.utf8Text } returns "DIYA_RECEIPT_V1\n{\"status\":\"delivered\"}"
        every { receipt.appdata } returns null
        every { receipt.getCustomHeader("X-Diya-Message-ID") } returns null

        val outgoing = mockk<ChatMessage>()
        every { outgoing.isRead } returns false
        every { outgoing.isOutgoing } returns true
        every { outgoing.utf8Text } returns "sent by me"
        every { outgoing.appdata } returns null
        every { outgoing.getCustomHeader("X-Diya-Message-ID") } returns null

        assertEquals(1, CustomImdn.visibleUnreadMessagesCount(listOf(visibleIncoming, receipt, outgoing)))
    }

    @Test
    fun composingControlMessageIsRecognizedAndExcludedFromUnreadCount() {
        val composing = mockk<ChatMessage>()
        every { composing.isRead } returns false
        every { composing.isOutgoing } returns false
        every { composing.utf8Text } returns "DIYA_COMPOSING_V1\n{\"state\":\"active\"}"

        assertTrue(CustomComposing.isControlMessage(composing))
        assertTrue(CustomImdn.isHiddenControlMessage(composing))
        assertEquals(0, CustomImdn.visibleUnreadMessagesCount(listOf(composing)))
    }

    @Test
    fun visibleUnreadCountCountsOneStableMessageIdOnlyOnce() {
        val firstDelivery = mockk<ChatMessage>()
        every { firstDelivery.isRead } returns false
        every { firstDelivery.isOutgoing } returns false
        every { firstDelivery.utf8Text } returns "hello"
        every { firstDelivery.appdata } returns null
        every { firstDelivery.getCustomHeader("X-Diya-Message-ID") } returns
            "ac3d0d4a-c8f2-4cc9-b02c-656cd028ab2b"

        val duplicateDelivery = mockk<ChatMessage>()
        every { duplicateDelivery.isRead } returns false
        every { duplicateDelivery.isOutgoing } returns false
        every { duplicateDelivery.utf8Text } returns "hello"
        every { duplicateDelivery.appdata } returns null
        every { duplicateDelivery.getCustomHeader("X-Diya-Message-ID") } returns
            "ac3d0d4a-c8f2-4cc9-b02c-656cd028ab2b"

        assertEquals(
            1,
            CustomImdn.visibleUnreadMessagesCount(listOf(firstDelivery, duplicateDelivery))
        )
    }*/
}
