package com.example.seamlesstravelapp

import android.content.Intent
import android.nfc.NfcAdapter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle as AndroidBundle
import androidx.fragment.app.Fragment
import com.example.seamlesstravelapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)

        // ✅ USE VIEW BINDING
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            // START WITH HOME FRAGMENT
            replaceFragment(HomeFragment())
        }
    }

    // ==========================================
    // Navigation Methods
    // ==========================================

    fun navigateToHomeFragment() {
        replaceFragment(HomeFragment())
    }

    fun navigateToPassportFragment() {
        replaceFragment(PassportFragment())
    }

    fun navigateToSelfieFragment() {
        replaceFragment(SelfieFragment())
    }
    fun navigateToScanIdFragment() {
        replaceFragment(ScanIdFragment())
    }
    fun navigateToBoardingPassFragment() {
        replaceFragment(BoardingPassFragment())
    }

    fun navigateToWalletFragment() {
        replaceFragment(WalletFragment())
    }

    fun navigateToConfirmationFragment(scanType: String) {
        val fragment = ConfirmationFragment.newInstance(scanType)
        replaceFragment(fragment)
    }

    fun restartProcess() {
        // Go back to home
        replaceFragment(HomeFragment())
    }

    // ==========================================
    // Fragment Management
    // ==========================================

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // ==========================================
    // NFC Intent Handling
    // ==========================================

    // ✅ FIX 1: Remove nullable (Intent?) - use Intent
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // No need to check if intent is null - it's guaranteed non-null
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.get(0)
        if (currentFragment is PassportFragment) {
            currentFragment.handleNfcIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        // If an NFC tag was scanned while the app was in the background
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            onNewIntent(intent)
        }
    }

    // ==========================================
    // Back Button Handling
    // ==========================================

    // ✅ FIX 2: Add @Suppress annotation and call super.onBackPressed()
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 1) {
            supportFragmentManager.popBackStack()
        } else {
            // Call super to handle default back behavior
            super.onBackPressed()
        }
    }
}