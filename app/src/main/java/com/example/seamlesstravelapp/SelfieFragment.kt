package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SelfieFragment : Fragment(R.layout.fragment_selfie) {

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private lateinit var captureButton: Button

    private var livenessState = LivenessState.CENTER
    private enum class LivenessState { CENTER, LEFT, RIGHT, DONE }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView = view.findViewById(R.id.camera_preview)
        instructionText = view.findViewById(R.id.instruction_text)
        captureButton = view.findViewById(R.id.capture_button)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            activityResultLauncher.launch(Manifest.permission.CAMERA)
        }

        captureButton.setOnClickListener {
            sharedViewModel.selfieTaken.value = true
            Toast.makeText(requireContext(), "Selfie captured!", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.navigateToPassportFragment()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer { headAngle ->
                        updateLivenessCheck(headAngle)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("SelfieFragment", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun updateLivenessCheck(headAngle: Float) {
        activity?.runOnUiThread {
            when (livenessState) {
                LivenessState.CENTER -> {
                    instructionText.text = "Look Straight"
                    if (headAngle > -10 && headAngle < 10) {
                        livenessState = LivenessState.LEFT
                    }
                }
                LivenessState.LEFT -> {
                    instructionText.text = "Turn Head Left"
                    if (headAngle > 35) { // Left turn angle
                        livenessState = LivenessState.RIGHT
                    }
                }
                LivenessState.RIGHT -> {
                    instructionText.text = "Turn Head Right"
                    if (headAngle < -35) { // Right turn angle
                        livenessState = LivenessState.DONE
                    }
                }
                LivenessState.DONE -> {
                    instructionText.text = "Liveness Check Complete!"
                    captureButton.visibility = View.VISIBLE
                    // Stop analysis once done
                    if (!cameraExecutor.isShutdown) cameraExecutor.shutdown()
                }
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
    }

    private class FaceAnalyzer(private val listener: (Float) -> Unit) : ImageAnalysis.Analyzer {
        private val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        private val detector = FaceDetection.getClient(highAccuracyOpts)

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                detector.process(image)
                    .addOnSuccessListener { faces ->
                        faces.firstOrNull()?.let { face ->
                            listener(face.headEulerAngleY)
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        }
    }
}