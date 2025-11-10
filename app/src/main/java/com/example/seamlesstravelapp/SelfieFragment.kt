// unchanged structure, includes tuned liveness thresholds;
// updated pass rule with a safe “strong L2 OR” fallback
// (full file kept so you can paste)
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
    private val yawReleaseTol = 10f
    private val noseTurnTol = 0.07f
    private val noseReleaseTol = 0.05f

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

        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .enableTracking()
                .setMinFaceSize(0.15f)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )

        captureButton.setOnClickListener { takeSelfieAndVerify() }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() {
        faceDetector?.close()
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

    private fun updateStateWithFace(face: com.google.mlkit.vision.face.Face) {
        val rawYaw = face.headEulerAngleY
        pushYaw(rawYaw)
        val yaw = smoothYaw()

        if (step == Step.CENTER || step == Step.BACK_CENTER) {
            if (abs(yawDelta(yaw)) <= centerTol) {
                yawBaseline = (yawBaseline * baselineSamples + rawYaw) / (baselineSamples + 1)
                baselineSamples++
            }
        }

        val noseX = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position?.x
        val box = face.boundingBox
        val boxCenterX = box.centerX().toFloat()
        val width = box.width().coerceAtLeast(1)
        val noseOffsetNorm = if (noseX != null) (noseX - boxCenterX) / width else 0f
        val noseRight = noseOffsetNorm > noseTurnTol
        val noseLeft  = noseOffsetNorm < -noseTurnTol

        advanceLiveness(yaw, noseLeft, noseRight, face)
    }

    private fun yawDelta(y: Float): Float = y - yawBaseline
    private fun pushYaw(y: Float) { if (yawWindow.size >= maxWindow) yawWindow.removeFirst(); yawWindow.addLast(y) }
    private fun smoothYaw(): Float { if (yawWindow.isEmpty()) return 0f; var s = 0f; for (v in yawWindow) s += v; return s / yawWindow.size }

    private fun advanceLiveness(yawSmoothed: Float, noseLeft: Boolean, noseRight: Boolean, face: Face) {
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
                if (turnedRight && face.bigEnough()) { side1IsPositive = true; enter(Step.SIDE1) }
                else if (turnedLeft && face.bigEnough()) { side1IsPositive = false; enter(Step.SIDE1) }
            }
            Step.SIDE1 -> {
                instructionText.text = if (side1IsPositive == true) "Hold right" else "Hold left"
                if (held()) enter(Step.SIDE2) else if (!stillOnSide(dy, side1IsPositive)) stepStartedAt = now
            }
            Step.SIDE2 -> {
                instructionText.text = if (side1IsPositive == true) "Now turn left" else "Now turn right"
                val needPositive = side1IsPositive != true
                val onSecondSide = if (needPositive) turnedRight else turnedLeft
                if (onSecondSide && held()) enter(Step.BACK_CENTER) else if (stillOnSide(dy, side1IsPositive)) stepStartedAt = now
            }
            Step.BACK_CENTER -> {
                instructionText.text = "Back to center"
                if (centered && held()) { enter(Step.DONE); onLivenessDone() }
            }
            Step.DONE -> Unit
        }
    }

    private fun stillOnSide(dy: Float, positiveSide: Boolean?): Boolean =
        if (positiveSide == true) dy > yawReleaseTol else dy < -yawReleaseTol

    private fun Face.bigEnough(): Boolean {
        val width = previewView.width.takeIf { it > 0 } ?: return true
        return boundingBox.width() >= width * 0.16f
    }

    private fun onLivenessDone() {
        instructionText.text = "Liveness complete"
        captureButton.isEnabled = true
        imageAnalyzer?.clearAnalyzer()
        view?.postDelayed({ takeSelfieAndVerify() }, 450)
    }

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
                        processSelfieBitmap(bmp, imageProxy.imageInfo.rotationDegrees)
                    } finally { imageProxy.close() }
                }
                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                    captureButton.isEnabled = true
                }
            }
        )
    }

    private fun processSelfieBitmap(bmp: Bitmap, rotation: Int) {
        val rotated = rotateBitmap(bmp, rotation)
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .build()
        )
        detector.process(InputImage.fromBitmap(rotated, 0))
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    Toast.makeText(requireContext(), "No face in selfie", Toast.LENGTH_SHORT).show()
                    captureButton.isEnabled = true
                    return@addOnSuccessListener
                }

                val cropA = cropWithPadding(rotated, face.boundingBox, 0.35f, 0.45f)
                val cropB = cropWithPadding(rotated, face.boundingBox, 0.28f, 0.38f)
                val inputA = Bitmap.createScaledBitmap(flipHorizontal(cropA), 112, 112, true)
                val inputB = Bitmap.createScaledBitmap(flipHorizontal(cropB), 112, 112, true)

                val idBmp = sharedViewModel.idPhotoBitmap.value
                if (idBmp == null) {
                    Toast.makeText(requireContext(), "ID not captured. Going back…", Toast.LENGTH_SHORT).show()
                    (activity as? MainActivity)?.navigateToScanIdFragment()
                    return@addOnSuccessListener
                }

                val idEmb = recognizer.getEmbedding(idBmp)
                val selfEmbA = recognizer.getEmbedding(inputA)
                val selfEmbB = recognizer.getEmbedding(inputB)

                val cosA = recognizer.cosineSimilarity(idEmb, selfEmbA)
                val distA = recognizer.l2Distance(idEmb, selfEmbA)
                val cosB = recognizer.cosineSimilarity(idEmb, selfEmbB)
                val distB = recognizer.l2Distance(idEmb, selfEmbB)

                val (cos, dist) = if (scoreBetter(cosA, distA, cosB, distB)) cosA to distA else cosB to distB
                val strongL2 = dist <= 1.00f
                val pass = (cos >= FaceRecognizer.COSINE_SIM_THRESHOLD && dist <= FaceRecognizer.L2_DISTANCE_THRESHOLD) ||
                        (strongL2 && cos >= 0.50f)

                Log.d("SelfieFragment", "cos=$cos dist=$dist pass=$pass")

                if (pass) {
                    Toast.makeText(requireContext(), "Verified (${String.format("%.2f", cos)})", Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.navigateToConfirmationFragment("passport")
                } else {
                    Toast.makeText(requireContext(), "Not a match. Please rescan your ID.", Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.navigateToScanIdFragment()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Selfie processing failed: ${it.message}", Toast.LENGTH_LONG).show()
                captureButton.isEnabled = true
            }
    }

    private fun scoreBetter(cosA: Float, distA: Float, cosB: Float, distB: Float): Boolean =
        if (cosA != cosB) cosA > cosB else distA < distB

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
            val ySize = w * h; val uvSize = w * h / 2
            val out = ByteArray(ySize + uvSize)

            val y = image.planes[0].buffer
            val u = image.planes[1].buffer
            val v = image.planes[2].buffer

            val yRowStride = image.planes[0].rowStride
            val uRowStride = image.planes[1].rowStride
            val vRowStride = image.planes[2].rowStride
            val uPixelStride = image.planes[1].pixelStride
            val vPixelStride = image.planes[2].pixelStride

            var pos = 0
            if (yRowStride == w) {
                y.get(out, 0, ySize); pos = ySize
            } else {
                var yPos = 0
                for (row in 0 until h) {
                    y.position(yPos)
                    y.get(out, pos, w)
                    yPos += yRowStride
                    pos += w
                }
            }
            for (row in 0 until h / 2) {
                val vPos = row * vRowStride
                val uPos = row * uRowStride
                v.position(vPos)
                u.position(uPos)
                for (col in 0 until w / 2) {
                    out[pos++] = v.get(col * vPixelStride)
                    out[pos++] = u.get(col * uPixelStride)
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
