package com.example.seamlesstravelapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteOrder
import kotlin.math.sqrt

class FaceRecognizer(context: Context) {

    companion object {
        const val COSINE_SIM_THRESHOLD = 0.55f
        const val L2_DISTANCE_THRESHOLD = 1.15f
        private const val MODEL_FILE = "mobile_facenet.tflite"
        private const val INPUT_SIZE = 112
    }

    private val interpreter: Interpreter
    private val imageProcessor: ImageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(127.5f, 127.5f))
        .build()

    init {
        val model = FileUtil.loadMappedFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(model, options)
        Log.d("FaceRecognizer", "Loaded $MODEL_FILE from assets")
    }

    fun getEmbedding(faceBmp112: Bitmap): FloatArray {
        var timg = TensorImage.fromBitmap(faceBmp112)
        timg = imageProcessor.process(timg)
        timg.buffer.order(ByteOrder.nativeOrder())
        val outLen = interpreter.getOutputTensor(0).shape().last()
        val out = Array(1) { FloatArray(outLen) }
        interpreter.run(timg.buffer, out)
        val v = out[0]; l2NormalizeInPlace(v); return v
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding sizes must match" }
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        val denom = (kotlin.math.sqrt(na.toDouble()) * kotlin.math.sqrt(nb.toDouble())).toFloat()
        if (denom <= 1e-6f) return 0f
        return (dot / denom).coerceIn(0f, 1f)
    }

    fun l2Distance(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding sizes must match" }
        var s = 0f; for (i in a.indices) { val d = a[i]-b[i]; s += d*d }; return kotlin.math.sqrt(s)
    }

    private fun l2NormalizeInPlace(v: FloatArray) {
        var sum = 0f; for (x in v) sum += x*x
        val n = sqrt(sum); if (n > 1e-6f) for (i in v.indices) v[i] /= n
    }

    fun close() { try { interpreter.close() } catch (_: Exception) {} }
}
