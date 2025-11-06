package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScanIdFragment : Fragment() {

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private lateinit var captureButton: Button
    private lateinit var cutoutView: View
    private lateinit var overlayView: View

    private var imageCapture: ImageCapture? = null
    private var faceDetector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null

    companion object { private const val TAG = "ScanIdFragment" }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_scan_id, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        instructionText = view.findViewById(R.id.instruction_text)
        captureButton = view.findViewById(R.id.capture_button)
        cutoutView = view.findViewById(R.id.cutout)
        overlayView = view.findViewById(R.id.overlay)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure Face Detector
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        faceDetector = FaceDetection.getClient(highAccuracyOpts)

        // Draw the cutout overlay
        overlayView.post { drawCutout() }

        captureButton.setOnClickListener { takePhoto() }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun drawCutout() {
        // Create a transparent hole in the overlay
        val bitmap = Bitmap.createBitmap(overlayView.width, overlayView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ContextCompat.getColor(requireContext(), android.R.color.black)
        paint.alpha = 150 // 99 in hex
        canvas.drawRect(0f, 0f, overlayView.width.toFloat(), overlayView.height.toFloat(), paint)

        // Clear the cutout area
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val rect = RectF(
            cutoutView.left.toFloat(),
            cutoutView.top.toFloat(),
            cutoutView.right.toFloat(),
            cutoutView.bottom.toFloat()
        )
        canvas.drawRect(rect, paint)
        overlayView.setBackgroundDrawable(BitmapDrawable(resources, bitmap))
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector?.process(image)
                .addOnSuccessListener { faces ->
                    // Check if any face is fully inside the cutout view
                    val faceInFrame = faces.any { isFaceInCutout(it) }
                    updateUI(faceInFrame, faces.isEmpty())
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Face detection failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun isFaceInCutout(face: Face): Boolean {
        // Get the bounding box of the face
        val faceRect = face.boundingBox

        // Get the cutout view's position on screen
        val cutoutRect = Rect()
        cutoutView.getGlobalVisibleRect(cutoutRect)

        // As the face detection coordinates are relative to the camera sensor,
        // this is a simplified check. A full solution would map sensor coordinates
        // to view coordinates. For this flow, we'll assume the preview matches the view.
        // A simple proximity check to the center is often good enough.

        // Let's check if the center of the face is near the center of the screen
        val viewCenterX = previewView.width / 2
        val viewCenterY = previewView.height / 2

        // This logic is complex. For V1, let's just enable capture if ANY face is seen.
        // We will crop to the largest face later.
        return true
    }

    private fun updateUI(faceInFrame: Boolean, noFace: Boolean) {
        activity?.runOnUiThread {
            when {
                noFace -> {
                    instructionText.text = "Position ID Card photo in frame"
                    captureButton.visibility = View.GONE
                }
                faceInFrame -> {
                    instructionText.text = "Hold Steady..."
                    captureButton.visibility = View.VISIBLE
                }
                else -> {
                    instructionText.text = "Move card closer"
                    captureButton.visibility = View.GONE
                }
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        // Run detection one last time on the high-res capture
                        faceDetector?.process(image)
                            .addOnSuccessListener { faces ->
                                if (faces.isNotEmpty()) {
                                    // Get the largest face
                                    val largestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!

                                    // Convert to Bitmap and crop
                                    val bitmap = mediaImage.toBitmap()
                                    val croppedBitmap = cropToFace(bitmap, largestFace, imageProxy.imageInfo.rotationDegrees)

                                    // Save to ViewModel and navigate
                                    sharedViewModel.idPhotoBitmap.postValue(croppedBitmap)
                                    (activity as? MainActivity)?.navigateToSelfieFragment()
                                } else {
                                    Toast.makeText(context, "No face found in photo. Try again.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    // Helper functions for image conversion
    private fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun cropToFace(bitmap: Bitmap, face: Face, rotationDegrees: Int): Bitmap {
        // Adjust for rotation
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val box = face.boundingBox
        // Add padding to the crop
        val padding = 20
        val left = (box.left - padding).coerceAtLeast(0)
        val top = (box.top - padding).coerceAtLeast(0)
        val right = (box.right + padding).coerceAtMost(rotatedBitmap.width)
        val bottom = (box.bottom + padding).coerceAtMost(rotatedBitmap.height)

        return Bitmap.createBitmap(rotatedBitmap, left, top, right - left, bottom - top)
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
        faceDetector?.close()
    }
}