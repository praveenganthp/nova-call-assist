package com.nova.callassist.calling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.nova.callassist.contacts.PhoneNumber

/**
 * Handles safe phone call initiation via Android intents.
 * Defaults to ACTION_DIAL to prioritize user consent and safety.
 */
class CallManager(private val context: Context) {

    sealed class CallResult {
        object Success : CallResult()
        object PermissionDenied : CallResult()
        object NoDialerAvailable : CallResult()
        data class Error(val message: String) : CallResult()
    }

    /**
     * Checks if direct calling permission (CALL_PHONE) is currently granted.
     */
    fun hasDirectCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Initiates a call to the specified phone number.
     *
     * @param phoneNumber The target phone number
     * @param isDirectCallingEnabled If true and CALL_PHONE is granted, places direct call via ACTION_CALL;
     *                               otherwise opens phone dialer via ACTION_DIAL.
     */
    fun placeCall(phoneNumber: PhoneNumber, isDirectCallingEnabled: Boolean): CallResult {
        val dialable = phoneNumber.dialableNumber
        if (dialable.isBlank()) {
            return CallResult.Error("Invalid phone number format")
        }

        val uri = Uri.parse("tel:$dialable")

        val intent = if (isDirectCallingEnabled) {
            if (!hasDirectCallPermission()) {
                return CallResult.PermissionDenied
            }
            Intent(Intent.ACTION_CALL, uri)
        } else {
            Intent(Intent.ACTION_DIAL, uri)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        // Check if there is a handler for this intent
        val packageManager = context.packageManager
        val resolveInfo = intent.resolveActivity(packageManager)
        if (resolveInfo == null) {
            // Some newer Android versions with package visibility may return null for resolveActivity
            // even if a dialer exists. Let's attempt start with a try/catch.
            return try {
                context.startActivity(intent)
                CallResult.Success
            } catch (e: SecurityException) {
                CallResult.PermissionDenied
            } catch (e: Exception) {
                CallResult.NoDialerAvailable
            }
        }

        return try {
            context.startActivity(intent)
            CallResult.Success
        } catch (e: SecurityException) {
            CallResult.PermissionDenied
        } catch (e: Exception) {
            CallResult.Error(e.localizedMessage ?: "Failed to initiate call")
        }
    }
}
