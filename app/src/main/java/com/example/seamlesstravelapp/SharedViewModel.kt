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

    // --- Data holder for Aadhaar flow ---
    val aadhaarData = MutableLiveData<AadhaarData?>()

    fun clearData() {
        passportData.value = null
        idPhotoBitmap.value = null
        selfieBitmap.value = null
        selfieTaken.value = false
        boardingPassData.value = null
        // --- Clear Aadhaar data on restart ---
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

// --- Data class to hold the parsed XML data from the QR code ---
data class AadhaarData(
    val uid: String,
    val name: String,
    val gender: String,
    val dob: String,
    val photo: Bitmap?
)