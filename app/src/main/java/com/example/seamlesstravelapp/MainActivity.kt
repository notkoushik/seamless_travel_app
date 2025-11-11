package com.example.seamlesstravelapp

import android.content.Intent
import android.nfc.NfcAdapter
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle as AndroidBundle
import androidx.fragment.app.Fragment
import com.example.seamlesstravelapp.databinding.ActivityMainBinding
import androidx.activity.viewModels
import androidx.fragment.app.FragmentManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Get the ViewModel
    private val sharedViewModel: SharedViewModel by viewModels()

    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            // Start with HomeFragment, don't add to back stack
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, HomeFragment())
                .commit()
        }
    }

    // --- NAVIGATION FUNCTIONS ---
    // These functions were missing, causing the 'Unresolved reference' errors.

    fun navigateToHomeFragment() = replaceFragment(HomeFragment())
    fun navigateToPassportFragment() = replaceFragment(PassportFragment())
    fun navigateToSelfieFragment() = replaceFragment(SelfieFragment())
    fun navigateToAadhaarFragment() = replaceFragment(AadhaarFragment())
    fun navigateToScanIdFragment() = replaceFragment(ScanIdFragment())
    fun navigateToBoardingPassFragment() = replaceFragment(BoardingPassFragment())
    fun navigateToWalletFragment() = replaceFragment(WalletFragment())
    fun navigateToConfirmationFragment(scanType: String) =
        replaceFragment(ConfirmationFragment.newInstance(scanType))

    /**
     * Clears all data and returns to the Home screen.
     */
    fun restartProcess() {
        // 1. Clear all data from the ViewModel
        sharedViewModel.clearData()

        // 2. Clear the entire fragment back stack
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        // 3. Navigate back to the HomeFragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, HomeFragment())
            .commit()
    }

    private fun replaceFragment(fragment: Fragment) {
        val fragmentName = fragment.javaClass.name
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(fragmentName) // Use class name for the back stack tag
            .commit()
    }

    // --- NFC & BACK PRESS HANDLING ---

    // NFC intent routing
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

    @Deprecated("Deprecated in Java") // Added annotation to fix warning
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Check if there is more than one fragment in the back stack
        if (supportFragmentManager.backStackEntryCount > 0) {
            // If yes, pop the stack (go to the previous fragment)
            supportFragmentManager.popBackStack()
        } else {
            // If no, (only HomeFragment is left), let the system handle it (exit app)
            super.onBackPressed()
        }
    }
}