package com.example.seamlesstravelapp


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class BoardingPassFragment : Fragment(R.layout.fragment_boarding_pass) {

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required.", Toast.LENGTH_SHORT).show()
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView = view.findViewById(R.id.camera_preview)
        cameraExecutor = Executors.newSingleThreadExecutor()

        view.findViewById<TextView>(R.id.instruction_text).text = "Position the boarding pass barcode inside the frame"
        view.findViewById<Button>(R.id.scan_button).visibility = View.GONE // Scanning is automatic

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            activityResultLauncher.launch(Manifest.permission.CAMERA)
        }

        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                return@setOnTouchListener true
            }
            return@setOnTouchListener false
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, HybridAnalyzer { barcodes, text ->
                        imageAnalysis?.clearAnalyzer()
                        processHybridScan(barcodes, text)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("BoardingPassFragment", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun processHybridScan(barcodes: List<Barcode>, visionText: Text?) {
        val pdf417Barcode = barcodes.firstOrNull { it.format == Barcode.FORMAT_PDF417 }
        if (pdf417Barcode?.rawValue != null) {
            parseBcbpData(pdf417Barcode.rawValue!!, visionText)
            return
        }

        activity?.runOnUiThread {
            Toast.makeText(context, "Could not find a valid boarding pass barcode. Please try again.", Toast.LENGTH_LONG).show()
            startCamera()
        }
    }

    private fun parseBcbpData(data: String, visionText: Text?) {
        try {
            // Data from Barcode (High Accuracy)
            val pnr = data.substring(23, 30).trim()
            val fromCity = data.substring(30, 33).trim()
            val toCity = data.substring(33, 36).trim()
            val airlineCode = data.substring(36, 39).trim()
            val flightNumberRaw = data.substring(39, 44).trim()
            val flightNumber = airlineCode + flightNumberRaw.padStart(4, '0')
            val travelClass = data.substring(47, 48).trim()
            val seat = data.substring(48, 52).trim()

            // Data from OCR (For info not in barcode)
            val gate = findGateInText(visionText)

            // Derived Data
            val airlineName = getAirlineName(airlineCode)
            val logoUrl = getAirlineLogoUrl(airlineCode)
            val className = when (travelClass) {
                "F" -> "First"; "J", "C" -> "Business"; "Y" -> "Economy"; else -> "Economy"
            }

            val boardingPass = BoardingPassData(pnr, flightNumber, seat, gate, className, airlineName, airlineCode, logoUrl, fromCity, toCity)
            goToConfirmation(boardingPass)

        } catch (e: Exception) {
            Log.e("BoardingPassFragment", "Error parsing BCBP data", e)
            activity?.runOnUiThread {
                Toast.makeText(context, "Could not parse barcode. Try again.", Toast.LENGTH_SHORT).show()
                startCamera()
            }
        }
    }

    private fun findGateInText(visionText: Text?): String {
        if (visionText == null) return "N/A"
        val gateRegex = Regex("\\b([A-Z]?\\d{1,2})\\b")
        val lines = visionText.text.split("\n")

        for ((index, line) in lines.withIndex()) {
            val upperLine = line.uppercase()
            if (upperLine.contains("GATE") || upperLine.contains("GT")) {
                var match = gateRegex.find(upperLine)
                if (match != null) {
                    return match.value
                }
                if (index + 1 < lines.size) {
                    match = gateRegex.find(lines[index + 1])
                    if (match != null) {
                        return match.value
                    }
                }
            }
        }
        return "N/A"
    }

    private fun goToConfirmation(boardingPass: BoardingPassData) {
        sharedViewModel.boardingPassData.postValue(boardingPass)
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), "Boarding Pass Scanned! Please confirm.", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.navigateToConfirmationFragment(ConfirmationFragment.TYPE_BOARDING_PASS)
        }
    }

    private fun getAirlineName(code: String): String {
        return when (code.trim().uppercase()) {
            "AI" -> "Air India"; "6E" -> "IndiGo"; "UK" -> "Vistara"; "SG" -> "SpiceJet";
            "BA" -> "British Airways"; "EK" -> "Emirates"; "QR" -> "Qatar Airways";
            "SQ" -> "Singapore Airlines"; "LH" -> "Lufthansa"; "AF" -> "Air France";
            "DL" -> "Delta Air Lines"; "UA" -> "United Airlines"; "AA" -> "American Airlines";
            else -> "Unknown ($code)"
        }
    }

    private fun getAirlineLogoUrl(code: String): String {
        return when (code.trim().uppercase()) {
            "AI" -> "https://upload.wikimedia.org/wikipedia/en/thumb/5/5b/Air_India_logo.svg/320px-Air_India_logo.svg.png"
            "6E" -> "https://upload.wikimedia.org/wikipedia/en/thumb/f/f7/IndiGo_logo.svg/320px-IndiGo_logo.svg.png"
            "UK" -> "https://upload.wikimedia.org/wikipedia/en/thumb/0/05/Vistara_logo.svg/320px-Vistara_logo.svg.png"
            else -> ""
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    private class HybridAnalyzer(private val listener: (List<Barcode>, Text?) -> Unit) : ImageAnalysis.Analyzer {
        private val barcodeOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_PDF417)
            .build()
        private val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)
        private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val barcodeTask = barcodeScanner.process(image)
                val textTask = textRecognizer.process(image)

                Tasks.whenAllComplete(barcodeTask, textTask)
                    .addOnSuccessListener {
                        val barcodes = barcodeTask.result
                        val visionText = textTask.result
                        if (!barcodes.isNullOrEmpty()) {
                            listener(barcodes, visionText)
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
    }
}