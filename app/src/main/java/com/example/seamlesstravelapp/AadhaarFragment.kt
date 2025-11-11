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
import com.google.common.util.concurrent.ListenableFuture
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
    private var qrScanner: BarcodeScanner? = null

    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var isProcessing = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView = view.findViewById(R.id.previewView)
        instructionText = view.findViewById(R.id.instruction_text)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        qrScanner = BarcodeScanning.getClient(options)

        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

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
    private val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (isProcessing.get()) {
            imageProxy.close()
            return@Analyzer
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            qrScanner?.process(image)
                ?.addOnSuccessListener { barcodes ->
                    val qrData = barcodes.firstOrNull()?.rawValue
                    if (!qrData.isNullOrBlank() && isProcessing.compareAndSet(false, true)) {
                        stopCamera()
                        Log.d("AadhaarFragment", "QR Data Found.")
                        lifecycleScope.launch(Dispatchers.IO) {
                            processQrData(qrData)
                        }
                    }
                }
                ?.addOnFailureListener {
                    Log.e("AadhaarFragment", "QR Scan failed", it)
                }
                ?.addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }


    private suspend fun processQrData(qrData: String) {
        // --- THIS IS THE NEW PARSER LOGIC ---

        var name: String? = null
        // Your new XML format doesn't have a UID, so we'll set a placeholder
        var uid: String = "N/A"
        var dob: String? = null
        var gender: String? = null
        var photoBase64: String? = null
        var currentTag: String? = null

        suspend fun showToast(message: String) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(qrData))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "Poi") {
                            // Data is in attributes of the <Poi> tag
                            name = parser.getAttributeValue(null, "name")
                            dob = parser.getAttributeValue(null, "dob")
                            gender = parser.getAttributeValue(null, "gender")
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag == "Photo" && parser.text != null && parser.text.isNotBlank()) {
                            // Handle multi-line Base64 text
                            photoBase64 = if (photoBase64 == null) parser.text else photoBase64 + parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = null
                    }
                }
                eventType = parser.next()
            }

            // Check if we found all the new fields
            if (name != null && dob != null && gender != null && photoBase64 != null) {
                val photoBytes = Base64.decode(photoBase64, Base64.DEFAULT)
                val photoBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)

                val aadhaarData = AadhaarData(uid = uid, name = name, gender = gender, dob = dob, photo = photoBitmap)

                withContext(Dispatchers.Main) {
                    sharedViewModel.aadhaarData.value = aadhaarData
                    sharedViewModel.idPhotoBitmap.value = photoBitmap // For selfie matching
                    Toast.makeText(context, "Aadhaar Scanned: $name", Toast.LENGTH_LONG).show()

                    // Navigate to the confirmation card
                    (activity as? MainActivity)?.navigateToAadhaarConfirmationFragment()
                }
            } else {
                Log.w("AadhaarFragment", "Parsed XML, but missing fields: N=$name, D=$dob, G=$gender, P=${photoBase64 != null}")
                showToast("Could not find all required data in QR. Try again.")
                restartCamera() // Try again
            }
        } catch (e: Exception) {
            Log.e("AadhaarFragment", "Error processing XML", e)
            Log.e("AadhaarFragment", "Failed to parse this data: $qrData")
            showToast("Error: Invalid QR Code. Try again.")
            restartCamera()
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
            isProcessing.set(false) // Reset the flag
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