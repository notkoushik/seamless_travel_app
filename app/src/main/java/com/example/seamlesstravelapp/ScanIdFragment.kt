// unchanged from your working version; included for completeness
package com.example.seamlesstravelapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
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
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class ScanIdFragment : Fragment(R.layout.fragment_scan_id) {
    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var statusText: TextView

    private var imageCapture: ImageCapture? = null
    private var faceDetector: FaceDetector? = null
    private var boundCameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        previewView   = view.findViewById(R.id.previewView)
        captureButton = view.findViewById(R.id.capture_button)
        statusText    = view.findViewById(R.id.instruction_text)
        captureButton.visibility = View.VISIBLE

        cameraExecutor = Executors.newSingleThreadExecutor()

        faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build()
        )

        captureButton.setOnClickListener { captureId() }
        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroyView() {
        try { boundCameraProvider?.unbindAll() } catch (_: Exception) {}
        faceDetector?.close()
        cameraExecutor.shutdown()
        super.onDestroyView()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Log.e("ScanIdFragment", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun captureId() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false
        statusText.text = "Capturing…"

        capture.takePicture(
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageCapturedCallback() {
                @OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bmp = imageProxyToBitmap(imageProxy)
                        if (bmp == null) {
                            statusText.text = "Failed to decode image"
                            captureButton.isEnabled = true
                            return
                        }
                        processIdBitmap(bmp, imageProxy.imageInfo.rotationDegrees)
                    } finally { imageProxy.close() }
                }
                override fun onError(exception: ImageCaptureException) {
                    statusText.text = "Capture failed: ${exception.message}"
                    captureButton.isEnabled = true
                }
            }
        )
    }

    private fun processIdBitmap(bitmap: Bitmap, rotation: Int) {
        val rotated = rotateBitmap(bitmap, rotation)
        faceDetector?.process(InputImage.fromBitmap(rotated, 0))
            ?.addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    statusText.text = "No face found on ID. Try again."
                    captureButton.isEnabled = true
                    return@addOnSuccessListener
                }

                val crop = cropWithPadding(rotated, face.boundingBox, 0.25f, 0.35f)
                val input = Bitmap.createScaledBitmap(crop, 112, 112, true)
                sharedViewModel.idPhotoBitmap.value = input

                statusText.text = "ID captured. Starting liveness…"
                try { boundCameraProvider?.unbindAll() } catch (_: Exception) {}
                (activity as? MainActivity)?.navigateToSelfieFragment()
            }
            ?.addOnFailureListener { e ->
                statusText.text = "ID processing failed: ${e.message}"
                captureButton.isEnabled = true
            }
    }

    private fun rotateBitmap(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
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
                    val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (t: Throwable) {
            Log.e("ScanIdFragment", "imageProxyToBitmap failed", t)
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
            Log.e("ScanIdFragment", "YUV->Bitmap failed: ${e.message}", e)
            null
        }
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
}
