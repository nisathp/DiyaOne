package com.naminfo.ui.main.chat

internal fun resolveComposingNames(
    participantNames: List<String>,
    isOneToOne: Boolean,
    peerDisplayName: String?
): List<String> {
    val names = participantNames.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.isNotEmpty() || !isOneToOne) return names

    return listOfNotNull(peerDisplayName?.trim()?.takeIf { it.isNotEmpty() })
}

internal fun resolveConversationDisplayName(
    conversationSubject: String?,
    contactName: String?,
    addressDisplayName: String?,
    username: String?,
    domain: String?
): String {
    val cleanUsername = username.orEmpty().trim()
    val subject = conversationSubject?.trim()?.takeIf { it.isNotEmpty() }
    val contact = contactName?.trim()?.takeIf {
        it.isNotEmpty() && !com.naminfo.contacts.PhoneContactNumbers.isNumericPlaceholder(it, cleanUsername)
    }
    val addrName = addressDisplayName?.trim()?.takeIf {
        it.isNotEmpty() && !com.naminfo.contacts.PhoneContactNumbers.isNumericPlaceholder(it, cleanUsername)
    }

    return sequenceOf(subject, contact, addrName, cleanUsername, domain)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        .orEmpty()
}
