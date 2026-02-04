package com.example.desviosturnos

import android.content.Context
import android.provider.ContactsContract

object ContactsRepo {

    fun findPhoneByExactName(context: Context, exactName: String): String? {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )

        val selection = "${ContactsContract.Contacts.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(exactName)

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null

            val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
            val phoneProjection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
            val phoneArgs = arrayOf(contactId)

            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                phoneSelection,
                phoneArgs,
                null
            )?.use { phoneCursor ->
                if (!phoneCursor.moveToFirst()) return null
                return phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }

        return null
    }
}
