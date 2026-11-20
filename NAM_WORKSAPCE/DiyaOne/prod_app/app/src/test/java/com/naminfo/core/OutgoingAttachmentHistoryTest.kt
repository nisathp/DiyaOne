package com.naminfo.core

import android.content.Context
import android.content.SharedPreferences
import com.naminfo.DiyaOneApplication
import com.naminfo.R
import com.naminfo.utils.LinphoneUtils
import io.mockk.EqMatcher
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.linphone.core.Address
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom

class OutgoingAttachmentHistoryTest {
    @get:Rule
    val files = TemporaryFolder()

    private val stableId = "attachment-stable-id"
    private val nativeId = "attachment-native-id"
    private val local = "5550000123"
    private val peer = "5550000456"

    @After
    fun clearJsonStubs() {
        unmockkConstructor(JSONArray::class, JSONObject::class)
    }

    @Test
    fun reopenedNativeRowRecoversTheAttachmentAndSentTickFromItsNativeId() {
        val (message, _) = restoredMessage()

        assertTrue(LinphoneUtils.isDisplayableChatMessage(message))
        val attachment = OfflineInboxRepository.outgoingHistoryMedia(message).single()
        assertEquals("photo.jpg", attachment.name)
        assertTrue(attachment.localPath.orEmpty().endsWith("photo.jpg"))
        assertEquals(stableId, OfflineInboxRepository.outgoingHistoryId(message))
        assertEquals(R.drawable.check, LinphoneUtils.getChatIconResId(CustomImdn.effectiveState(message)))
    }

    @Test
    fun deletingTheReopenedRowRemovesItsSnapshotAndStoresBothIdentifiers() {
        val (message, editor) = restoredMessage()
        mockkConstructor(JSONObject::class)
        every { anyConstructed<JSONObject>().put(any(), any<String>()) } answers { self as JSONObject }
        every { anyConstructed<JSONObject>().put(any(), any<Long>()) } answers { self as JSONObject }

        val deletedIds = OfflineInboxRepository.markMessageDeletedLocally(message)

        assertEquals(setOf(stableId, nativeId), deletedIds)
        verify { anyConstructed<JSONObject>().put("id", stableId) }
        verify { anyConstructed<JSONObject>().put("id", nativeId) }
        verify { editor.putString("items", "[]") }
        verify { editor.putString("deleted_message_tombstones", any()) }
    }

    private fun restoredMessage(): Pair<ChatMessage, SharedPreferences.Editor> {
        val photo = files.newFile("photo.jpg").apply { writeText("image bytes") }
        val attachment = mockk<JSONObject>(relaxed = true) {
            every { optString("name", any()) } returns "photo.jpg"
            every { optString("localPath") } returns photo.absolutePath
            every { optString("mimeType", any()) } returns "image/jpeg"
        }
        val media = mockk<JSONArray> {
            every { length() } returns 1
            every { optJSONObject(0) } returns attachment
        }
        val row = mockk<JSONObject>(relaxed = true) {
            every { optBoolean("localOutgoing", false) } returns true
            every { optString("localRecipient") } returns local
            every { optString("sender") } returns peer
            every { optString("id") } returns stableId
            every { optString("nativeMessageId") } returns nativeId
            every { optString("deliveryStatus") } returns "sent"
            every { optJSONArray("media") } returns media
        }
        val raw = "outgoing-history-fixture-${photo.absolutePath}"
        mockkConstructor(JSONArray::class)
        every { anyConstructed<JSONArray>().length() } returns 0
        every { anyConstructed<JSONArray>().toString() } returns "[]"
        every { constructedWith<JSONArray>(EqMatcher(raw)).length() } returns 1
        every { constructedWith<JSONArray>(EqMatcher(raw)).optJSONObject(0) } returns row

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("items", null) } returns raw
            every { getString("items", "[]") } returns raw
            every { getString("deleted_message_tombstones", null) } returns null
            every { edit() } returns editor
        }
        val appContext = mockk<Context> {
            every { getSharedPreferences("offline_inbox", Context.MODE_PRIVATE) } returns prefs
        }
        DiyaOneApplication.coreContext = mockk {
            every { context } returns appContext
        }

        val senderAddress = mockk<Address> { every { username } returns local }
        val receiverAddress = mockk<Address> { every { username } returns peer }
        val room = mockk<ChatRoom> {
            every { localAddress } returns senderAddress
            every { peerAddress } returns receiverAddress
        }
        val message = mockk<ChatMessage>(relaxed = true) {
            every { isOutgoing } returns true
            every { fromAddress } returns senderAddress
            every { chatRoom } returns room
            every { messageId } returns nativeId
            every { state } returns ChatMessage.State.Idle
            every { contents } returns emptyArray()
        }
        return message to editor
    }
}
