package com.example.seamlesstravelapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class AadhaarConfirmationFragment : Fragment(R.layout.fragment_aadhaar_confirmation) {

    private val sharedViewModel: SharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val photoView: ImageView = view.findViewById(R.id.iv_aadhaar_photo)
        val nameView: TextView = view.findViewById(R.id.tv_aadhaar_name)
        val uidView: TextView = view.findViewById(R.id.tv_aadhaar_uid)
        val dobView: TextView = view.findViewById(R.id.tv_aadhaar_dob)
        val genderView: TextView = view.findViewById(R.id.tv_aadhaar_gender)
        val confirmButton: Button = view.findViewById(R.id.btn_confirm_aadhaar)
        val rescanButton: Button = view.findViewById(R.id.btn_rescan_aadhaar)

        // Observe the Aadhaar data from the ViewModel
        sharedViewModel.aadhaarData.observe(viewLifecycleOwner) { data ->
            if (data != null) {
                nameView.text = data.name
                // Format UID with spaces, e.g., "1234 5678 9012"
                uidView.text = data.uid.replace("(\\d{4})".toRegex(), "$1 ").trim()
                dobView.text = data.dob
                genderView.text = data.gender
                data.photo?.let {
                    photoView.setImageBitmap(it)
                }
            } else {
                // If data is null, go back to scanner
                (activity as? MainActivity)?.navigateToAadhaarFragment()
            }
        }

        confirmButton.setOnClickListener {
            // Data is confirmed, proceed to selfie verification
            (activity as? MainActivity)?.navigateToSelfieFragment()
        }

        rescanButton.setOnClickListener {
            // Go back to Aadhaar scanner
            (activity as? MainActivity)?.navigateToAadhaarFragment()
        }
    }
}