package com.example.seamlesstravelapp

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    val passportData = MutableLiveData<PassportData?>()
    val idPhotoBitmap = MutableLiveData<Bitmap?>()
    val selfieBitmap = MutableLiveData<Bitmap?>()
    val selfieTaken = MutableLiveData<Boolean>(false)
    val boardingPassData = MutableLiveData<BoardingPassData?>()

    // --- ADD THIS LINE ---
    val aadhaarData = MutableLiveData<AadhaarData?>()

    /**
     * Clears all session data from the ViewModel to allow for a restart.
     */
    fun clearData() {
        passportData.value = null
        idPhotoBitmap.value = null
        selfieBitmap.value = null
        selfieTaken.value = false
        boardingPassData.value = null

        // --- ADD THIS LINE ---
        aadhaarData.value = null
    }
}

data class PassportData(
    val name: String,
    val passportNumber: String,
    val dateOfBirth: String,
    val expiryDate: String,
    val photo: Bitmap?
)

data class BoardingPassData(
    val pnr: String,
    val flight: String,
    val seat: String,
    val gate: String,
    val travelClass: String,
    val airline: String,
    val airlineCode: String,
    val airlineLogoUrl: String,
    val from: String,
    val to: String
)

// --- ADD THIS NEW DATA CLASS ---
/**
 * Holds the demographic data extracted from the Aadhaar e-KYC XML.
 * The XML attributes are typically:
 * "name" -> Name
 * "dob"  -> Date of Birth (YYYY-MM-DD)
 * "gender" -> M / F / T
 */
data class AadhaarData(
    val name: String,
    val dob: String,
    val gender: String
)