package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

class SelfieFragment : Fragment(R.layout.fragment_selfie) {

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var instructionText: TextView
    private lateinit var captureButton: Button

    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var faceDetector: FaceDetector? = null
    private lateinit var recognizer: FaceRecognizer

    // High-accuracy detector for single images (Aadhaar + Selfie)
    private var highAccuracyFaceDetector: FaceDetector? = null

    // Liveness FSM state
    private enum class Step { CENTER, SIDE1, SIDE2, BACK_CENTER, DONE }
    private var step = Step.CENTER
    private var stepStartedAt = 0L
    private var side1IsPositive: Boolean? = null
    private val yawWindow = ArrayDeque<Float>()
    private val maxWindow = 6
    private var yawBaseline = 0f
    private var baselineSamples = 0
    private val dwellMs = 400L
    private val centerTol = 7f
    private val yawTurnTol = 14f
    private val noseTurnTol = 0.07f
    private val running = AtomicBoolean(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView     = view.findViewById(R.id.camera_preview)
        instructionText = view.findViewById(R.id.instruction_text)
        captureButton   = view.findViewById(R.id.capture_button)

        captureButton.visibility = View.VISIBLE
        captureButton.isEnabled = false

        cameraExecutor = Executors.newSingleThreadExecutor()
        recognizer = FaceRecognizer(requireContext().applicationContext)

        // Fast detector for real-time liveness
        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .enableTracking()
                .setMinFaceSize(0.15f)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )

        // Accurate detector for matching (as per Python logic)
        highAccuracyFaceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )

        captureButton.setOnClickListener { takeSelfieAndVerify() }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() {
        faceDetector?.close()
        highAccuracyFaceDetector?.close()
        recognizer.close()
        cameraExecutor.shutdown()
        super.onDestroyView()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, imageAnalyzer)
                instructionText.text = "Look straight"
                resetFsm(Step.CENTER)
            } catch (e: Exception) {
                Log.e("SelfieFragment", "bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun resetFsm(s: Step) {
        step = s
        stepStartedAt = SystemClock.elapsedRealtime()
        yawWindow.clear()
        baselineSamples = 0
        yawBaseline = 0f
        side1IsPositive = null
    }

    // --- LIVENESS DETECTION (Analyzes camera feed) ---

    private val analyzer = ImageAnalysis.Analyzer { proxy ->
        if (!running.compareAndSet(false, true)) { proxy.close(); return@Analyzer }
        val media = proxy.image ?: run { running.set(false); proxy.close(); return@Analyzer }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        faceDetector?.process(input)
            ?.addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    instructionText.text = "Align your face"
                    if (SystemClock.elapsedRealtime() - stepStartedAt > 2500) resetFsm(Step.CENTER)
                } else {
                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    updateStateWithFace(face)
                }
            }
            ?.addOnFailureListener { Log.e("SelfieFragment", "detector fail", it) }
            ?.addOnCompleteListener { running.set(false); proxy.close() }
    }

    private fun updateStateWithFace(face: Face) {
        val rawYaw = face.headEulerAngleY
        pushYaw(rawYaw)
        val yaw = smoothYaw()

        if (step == Step.CENTER || step == Step.BACK_CENTER) {
            if (abs(yawDelta(yaw)) <= centerTol) {
                yawBaseline = (yawBaseline * baselineSamples + rawYaw) / (baselineSamples + 1)
                baselineSamples++
            }
        }

        val noseX = face.getLandmark(FaceLandmark.NOSE_BASE)?.position?.x
        val box = face.boundingBox
        val boxCenterX = box.centerX().toFloat()
        val width = box.width().coerceAtLeast(1)
        val noseOffsetNorm = if (noseX != null) (noseX - boxCenterX) / width else 0f
        val noseRight = noseOffsetNorm > noseTurnTol
        val noseLeft  = noseOffsetNorm < -noseTurnTol

        advanceLiveness(yaw, noseLeft, noseRight)
    }

    private fun yawDelta(y: Float): Float = y - yawBaseline
    private fun pushYaw(y: Float) { if (yawWindow.size >= maxWindow) yawWindow.removeFirst(); yawWindow.addLast(y) }
    private fun smoothYaw(): Float { if (yawWindow.isEmpty()) return 0f; var s = 0f; for (v in yawWindow) s += v; return s / yawWindow.size }

    private fun advanceLiveness(yawSmoothed: Float, noseLeft: Boolean, noseRight: Boolean) {
        val now = SystemClock.elapsedRealtime()
        fun held() = (now - stepStartedAt) >= dwellMs
        fun enter(s: Step) { step = s; stepStartedAt = now }

        val dy = yawDelta(yawSmoothed)
        val turnedRight = (dy > yawTurnTol) || noseRight
        val turnedLeft  = (dy < -yawTurnTol) || noseLeft
        val centered    = abs(dy) <= centerTol

        when (step) {
            Step.CENTER -> {
                instructionText.text = "Turn to one side"
                if (turnedRight) { side1IsPositive = true; enter(Step.SIDE1) }
                else if (turnedLeft) { side1IsPositive = false; enter(Step.SIDE1) }
            }
            Step.SIDE1 -> {
                instructionText.text = if (side1IsPositive == true) "Hold right" else "Hold left"
                if (held()) enter(Step.SIDE2)
            }
            Step.SIDE2 -> {
                instructionText.text = if (side1IsPositive == true) "Now turn left" else "Now turn right"
                val needPositive = side1IsPositive != true
                val onSecondSide = if (needPositive) turnedRight else turnedLeft
                if (onSecondSide && held()) enter(Step.BACK_CENTER)
            }
            Step.BACK_CENTER -> {
                instructionText.text = "Back to center"
                if (centered && held()) { enter(Step.DONE); onLivenessDone() }
            }
            Step.DONE -> Unit
        }
    }

    private fun onLivenessDone() {
        instructionText.text = "Liveness complete"
        captureButton.isEnabled = true
        imageAnalyzer?.clearAnalyzer()
        view?.postDelayed({ takeSelfieAndVerify() }, 450)
    }

    // --- FACE MATCHING (Runs on button click) ---

    private fun takeSelfieAndVerify() {
        val ic = imageCapture ?: return
        captureButton.isEnabled = false
        ic.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                @OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bmp = imageProxyToBitmap(imageProxy)
                        if (bmp == null) {
                            Toast.makeText(requireContext(), "Capture failed. Try again.", Toast.LENGTH_SHORT).show()
                            captureButton.isEnabled = true
                            return
                        }
                        // This is the main function that performs the logic from your Python files
                        processAndCompareFaces(bmp, imageProxy.imageInfo.rotationDegrees)
                    } finally { imageProxy.close() }
                }
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                    captureButton.isEnabled = true
                }
            }
        )
    }

    /**
     * This function implements the logic from your Python scripts.
     * It aligns BOTH the Aadhaar photo and the Selfie photo before generating embeddings.
     */
    private fun processAndCompareFaces(selfieBitmap: Bitmap, rotation: Int) {
        val rotatedSelfie = rotateBitmap(selfieBitmap, rotation)

        // --- THIS IS STEP 7 from your flow ---
        val aadhaarBitmap = sharedViewModel.idPhotoBitmap.value
        if (aadhaarBitmap == null) {
            Toast.makeText(requireContext(), "Aadhaar data missing. Rescan Aadhaar.", Toast.LENGTH_LONG).show()
            (activity as? MainActivity)?.navigateToAadhaarFragment()
            return
        }

        // 1. Asynchronously align both faces (Implements logic from your Python files)
        val selfieAlignTask = detectAndAlignFace(rotatedSelfie, isFlipped = true)
        val aadhaarAlignTask = detectAndAlignFace(aadhaarBitmap, isFlipped = false)

        Tasks.whenAllSuccess<Bitmap>(selfieAlignTask, aadhaarAlignTask).addOnSuccessListener { results ->
            // 2. We now have two perfectly aligned 112x112 bitmaps
            val alignedSelfie = results[0]
            val alignedAadhaar = results[1]

            // 3. Generate embeddings from the aligned bitmaps
            val selfEmb = recognizer.getEmbedding(alignedSelfie)
            val idEmb = recognizer.getEmbedding(alignedAadhaar)

            // 4. Compare embeddings
            val cos = recognizer.cosineSimilarity(idEmb, selfEmb)
            val dist = recognizer.l2Distance(idEmb, selfEmb)

            // 5. Make decision
            val strongL2 = dist <= 1.00f
            val pass = (cos >= FaceRecognizer.COSINE_SIM_THRESHOLD && dist <= FaceRecognizer.L2_DISTANCE_THRESHOLD) ||
                    (strongL2 && cos >= 0.50f)

            Log.d("SelfieFragment", "Match complete: Cosine=$cos, L2-Dist=$dist, Pass=$pass")

            if (pass) {
                Toast.makeText(requireContext(), "Verified (Score: ${String.format("%.2f", cos)})", Toast.LENGTH_LONG).show()
                (activity as? MainActivity)?.navigateToBoardingPassFragment() // Go to next step
            } else {
                Toast.makeText(requireContext(), "Not a match. Please rescan your ID.", Toast.LENGTH_LONG).show()
                (activity as? MainActivity)?.restartProcess() // Failed, restart full process
            }

        }.addOnFailureListener { e ->
            // This happens if a face wasn't found in one of the images
            Log.e("SelfieFragment", "Alignment failed for one or both images.", e)

            // --- THIS IS THE FIX ---
            Toast.makeText(requireContext(), "Could not find face in one or both images. ${e.message}", Toast.LENGTH_LONG).show()

            captureButton.isEnabled = true
            (activity as? MainActivity)?.restartProcess() // Restart
        }
    }

    /**
     * New function to align and crop ANY bitmap (Aadhaar or Selfie)
     * This implements the logic from your Python scripts.
     * @param isFlipped True if this is the live selfie (front camera)
     * @return A Task that will resolve with a 112x112 aligned Bitmap
     */
    private fun detectAndAlignFace(bitmap: Bitmap, isFlipped: Boolean): Task<Bitmap> {
        val input = InputImage.fromBitmap(bitmap, 0)
        return highAccuracyFaceDetector!!.process(input)
            .continueWith<Bitmap>(cameraExecutor) { task ->
                val faces = task.result
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    throw Exception("No face found in image.")
                }

                // Crop and align the face
                val crop = cropWithPadding(bitmap, face.boundingBox, 0.35f, 0.45f)

                // Flip if it's the selfie
                val flipped = if(isFlipped) flipHorizontal(crop) else crop

                // Scale to 112x112 (as required by model)
                val alignedBitmap = Bitmap.createScaledBitmap(flipped, 112, 112, true)

                alignedBitmap
            }
    }


    // --- BITMAP UTILITY FUNCTIONS ---

    private fun rotateBitmap(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
    private fun flipHorizontal(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
    private fun cropWithPadding(src: Bitmap, box: Rect, padW: Float, padH: Float): Bitmap {
        val pw = (box.width() * padW).toInt()
        val ph = (box.height() * padH).toInt()
        val l = (box.left - pw).coerceAtLeast(0)
        val t = (box.top - ph).coerceAtLeast(0)
        val r = (box.right + pw).coerceAtMost(src.width)
        val b = (box.bottom + ph).coerceAtMost(src.height)
        val w = max(1, r - l)
        val h = max(1, b - t)
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                    val buf = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                ImageFormat.YUV_420_888 -> {
                    val img = imageProxy.image ?: return null
                    yuvToBitmap(img)
                }
                else -> {
                    val buf = imageProxy.planes.firstOrNull()?.buffer ?: return null
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (t: Throwable) {
            Log.e("SelfieFragment", "imageProxyToBitmap failed", t)
            null
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun yuvToBitmap(image: Image): Bitmap? {
        fun yuvToNv21(image: Image): ByteArray {
            val w = image.width; val h = image.height
            val ySize = w * h; val uvSize = w * h / 4
            val out = ByteArray(ySize + 2 * uvSize) // NV21 format size

            val y = image.planes[0].buffer
            val u = image.planes[1].buffer
            val v = image.planes[2].buffer

            val yRowStride = image.planes[0].rowStride
            val uRowStride = image.planes[1].rowStride
            val vRowStride = image.planes[2].rowStride
            val uPixelStride = image.planes[1].pixelStride
            val vPixelStride = image.planes[2].pixelStride

            var yPos = 0
            for (row in 0 until h) {
                y.position(yPos)
                y.get(out, row * w, w)
                yPos += yRowStride
            }

            var uvPos = ySize
            for (row in 0 until h / 2) {
                val vPos = row * vRowStride
                val uPos = row * uRowStride
                v.position(vPos)
                u.position(uPos)
                for (col in 0 until w / 2) {
                    if (vPixelStride == 2 && uPixelStride == 2) {
                        out[uvPos++] = v.get(col * vPixelStride)
                        out[uvPos++] = u.get(col * uPixelStride)
                    } else { // Handle planar (pixel stride = 1)
                        out[uvPos++] = v.get()
                        out[uvPos++] = u.get()
                    }
                }
            }
            return out
        }

        return try {
            val nv21 = yuvToNv21(image)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val baos = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 95, baos)
            val bytes = baos.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("SelfieFragment", "YUV->Bitmap failed: ${e.message}", e)
            null
        }
    }
}