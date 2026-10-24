package com.nova.callassist.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accesses local Android Contacts Provider.
 * Contacts are queried on-demand and kept in memory only; never persisted to
 * third-party databases and never sent across the network.
 */
class ContactsRepository(private val context: Context) {

    private var cachedContacts: List<ContactModel>? = null

    /**
     * Checks if the app currently has permission to read contacts.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Loads contacts from the device Contacts Provider on an IO dispatcher.
     */
    suspend fun loadContacts(forceRefresh: Boolean = false): List<ContactModel> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedContacts != null) {
            return@withContext cachedContacts!!
        }

        if (!hasPermission()) {
            return@withContext emptyList()
        }

        val contactsMap = mutableMapOf<String, MutableContact>()

        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.LOOKUP_KEY,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_PRIMARY,
            Phone.PHOTO_URI
        )

        val sortOrder = "${Phone.DISPLAY_NAME_PRIMARY} ASC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                Phone.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(Phone.CONTACT_ID)
                val lookupIdx = c.getColumnIndex(Phone.LOOKUP_KEY)
                val nameIdx = c.getColumnIndex(Phone.DISPLAY_NAME_PRIMARY)
                val numberIdx = c.getColumnIndex(Phone.NUMBER)
                val typeIdx = c.getColumnIndex(Phone.TYPE)
                val labelIdx = c.getColumnIndex(Phone.LABEL)
                val isPrimaryIdx = c.getColumnIndex(Phone.IS_PRIMARY)
                val photoIdx = c.getColumnIndex(Phone.PHOTO_URI)

                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val lookupKey = if (lookupIdx >= 0) c.getString(lookupIdx) ?: "" else ""
                    val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "Unknown" else "Unknown"
                    val number = if (numberIdx >= 0) c.getString(numberIdx) ?: "" else ""
                    val typeInt = if (typeIdx >= 0) c.getInt(typeIdx) else Phone.TYPE_OTHER
                    val label = if (labelIdx >= 0) c.getString(labelIdx) else null
                    val isPrimary = if (isPrimaryIdx >= 0) c.getInt(isPrimaryIdx) > 0 else false
                    val photoUri = if (photoIdx >= 0) c.getString(photoIdx) else null

                    if (number.isNotBlank()) {
                        val typeStr = Phone.getTypeLabel(context.resources, typeInt, label).toString()
                        val phone = PhoneNumber(
                            number = number.trim(),
                            type = typeStr,
                            isPrimary = isPrimary
                        )

                        val entry = contactsMap.getOrPut(id) {
                            MutableContact(
                                id = id,
                                lookupKey = lookupKey,
                                displayName = displayName.trim(),
                                photoUri = photoUri
                            )
                        }
                        // Avoid duplicate phone entries
                        if (entry.phoneNumbers.none { it.number == phone.number }) {
                            entry.phoneNumbers.add(phone)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }

        val result = contactsMap.values.map { it.toContactModel() }
        cachedContacts = result
        return@withContext result
    }

    /**
     * Clears cached contact list to force fresh reload on next query.
     */
    fun invalidateCache() {
        cachedContacts = null
    }

    private data class MutableContact(
        val id: String,
        val lookupKey: String,
        val displayName: String,
        val photoUri: String?,
        val phoneNumbers: MutableList<PhoneNumber> = mutableListOf()
    ) {
        fun toContactModel() = ContactModel(
            id = id,
            lookupKey = lookupKey,
            displayName = displayName,
            phoneNumbers = phoneNumbers.toList(),
            photoUri = photoUri
        )
    }
}
