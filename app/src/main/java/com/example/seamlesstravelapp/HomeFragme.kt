package com.example.seamlesstravelapp

import android.os.Bundle as AndroidBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.seamlesstravelapp.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SharedViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: AndroidBundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: AndroidBundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupClickListeners()
    }

    private fun setupUI() {
        // Set user greeting
        binding.tvGreeting.text = "Welcome, Traveler!"
        binding.tvSubtitle.text = "LAX Terminal 3" // Updated to match UI
    }

    private fun setupClickListeners() {
        val mainActivity = activity as? MainActivity

        // --- FIX: Listeners for the icons ---
        binding.btnScanPassportIcon.setOnClickListener {
            mainActivity?.navigateToPassportFragment()
        }

        binding.btnTakeSelfieIcon.setOnClickListener {
            // Navigate to Selfie fragment or show toast
            mainActivity?.navigateToSelfieFragment()
            // Toast.makeText(context, "Selfie clicked", Toast.LENGTH_SHORT).show()
        }

        binding.btnFastTrackIcon.setOnClickListener {
            // Show toast or navigate
            Toast.makeText(context, "Fast Track clicked", Toast.LENGTH_SHORT).show()
        }

        // --- FIX: Correct ID for "Enroll in ProPass" button ---
        binding.btnStartVerification.setOnClickListener {
            mainActivity?.navigateToScanIdFragment()
        }

        // --- FIX: Correct ID for "Flight Registration" button ---
        binding.btnFlightRegistration.setOnClickListener {
            mainActivity?.navigateToBoardingPassFragment()
        }

        // --- FIX: Listeners for Quick Actions ---
        binding.btnBaggageTracking.setOnClickListener {
            // Navigate to Wallet or a dedicated baggage screen
            mainActivity?.navigateToWalletFragment()
            // Toast.makeText(context, "Baggage Tracking clicked", Toast.LENGTH_SHORT).show()
        }

        binding.btnFindGate.setOnClickListener {
            // Navigate to Map
            Toast.makeText(context, "Find Gate clicked", Toast.LENGTH_SHORT).show()
        }

        binding.btnLiveChat.setOnClickListener {
            // Show toast or open chat
            Toast.makeText(context, "Live Chat clicked", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

