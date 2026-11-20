package com.naminfo.utils

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.linphone.core.Account
import org.linphone.core.Address

class HistoryAccountOwnershipTest {
    @Test
    fun storedHistoryFollowsThePhoneIdentityAcrossLogins() {
        val storedOwner = address("5550000123")
        val otherAccount = account(address("5550000124"))
        val originalAccountAfterRelogin = account(address("+915550000123"))

        assertFalse(LinphoneUtils.isHistoryOwnedBy(storedOwner, otherAccount))
        assertTrue(LinphoneUtils.isHistoryOwnedBy(storedOwner, originalAccountAfterRelogin))
    }

    @Test
    fun historyRequiresAnActiveAccountAndAnIdentifiableOwner() {
        val owner = address("5550000123")
        assertFalse(LinphoneUtils.isHistoryOwnedBy(owner, null))
        assertFalse(LinphoneUtils.isHistoryOwnedBy(null, account(owner)))
        assertFalse(LinphoneUtils.isHistoryOwnedBy(address("operator5550000123"), account(owner)))
    }

    private fun account(identity: Address): Account = mockk {
        every { params.identityAddress } returns identity
    }

    private fun address(user: String): Address = mockk {
        every { username } returns user
        every { weakEqual(any()) } returns false
    }
}
