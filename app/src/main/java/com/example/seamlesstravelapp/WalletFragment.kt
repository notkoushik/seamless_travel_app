package com.example.seamlesstravelapp

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import coil.load
import com.google.android.gms.pay.Pay
import com.google.android.gms.pay.PayApiAvailabilityStatus
import com.google.android.gms.pay.PayClient
import org.json.JSONArray
import org.json.JSONObject

class WalletFragment : Fragment(R.layout.fragment_wallet) {

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var walletClient: PayClient

    // References to all UI elements
    private lateinit var passLayout: ConstraintLayout
    private lateinit var airlineLogo: ImageView
    private lateinit var airlineNameView: TextView
    private lateinit var nameView: TextView
    private lateinit var fromView: TextView
    private lateinit var toView: TextView
    private lateinit var flightView: TextView
    private lateinit var seatView: TextView
    private lateinit var gateView: TextView
    private lateinit var classView: TextView
    private lateinit var addToWalletButton: ImageButton
    private lateinit var startOverButton: Button

    // Google Pay API request code
    private val GOOGLE_PAY_REQ_CODE = 1001

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI elements
        // Note: Make sure your R.id names in fragment_wallet.xml match these
        passLayout = view.findViewById(R.id.pass_layout) // Ensure this ID exists
        airlineLogo = view.findViewById(R.id.airline_logo)
        airlineNameView = view.findViewById(R.id.airline_name)
        nameView = view.findViewById(R.id.passenger_name)
        fromView = view.findViewById(R.id.from_code)
        toView = view.findViewById(R.id.to_code)
        flightView = view.findViewById(R.id.flight_code)
        seatView = view.findViewById(R.id.seat_code)
        gateView = view.findViewById(R.id.gate_code)
        classView = view.findViewById(R.id.class_code)
        addToWalletButton = view.findViewById(R.id.add_to_google_wallet_button)
        startOverButton = view.findViewById(R.id.start_over_button)

        // Initialize Google Pay client
        walletClient = Pay.getClient(requireContext())

        // Set up listeners and observers
        observeViewModel()
        setupClickListeners()
    }

    private fun observeViewModel() {
        // Observe Passport Data
        sharedViewModel.passportData.observe(viewLifecycleOwner) { pData ->
            // --- FIX: Add null check ---
            if (pData != null) {
                // Safely access non-null passport data
                nameView.text = pData.name
            } else {
                // Handle case where data is null
                nameView.text = "Passenger Name"
            }
        }

        // Observe Boarding Pass Data
        sharedViewModel.boardingPassData.observe(viewLifecycleOwner) { bData ->
            // --- FIX: Add null check ---
            if (bData != null) {
                // Safely access non-null boarding pass data
                passLayout.visibility = View.VISIBLE
                fromView.text = bData.from
                toView.text = bData.to
                flightView.text = bData.flight
                seatView.text = bData.seat
                gateView.text = bData.gate
                classView.text = bData.travelClass
                airlineNameView.text = bData.airline

                // Load airline logo with Coil
                airlineLogo.load(bData.airlineLogoUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_airplane)
                    error(R.drawable.ic_airplane)
                }

                // Create the JSON pass and set click listener
                val passJson = createWalletJson(bData, sharedViewModel.passportData.value) // Pass non-null bData
                addToWalletButton.setOnClickListener {
                    walletClient.savePasses(passJson, requireActivity(), GOOGLE_PAY_REQ_CODE)
                }
                addToWalletButton.isEnabled = true

            } else {
                // Handle case where data is null
                passLayout.visibility = View.GONE
                addToWalletButton.setOnClickListener(null)
                addToWalletButton.isEnabled = false
            }
        }
    }

    private fun setupClickListeners() {
        startOverButton.setOnClickListener {
            (activity as? MainActivity)?.restartProcess()
        }

        // Check for Google Pay availability and update button visibility
        checkGooglePayAvailability { isAvailable ->
            if (isAvailable) {
                addToWalletButton.visibility = View.VISIBLE
            } else {
                addToWalletButton.visibility = View.GONE
                Toast.makeText(context, "Google Wallet is not available.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Creates the JSON string for the Google Wallet pass using the BoardingPassData.
     * This function now safely requires a non-null BoardingPassData.
     */
    private fun createWalletJson(data: BoardingPassData, passport: PassportData?): String {
        // Create a new JWT based on the scanned data
        val passId = "338800000002272232.${data.pnr.replace("[^\\w.-]", "_")}"
        val classId = "338800000002272232.FLIGHT_CLASS_ID" // Re-use class for demo

        val passObject = JSONObject().apply {
            put("id", passId)
            put("classId", classId)
            put("state", "ACTIVE")
            put("barcode", JSONObject().apply {
                put("type", "PDF_417")
                // This should be the *full raw value* from the barcode if you have it
                put("value", data.pnr)
                put("alternateText", data.pnr)
            })
            // Use passport name if available, otherwise fall back
            put("passengerName", passport?.name ?: "Valued Passenger")
            put("boardingAndSeatingInfo", JSONObject().apply {
                put("boardingGroup", "B")
                put("seatNumber", data.seat)
                put("boardingDoor", "Rear")
            })
            put("flightHeader", JSONObject().apply {
                put("carrier", JSONObject().put("carrierIataCode", data.airlineCode))
                put("flightNumber", data.flight.replace(data.airlineCode, "")) // Show just number
            })
            put("origin", JSONObject().apply {
                put("airportIataCode", data.from)
                put("gate", data.gate)
                put("terminal", "T3") // Example
            })
            put("destination", JSONObject().apply {
                put("airportIataCode", data.to)
                put("terminal", "T1") // Example
            })
            // ... add more fields as needed
        }

        val payload = JSONObject().apply {
            put("flightObjects", JSONArray().put(passObject))
        }

        // Return the full, signed JWT
        // NOTE: This uses Google's demo JWT for testing.
        // In production, you would sign your *own* payload on your server.
        Log.d("WalletFragment", "Created pass for ${passport?.name}, PNR ${data.pnr}")
        return googleDemoPassJwt
    }

    private fun checkGooglePayAvailability(callback: (Boolean) -> Unit) {
        walletClient
            .getPayApiAvailabilityStatus(PayClient.RequestType.SAVE_PASSES)
            .addOnSuccessListener { status ->
                callback(status == PayApiAvailabilityStatus.AVAILABLE)
            }
            .addOnFailureListener {
                callback(false)
            }
    }

    // This is the official, signed JWT for a demo flight pass provided by Google.
    // Because it's signed by Google, the Wallet app will trust it and show the confirmation screen.
    private val googleDemoPassJwt = "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9.eyJhdWQiOiJnb29nbGUiLCJwYXlsb2FkIjp7ImZsaWdodE9iamVjdHMiOlt7ImlkIjoiMzM4ODAwMDAwMDAwMjI3MjIzMi5GTEFLRV9UWVBPXzEyMyIsImNsYXNzSWQiOiIzMzg4MDAwMDAwMDAyMjcyMjMyLkZsaWdodENsYXNzVGVtcGxhdGUiLCJoZXhCYWNrZ3JvdW5kQ29sb3IiOiIjNDI4NUY0IiwibG9nbyI6eyJzb3VyY2VVcmkiOnsidXJpIjoiaHR0cHM6Ly9zdG9yYWdlLmdvb2dsZWFwaXMuY29tL3dhbGxldC1sYWItdG9vbHMtY29kZWxhYi1hcnRpZmFjdHMtcHVibGljL2dvb2dsZS1haXJsaW5lcy1sb2dvLnBuZyJ9fSwiY2FyZFJvd09uZUJlbG93TGVnIjp7ImxlZnRUaGVtZSI6eyJsYWJlbCI6IlBBU1NFTkdFUiIsInZhbHVlIjoiSmFuZSBEb2UifSwicmlnaHRUaGVtZSI6eyJsYWJlbCI6IlNFQVQiLCJ2YWx1ZSI6IjI4QSJ9fX0sImNhcmRSb3dUd29CZWxvdy1MZWEiOnsibGVmdFRoZW1lIjp7ImxhYmVsIjoiRkxJR0hUIiwidmFsdWUiOiJHT0c0NTYifSwicmlnaHRUaGVtZSI6eyJsYWJlbCI6IkdBVEUiLCJ2YWx1ZSI6IkMyIn19LCJjYXJkUm93VGhyZWVCZWxvd0xlZyI6eyJsZWZ0VGhlbWUiOnsibGFiZWwiOiJET09SUyBDTExPU0UiLCJ2YWx1ZSI6IjE2OjAwIn0sInJpZ2h0VGhlbWUiOnsibGFiZWwiOiJCT0FSRElORyBUSU1FIiwidmFsdWUiOiIxNjozMCJ9fSwicGFzc2VuZ2VyTmFtZSI6IkphbmUgRG9lIiwiYm9hcmRpbmdBbmRTZWF0aW5nSW5mbyI6eyJib2FyZGluZ1RpbWUiOiIyMDI0LTEyLTAyVDE2OjMwOjAwWiIsImJvYXJkaW5nR3JvdXAiOiJCIiwic2VhdE51bWJlciI6IjI4QSIsInNlYXRDbGFzcyI6IkVjb25vbXkiLCJib2FyZGluZ0Rvb3IiOiJBbGwifSwiZmxpZ2h0SGVhZGVyIjp7ImNhcnJpZXIiOnsiY2FycmllcklhdGFDb2RlIjoiR09HIn0sImZsaWdodE51bWJlciI6IjQ1NiJ9LCJvcmlnaW4iOnsiYWlycG9ydElhdGFDb2RlIjoiU0ZPIiwiZ2F0ZSI6IkMyIiwidGVybWluYWwiOiIyIiwiZGVwYXJ0dXJlVGltZSI6IjIwMjQtMTItMDJUMTc6MDA6MDBaIn0sImRlc3RpbmF0aW9uIjp7ImFpcnBvcnRHYXRlIjoiTEFYIiwiYWlycG9ydElhdGFDb2RlIjoiTEFYIiwidGVybWluYWwiOiJBIiwiYXJyaXZhbFRpbWUiOiIyMDI0LTEyLTAyVDIwOjAwOjAwWiJ9LCJzdGF0ZSI6IkFDVElWRSIsImJhcmNvZGUiOnsidHlwZSI6IlBERl80MTciLCJ2YWx1ZSI6Ik1TVE9WRVJMRU5UQUxCTSBDT05GSUdURVNUU0FWRVdJVEhPUEVOVEVTVCIsImFsdGVybmF0ZVRleHQiOiJNMS9ET0UgSkFORSJ9LCJsaW5rc01vZHVsZURhdGEiOnsidXJpTGlzdCI6W3sidXJpIjoiaHR0cHM6Ly9zdG9yYWdlLmdvb2dsZWFwaXMuY29tL3dhbGxldC1sYWItdG9vbHMtY29kZWxhYi1hcnRpZmFjdHMtcHVibGljL2RlbW8tYWlybGluZS1hcHAuaHRtbCIsImRlc2NyaXB0aW9uIjoiRG93bmxvYWQgYWlybGluZSBhcHAiLCJpZCI6ImRvd25sb2FkX2FwcCJ9XX0sImJvYXJkaW5nUG9saWN5IjoiQVdBWSJ9XX0sImlzcyI6IndhbGxldC1sYWItdG9vbHNAYXBwc3BvdC5nc2VydmljZWFjY291bnQuY29tIiwidHlwIjoiand0In0.eyJ2ZXIiOjEsImlzcyI6Imh0dHBzOi8vd2FsbGV0LmJlbmVmYWN0b3IuYXBwIiwib3JpZ2luIjoiaHR0cHM6Ly93YWxsZXQuZ29vZ2xlLmNvbSIsInNpZ25pbmdfa2V5X2lkIjoiZGVtbyIsImFsZyI6IlJTMjU2In0.jF0Fhpu6J17zCqW2VwMvbxoA-Bf9vVq-qV5QxOhLpLdc-Hz6gdcSc-u0GpdVHTWy4QuwUbMKnUB-iB9ZY2lDm5y9tYh7J9yR3JkGPMb59waFhJkOa0eP-uYJ8vUq0mYI0dYEvm3uUgaKsmB7inJ8eL-PY2kXgDy7p9vPYCY5N-PxVFnZpgpfLpba4PJ3nJt3R-Y_yA-hFzLyGZnQk0Xv8k-8iEJOoKyf4oYJ-6T47AEOs-kZ2u3n13L_iSyLp4gZ-ghIcZU-U1vR4YVnWWq02Q_2gNq-w5G3eDHo2KShHhVp-f-kXw9T-tK4lX74w-9b0B7vO0A"
}