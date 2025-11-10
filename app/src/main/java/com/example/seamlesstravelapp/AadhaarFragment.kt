package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.*
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
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class AadhaarFragment : Fragment(R.layout.fragment_aadhaar) {

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var qrScanner: BarcodeScanner? = null

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

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun buildQrAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image =
                    InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                qrScanner?.process(image)
                    ?.addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            // Found QR Code
                            stopCamera() // Stop scanning
                            val qrData = barcodes.first().rawValue
                            if (qrData != null) {
                                Log.d("AadhaarFragment", "QR Data Found")
                                // Now, we have the encrypted ZIP data. Ask for password.
                                askForShareCode(qrData.toByteArray(Charsets.ISO_8859_1))
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

    private fun askForShareCode(zipData: ByteArray) {
        // This must run on the UI thread
        activity?.runOnUiThread {
            val builder = AlertDialog.Builder(requireContext())
            val inflater = requireActivity().layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_share_code, null)
            val editText = dialogView.findViewById<EditText>(R.id.share_code_input)

            builder.setView(dialogView)
                .setTitle("Enter Share Code")
                .setMessage("Enter your 4-digit share code for e-Aadhaar")
                .setPositiveButton("OK") { dialog, _ ->
                    val code = editText.text.toString()
                    if (code.length == 4) {
                        instructionText.text = "Verifying..."
                        // Process in background
                        processAadhaarZip(zipData, code)
                    } else {
                        Toast.makeText(context, "Must be 4 digits", Toast.LENGTH_SHORT).show()
                        restartCamera() // Try again
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    dialog.cancel()
                    restartCamera() // User cancelled, restart scanner
                }
                .show()
        }
    }

    private fun processAadhaarZip(zipData: ByteArray, shareCode: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val photoBitmap = extractPhotoFromZip(zipData, shareCode)

                withContext(Dispatchers.Main) {
                    if (photoBitmap != null) {
                        Log.d("AadhaarFragment", "Aadhaar Photo Extracted!")
                        // SUCCESS! Save bitmap and move to SelfieFragment
                        sharedViewModel.idPhotoBitmap.value = photoBitmap
                        (activity as? MainActivity)?.navigateToSelfieFragment()
                    } else {
                        // Failed (e.g., wrong password, bad zip)
                        Toast.makeText(context, "Verification Failed. Wrong share code or invalid QR.", Toast.LENGTH_LONG).show()
                        restartCamera()
                    }
                }
            } catch (e: Exception) {
                Log.e("AadhaarFragment", "Error processing ZIP", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    restartCamera()
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun extractPhotoFromZip(zipData: ByteArray, shareCode: String): Bitmap? {
        ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
            // Set password
            // Note: This assumes the default Java ZipInputStream supports passwords.
            // If not, we'd need a library like 'zip4j'
            // For this example, we'll assume it's a standard zip or needs a different method.

            // --- SIMPLIFIED: Android's ZipInputStream doesn't support passwords.
            // We'll use a common library for this. Please add:
            // implementation 'net.lingala.zip4j:zip4j:2.11.5'
            // ---
            // The QR data is NOT a zip file, it's *BigInteger* data.
            // The UIDAI SDK is required for this.

            // --- MAJOR CORRECTION ---
            // The raw QR data is NOT a ZIP file. It's a custom-encoded block of data.
            // You must use an official SDK or follow a very complex spec to parse it.

            // --- SIMPLIFIED FAKE PARSER FOR THIS EXAMPLE ---
            // Since we cannot implement the full UIDAI spec here, we will *simulate*
            // finding a Base64 string in the QR data.
            // In a real app, 'zipData' would be passed to a UIDAI-provided library.

            // This is a placeholder for the complex parsing logic.
            // Let's assume the QR code *only* contained the Base64 photo string.
            // This is NOT how it works in production, but allows us to build the flow.

            val (base64Photo, error) = simulateAadhaarParse(zipData)

            if (base64Photo != null) {
                val imageBytes = Base64.decode(base64Photo, Base64.DEFAULT)
                return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            } else {
                Log.e("AadhaarFragment", "Failed to parse simulated data: $error")
                return null
            }
        }
    }

    /**
     * THIS IS A SIMULATOR. The real Aadhaar QR data is not this simple.
     * This function pretends to find a Base64 photo inside the QR data.
     */
    private fun simulateAadhaarParse(qrData: ByteArray): Pair<String?, String?> {
        val qrString = String(qrData)
        // Let's pretend the QR string is simple XML: <Kyc>...<Photo>BASE64...</Photo></Kyc>
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(qrData), "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "Photo") {
                    val base64String = parser.nextText()
                    return Pair(base64String, null)
                }
                eventType = parser.next()
            }
            return Pair(null, "No <Photo> tag found")
        } catch (e: Exception) {
            return Pair(null, e.message)
        }
    }


    private fun stopCamera() {
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    private fun restartCamera() {
        activity?.runOnUiThread {
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