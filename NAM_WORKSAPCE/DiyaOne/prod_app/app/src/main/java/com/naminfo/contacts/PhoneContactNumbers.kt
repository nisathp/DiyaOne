// Matching only: never use the normalized key to rewrite a SIP destination.

package com.naminfo.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SubscriptionManager

object PhoneContactNumbers {
    internal data class SavedNameEntry(
        val name: String,
        val isSim: Boolean
    )

    fun normalize(number: String): String {
        val digits = number.filter(Char::isDigit)
        return when {
            digits.length == 14 && digits.startsWith("0091") -> digits.drop(4)
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Returns true when [candidate] is blank or carries only digits equal to [rawNumberOrUsername]
     * (or any phone-number-only placeholder when [rawNumberOrUsername] is empty).
     */
    fun isNumericPlaceholder(candidate: String?, rawNumberOrUsername: String = ""): Boolean {
        val trimmed = candidate.orEmpty().trim()
        if (trimmed.isEmpty()) return true
        val candidateNormalized = normalize(trimmed)
        if (candidateNormalized.isEmpty()) return false
        val rawNormalized = normalize(rawNumberOrUsername)
        if (rawNormalized.isNotEmpty()) {
            return candidateNormalized == rawNormalized
        }
        return trimmed.all { it.isDigit() || it in "+-() " }
    }

    // Call on Dispatchers.IO. This function owns and closes its own cursor.
    fun read(context: Context): Set<String> {
        val numbers = mutableSetOf<String>()
        forEachPhoneRow(context, includeDisplayName = false) { number, _, _ ->
            numbers.add(number)
        }
        forEachSimAdnRow(context) { number, _ ->
            numbers.add(number)
        }
        return numbers
    }

    /**
     * Reads saved phonebook & SIM names keyed by normalized phone number, preferring SIM contact
     * names first and meaningful text names over blank or numeric placeholders.
     * Call on Dispatchers.IO.
     */
    fun readSavedNames(context: Context): Map<String, String> {
        val entries = linkedMapOf<String, SavedNameEntry>()
        forEachSimAdnRow(context) { number, displayName ->
            mergeSavedNameCandidate(entries[number], number, displayName, isSim = true)?.let {
                entries[number] = it
            }
        }
        forEachPhoneRow(context, includeDisplayName = true) { number, displayName, isSim ->
            mergeSavedNameCandidate(entries[number], number, displayName, isSim = isSim)?.let {
                entries[number] = it
            }
        }
        val result = linkedMapOf<String, String>()
        for ((number, entry) in entries) {
            result[number] = entry.name
        }
        return result
    }

    internal fun mergeSavedNameCandidate(
        existing: SavedNameEntry?,
        number: String,
        displayName: String,
        isSim: Boolean
    ): SavedNameEntry? {
        val cleanName = displayName.trim()
        val newIsMeaningful = !isNumericPlaceholder(cleanName, number)
        if (existing == null) {
            return SavedNameEntry(cleanName, isSim)
        }
        val existingIsMeaningful = !isNumericPlaceholder(existing.name, number)
        val shouldReplace = when {
            isSim && newIsMeaningful && (!existing.isSim || !existingIsMeaningful) -> true
            existing.isSim && existingIsMeaningful && !isSim -> false
            existing.name.isBlank() && cleanName.isNotEmpty() -> true
            !existingIsMeaningful && newIsMeaningful -> true
            else -> false
        }
        return if (shouldReplace) SavedNameEntry(cleanName, isSim) else null
    }

    internal fun isSimAccountType(accountType: String?): Boolean {
        val type = accountType.orEmpty().lowercase()
        if (type.isEmpty()) return false
        return type.contains("sim") ||
            type.contains("icc") ||
            type.contains("adn") ||
            type.contains("usim") ||
            type.contains("uim")
    }

    private inline fun forEachPhoneRow(
        context: Context,
        includeDisplayName: Boolean,
        onRow: (normalizedNumber: String, displayName: String, isSim: Boolean) -> Unit
    ) {
        if (!hasPermission(context)) {
            throw SecurityException("Contacts permission required")
        }

        val projection = if (includeDisplayName) {
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.ACCOUNT_TYPE
            )
        } else {
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        }
        val sortOrder = if (includeDisplayName) {
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        } else {
            null
        }

        val cursor = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )
        } catch (_: Exception) {
            val fallbackProjection = if (includeDisplayName) {
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
                )
            } else {
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            }
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                fallbackProjection,
                null,
                null,
                sortOrder
            )
        } ?: error("Unable to read phone contacts")

        cursor.use {
            val numberCol = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameCol = if (includeDisplayName) {
                it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            } else {
                -1
            }
            val accountTypeCol = if (includeDisplayName) {
                it.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            } else {
                -1
            }
            while (it.moveToNext()) {
                val number = normalize(it.getString(numberCol).orEmpty())
                if (number.isEmpty()) continue
                val displayName = if (nameCol >= 0) it.getString(nameCol).orEmpty().trim() else ""
                val isSim = accountTypeCol >= 0 && isSimAccountType(it.getString(accountTypeCol))
                onRow(number, displayName, isSim)
            }
        }
    }

    private inline fun forEachSimAdnRow(
        context: Context,
        onRow: (normalizedNumber: String, displayName: String) -> Unit
    ) {
        if (!hasPermission(context)) return

        val uris = linkedSetOf(Uri.parse("content://icc/adn"))
        runCatching {
            val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

            @Suppress("MissingPermission")
            val activeSubs = subMgr?.activeSubscriptionInfoList.orEmpty()
            for (info in activeSubs) {
                uris.add(Uri.parse("content://icc/adn/subId/${info.subscriptionId}"))
            }
        }

        for (uri in uris) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = listOf("name", "display_name", "DISPLAY_NAME")
                        .firstNotNullOfOrNull { col -> cursor.getColumnIndex(col).takeIf { it >= 0 } }
                        ?: -1
                    val numberIdx = listOf("number", "NUMBER", "phone_number")
                        .firstNotNullOfOrNull { col -> cursor.getColumnIndex(col).takeIf { it >= 0 } }
                        ?: -1
                    if (numberIdx < 0) return@use
                    while (cursor.moveToNext()) {
                        val number = normalize(cursor.getString(numberIdx).orEmpty())
                        if (number.isEmpty()) continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                        onRow(number, name)
                    }
                }
            }
        }
    }
}
