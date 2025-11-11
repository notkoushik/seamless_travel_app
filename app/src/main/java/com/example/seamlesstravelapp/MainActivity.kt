package com.example.seamlesstravelapp

import android.content.Intent
import android.nfc.NfcAdapter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle as AndroidBundle
import androidx.fragment.app.Fragment
import com.example.seamlesstravelapp.databinding.ActivityMainBinding
import androidx.activity.viewModels
import androidx.fragment.app.FragmentManager

// --- Import the new confirmation fragment ---
import com.example.seamlesstravelapp.AadhaarConfirmationFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sharedViewModel: SharedViewModel by viewModels()

    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }

    // --- NAVIGATION FUNCTIONS ---

    fun navigateToHomeFragment() = replaceFragment(HomeFragment())
    fun navigateToPassportFragment() = replaceFragment(PassportFragment())
    fun navigateToSelfieFragment() = replaceFragment(SelfieFragment())
    fun navigateToAadhaarFragment() = replaceFragment(AadhaarFragment())
    fun navigateToScanIdFragment() = replaceFragment(ScanIdFragment())
    fun navigateToBoardingPassFragment() = replaceFragment(BoardingPassFragment())
    fun navigateToWalletFragment() = replaceFragment(WalletFragment())

    fun navigateToConfirmationFragment(scanType: String) =
        replaceFragment(ConfirmationFragment.newInstance(scanType))

    // --- THIS IS THE NEW FUNCTION FOR YOUR FLOW ---
    fun navigateToAadhaarConfirmationFragment() = replaceFragment(AadhaarConfirmationFragment())

    fun restartProcess() {
        sharedViewModel.clearData()
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, HomeFragment())
            .commit()
    }

    private fun replaceFragment(fragment: Fragment) {
        val fragmentName = fragment.javaClass.name
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(fragmentName)
            .commit()
    }

    // --- NFC & BACK PRESS HANDLING ---

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is PassportFragment) {
            currentFragment.handleNfcIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            onNewIntent(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}