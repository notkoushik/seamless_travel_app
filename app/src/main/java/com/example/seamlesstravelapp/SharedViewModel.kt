package com.example.seamlesstravelapp

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class SharedViewModel : ViewModel() {
    val passportData = MutableLiveData<PassportData>()
    val idPhotoBitmap = MutableLiveData<Bitmap>()
    val selfieBitmap = MutableLiveData<Bitmap>()
    val selfieTaken = MutableLiveData<Boolean>(false)
    val boardingPassData = MutableLiveData<BoardingPassData>()
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
