package com.example.seamlesstravelapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class ConfirmationFragment : Fragment(R.layout.fragment_confirmation) {

    private val sharedViewModel: SharedViewModel by activityViewModels()

    companion object {
        private const val ARG_SCAN_TYPE = "scan_type"
        const val TYPE_PASSPORT = "passport"
        const val TYPE_BOARDING_PASS = "boarding_pass"

        fun newInstance(scanType: String): ConfirmationFragment {
            val fragment = ConfirmationFragment()
            val args = Bundle()
            args.putString(ARG_SCAN_TYPE, scanType)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val scanType = arguments?.getString(ARG_SCAN_TYPE) ?: return

        // --- Common Views ---
        val title: TextView = view.findViewById(R.id.confirmation_title)
        val passportGroup: LinearLayout = view.findViewById(R.id.passport_fields)
        val boardingPassGroup: LinearLayout = view.findViewById(R.id.boarding_pass_fields)
        val confirmButton: Button = view.findViewById(R.id.confirm_button)
        val rescanButton: Button = view.findViewById(R.id.rescan_button)

        if (scanType == TYPE_PASSPORT) {
            // --- Passport Views ---
            val nameEditText: EditText = view.findViewById(R.id.edit_passport_name)
            val passportNumEditText: EditText = view.findViewById(R.id.edit_passport_number)
            val passportPhoto: ImageView = view.findViewById(R.id.passport_photo)

            title.text = "Confirm Passport Details"
            passportGroup.visibility = View.VISIBLE
            boardingPassGroup.visibility = View.GONE

            val data = sharedViewModel.passportData.value
            nameEditText.setText(data?.name)
            passportNumEditText.setText(data?.passportNumber)
            data?.photo?.let {
                passportPhoto.setImageBitmap(it)
            }

            confirmButton.setOnClickListener {
                // Save any edits from the EditTexts
                val confirmedData = PassportData(
                    nameEditText.text.toString(),
                    passportNumEditText.text.toString(),
                    data?.dateOfBirth ?: "",
                    data?.expiryDate ?: "",
                    data?.photo
                )
                sharedViewModel.passportData.value = confirmedData
                (activity as? MainActivity)?.navigateToBoardingPassFragment()
            }
            rescanButton.setOnClickListener {
                (activity as? MainActivity)?.navigateToPassportFragment()
            }
        } else {
            // --- Boarding Pass Views (THIS IS THE FIX) ---

            // These IDs now match the XML file I generated
            val flightTextView: TextView = view.findViewById(R.id.tv_bp_flight)
            val seatTextView: TextView = view.findViewById(R.id.tv_bp_seat)
            val gateTextView: TextView = view.findViewById(R.id.tv_bp_gate)
            val fromTextView: TextView = view.findViewById(R.id.tv_bp_from)
            val toTextView: TextView = view.findViewById(R.id.tv_bp_to)
            val pnrTextView: TextView = view.findViewById(R.id.tv_bp_pnr)

            title.text = "Confirm Boarding Pass Details"
            passportGroup.visibility = View.GONE
            boardingPassGroup.visibility = View.VISIBLE

            // --- ADDED: Observe and populate the fields ---
            val data = sharedViewModel.boardingPassData.value
            flightTextView.text = data?.flight
            seatTextView.text = data?.seat
            gateTextView.text = data?.gate
            fromTextView.text = data?.from
            toTextView.text = data?.to
            pnrTextView.text = data?.pnr

            // --- SIMPLIFIED: Save data on confirm ---
            confirmButton.setOnClickListener {
                // The data is already in the view model, just navigate.
                (activity as? MainActivity)?.navigateToWalletFragment()
            }
            rescanButton.setOnClickListener {
                (activity as? MainActivity)?.navigateToBoardingPassFragment()
            }
        }
    }
}

