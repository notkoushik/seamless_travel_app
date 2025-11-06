package com.example.seamlesstravelapp

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

// ✅ BEST PRACTICE: Single file for all related data classes and ViewModel


class SharedViewModel : ViewModel() {
    val passportData = MutableLiveData<PassportData>()

    // --- NEW: Add MutableLiveData for our two new photos ---
    val idPhotoBitmap = MutableLiveData<Bitmap>()
    val selfieBitmap = MutableLiveData<Bitmap>()
    // --- END NEW ---

    val selfieTaken = MutableLiveData<Boolean>(false)
    val boardingPassData = MutableLiveData<BoardingPassData>()
}

// ✅ PassportData - Updated to hold detailed passport info, including photo from NFC chip
data class PassportData(
    val name: String,
    val passportNumber: String,
    val dateOfBirth: String,     // Format: YYMMDD
    val expiryDate: String,      // Format: YYMMDD
    val photo: Bitmap?
)

// ✅ BoardingPassData - Comprehensive boarding pass information
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
