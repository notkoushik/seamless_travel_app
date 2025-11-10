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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            replaceFragment(HomeFragment())
        }
    }

    fun navigateToHomeFragment() = replaceFragment(HomeFragment())
    fun navigateToPassportFragment() = replaceFragment(PassportFragment())
    fun navigateToSelfieFragment() = replaceFragment(SelfieFragment())
    fun navigateToScanIdFragment() = replaceFragment(ScanIdFragment())
    fun navigateToBoardingPassFragment() = replaceFragment(BoardingPassFragment())
    fun navigateToWalletFragment() = replaceFragment(WalletFragment())
    fun navigateToConfirmationFragment(scanType: String) =
        replaceFragment(ConfirmationFragment.newInstance(scanType))

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    // NFC intent routing → fix: get current fragment directly from the container
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 1) {
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
    }
}
