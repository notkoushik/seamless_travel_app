package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AadhaarFragment : Fragment(R.layout.fragment_aadhaar) {

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var qrScanner: BarcodeScanner? = null

    // --- ADDED: This flag prevents the scanner from firing 1000 times ---
    private val isProcessing = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(
                requireContext(),
                "Camera permission is required",
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView = view.findViewById(R.id.previewView)
        instructionText = view.findViewById(R.id.instruction_text)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Setup QR Code Analyzer
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            qrScanner = BarcodeScanning.getClient(options)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, buildQrAnalyzer())
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("AadhaarFragment", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun buildQrAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            // --- FIX 1: Check if we are already processing a QR code ---
            if (isProcessing.get()) {
                imageProxy.close()
                return@Analyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                qrScanner?.process(image)
                    ?.addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            // --- FIX 2: Set flag to true to stop scanner from firing again ---
                            isProcessing.set(true)
                            stopCamera() // Stop scanning

                            // This is the raw XML text from your test QR code
                            val xmlString = barcodes.first().rawValue

                            if (xmlString != null) {
                                Log.d("AadhaarFragment", "QR Data Found. Parsing...")
                                instructionText.text = "QR Scanned. Processing..."
                                // --- FIX 3: REMOVED password step, go DIRECTLY to parsing ---
                                parseAadhaarXml(xmlString)
                            }
                        }
                    }
                    ?.addOnFailureListener {
                        Log.e("AadhaarFragment", "QR Scan failed", it)
                    }
                    ?.addOnCompleteListener {
                        imageProxy.close() // Must close proxy
                    }
            }
        }
    }

    // --- FIX 4: REMOVED the askForShareCode(...) function ---
    // --- FIX 5: REMOVED the processAadhaarZip(...) function ---

    /**
     * This function parses the XML string from the QR code.
     * It looks for two things:
     * 1. The <Poi> tag to get the attributes (name, dob, gender)
     * 2. The <Photo> or <Pht> tag to get the Base64 text
     */
    private fun parseAadhaarXml(xmlString: String) {
        // Run this on a background thread
        lifecycleScope.launch(Dispatchers.IO) {
            var name: String? = null
            var dob: String? = null
            var gender: String? = null
            var base64Photo: String? = null

            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                // Use StringReader to read the XML string
                parser.setInput(StringReader(xmlString))

                var eventType = parser.eventType

                // Loop through the XML tags
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    val tagName = parser.name

                    if (eventType == XmlPullParser.START_TAG) {
                        // The demographic data is in the *attributes* of the "Poi" tag
                        if (tagName.equals("Poi", ignoreCase = true)) {
                            name = parser.getAttributeValue(null, "name")
                            dob = parser.getAttributeValue(null, "dob")
                            gender = parser.getAttributeValue(null, "gender")
                        }
                        // The photo is in the *text* of the "Photo" tag
                        else if (tagName.equals("Photo", ignoreCase = true)) {
                            base64Photo = parser.nextText()
                        }
                        // Also check for <Pht> tag (used in official XML)
                        else if (tagName.equals("Pht", ignoreCase = true)) {
                            base64Photo = parser.nextText()
                        }
                    }
                    eventType = parser.next()
                }

                // --- ALL PARSING IS DONE, NOW CHECK THE RESULTS ---

                if (name != null && dob != null && gender != null && base64Photo != null) {
                    // We found everything!

                    // 1. Create the AadhaarData object
                    val aadhaarInfo = AadhaarData(name = name, dob = dob, gender = gender)

                    // 2. Decode the Base64 photo
                    val imageBytes = Base64.decode(base64Photo, Base64.DEFAULT)
                    val photoBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    // 3. Switch back to the Main thread to update ViewModel and navigate
                    withContext(Dispatchers.Main) {
                        Log.d("AadhaarFragment", "Aadhaar Info Extracted: $name")

                        // --- SAVE DATA TO VIEWMODEL ---
                        sharedViewModel.aadhaarData.value = aadhaarInfo
                        sharedViewModel.idPhotoBitmap.value = photoBitmap

                        // --- NAVIGATE TO SELFIE FRAGMENT ---
                        (activity as? MainActivity)?.navigateToSelfieFragment()
                    }
                } else {
                    // Failed to find one of the required tags
                    withContext(Dispatchers.Main) {
                        Log.e(
                            "AadhaarFragment",
                            "Parse Error: Missing tags. Name: $name, DOB: $dob, Photo: ${base64Photo != null}"
                        )
                        Toast.makeText(
                            context,
                            "Could not find all required data in QR. Try again.",
                            Toast.LENGTH_LONG
                        ).show()

                        // --- FIX 6: Reset the flag on failure ---
                        isProcessing.set(false)
                        restartCamera() // Try again
                    }
                }
            } catch (e: Exception) {
                Log.e("AadhaarFragment", "Error processing XML", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: Invalid XML. Try again.", Toast.LENGTH_LONG)
                        .show()

                    // --- FIX 7: Reset the flag on failure ---
                    isProcessing.set(false)
                    restartCamera()
                }
            }
        }
    }


    private fun stopCamera() {
        activity?.runOnUiThread {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        }
    }

    private fun restartCamera() {
        activity?.runOnUiThread {
            // --- FIX 8: Reset the flag when restarting ---
            isProcessing.set(false)
            instructionText.text = "Scan your Aadhaar QR Code"
            startCamera()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        qrScanner?.close()
        cameraProvider?.unbindAll()
    }
}