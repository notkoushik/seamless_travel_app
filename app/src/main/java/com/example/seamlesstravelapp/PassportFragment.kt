package com.example.seamlesstravelapp

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle as AndroidBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.seamlesstravelapp.databinding.FragmentPassportBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.MRZInfo
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
class PassportFragment : Fragment() {

    private var _binding: FragmentPassportBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null

    // 👇 THIS is the field your build was missing
    private var nfcAdapter: NfcAdapter? = null

    private var mrzInfo: MRZInfo? = null
    private var isScanning = false
    private var mlKitReady = false
    private var isCameraReady = false

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lastBestL2: String? = null
    private var stableHits: Int = 0
    private val STABLE_REQUIRED = 1

    private var lastToastAtMs = 0L
    private fun toastOnce(msg: String, minIntervalMs: Long = 2500) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastAtMs >= minIntervalMs) {
            context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
            lastToastAtMs = now
        }
    }

    companion object { private const val TAG = "PassportFragment" }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                toastOnce("✅ Camera permission granted!")
                startCamera()
            } else {
                toastOnce("❌ Camera permission required!")
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: AndroidBundle?
    ): View {
        _binding = FragmentPassportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: AndroidBundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available")
            toastOnce("ℹ️ NFC not available on this device")
        } else {
            val enabled = nfcAdapter!!.isEnabled
            toastOnce(if (enabled) "NFC: ✅ Enabled" else "NFC: ⚠️ Disabled")
            Log.d(TAG, "NFC available, enabled=$enabled")
        }

        checkMLKitModels()
        binding.scanMrzButton.visibility = View.GONE // automatic scanning

        if (allPermissionsGranted()) startCamera()
        else permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun checkMLKitModels() {
        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val testImage = InputImage.fromBitmap(testBitmap, 0)
        recognizer.process(testImage)
            .addOnSuccessListener {
                mlKitReady = true
                attemptToStartScanning()
            }
            .addOnFailureListener {
                _binding?.root?.postDelayed({ checkMLKitModels() }, 5000)
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val aspect = AspectRatioStrategy(
                    AspectRatio.RATIO_4_3,
                    AspectRatioStrategy.FALLBACK_RULE_AUTO
                )

                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .build()
                    )
                    .build()
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

                imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(aspect)
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                isCameraReady = true
                attemptToStartScanning()
            } catch (e: Exception) {
                toastOnce("❌ Camera failed: ${e.message}")
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun attemptToStartScanning() {
        if (mlKitReady && isCameraReady && !isScanning) {
            binding.resultText.text = "📸 Scanning MRZ…"
            isScanning = true
            lastBestL2 = null
            stableHits = 0
            analyzeImage()
        }
    }

    private fun analyzeImage() {
        val analysis = imageAnalysis ?: run {
            toastOnce("❌ Camera not ready!")
            isScanning = false
            return
        }

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanning) { imageProxy.close(); return@setAnalyzer }
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                binding.resultText.post { binding.resultText.text = "❌ Camera frame error" }
                imageProxy.close()
                return@setAnalyzer
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (visionText.textBlocks.isEmpty()) {
                        binding.resultText.text = "⚠️ No text detected\nAdjust angle/lighting"
                    } else {
                        val mrz = findMrzFromStructuredLines(visionText)
                        if (mrz != null) {
                            val (line1, line2) = mrz
                            if (lastBestL2 == null || lastBestL2 != line2) {
                                lastBestL2 = line2; stableHits = 1
                            } else stableHits += 1
                            binding.resultText.text = "🔎 MRZ candidate ($stableHits/$STABLE_REQUIRED)"
                            if (stableHits >= STABLE_REQUIRED) {
                                isScanning = false
                                imageAnalysis?.clearAnalyzer()
                                processMRZResult(line1, line2)
                            }
                        } else {
                            binding.resultText.text = "⚠️ No MRZ pattern found\nTip: fill the screen with the two MRZ lines"
                        }
                    }
                }
                .addOnFailureListener { e ->
                    toastOnce("❌ ${e.message}")
                    binding.resultText.text = "❌ ${e.message}"
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    // -------- MRZ parsing helpers --------

    private fun findMrzFromStructuredLines(vText: Text): Pair<String, String>? {
        val potentialLine1s = mutableListOf<String>()
        val potentialLine2s = mutableListOf<String>()

        val candidates = mutableListOf<String>()
        for (block in vText.textBlocks) {
            for (line in block.lines) {
                candidates += sanitizeMrzLine(line.text)
            }
        }
        for (line in candidates) {
            if (line.isEmpty()) continue
            if (line.startsWith("P<")) potentialLine1s += line.padEnd(44, '<').take(44)
            tryFixAndValidateLine2(line)?.let { potentialLine2s += it }
        }
        return if (potentialLine1s.isNotEmpty() && potentialLine2s.isNotEmpty())
            potentialLine1s.first() to potentialLine2s.first()
        else null
    }

    private fun sanitizeMrzLine(src: String): String {
        var t = src.uppercase()
        t = t.replace("«", "<<").replace("»", "<")
        t = t.replace("‹", "<").replace("›", "<")
        t = t.replace("[\\[\\{\\(]".toRegex(), "<")
        t = t.replace("[\\]\\}\\)]".toRegex(), "<")
        t = t.replace("[=]+".toRegex(), "<")
        t = t.replace("\\s+".toRegex(), "")
        t = t.replace("[^A-Z0-9<]".toRegex(), "")
        return t
    }

    private fun tryFixAndValidateLine2(raw: String): String? {
        if (raw.length < 30) return null
        var f = raw.padEnd(44, '<').take(44)

        fun toDigit(c: Char): Char = when (c) {
            'O','D','Q' -> '0'; 'I','L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'G' -> '6'; 'B' -> '8'; else -> c
        }
        fun fixRange(s: String, a: Int, b: Int): String {
            val ch = s.toCharArray(); for (i in a until b) ch[i] = toDigit(ch[i]); return String(ch)
        }
        fun fixAt(s: String, idx: Int): String {
            val ch = s.toCharArray(); ch[idx] = toDigit(ch[idx]); return String(ch)
        }

        f = fixRange(f, 0, 9); f = fixAt(f, 9); f = fixRange(f, 13, 19)
        f = fixAt(f, 19); f = fixRange(f, 21, 27); f = fixAt(f, 27)
        f = fixRange(f, 28, 42); f = fixAt(f, 42); f = fixAt(f, 43)

        val nat = f.substring(10, 13); val dob = f.substring(13, 19)
        val sex = f[20]; val exp = f.substring(21, 27)
        if (!nat.matches(Regex("^[A-Z<]{3}$"))) return null
        if (!dob.matches(Regex("^\\d{6}$"))) return null
        if (!exp.matches(Regex("^\\d{6}$"))) return null
        if (sex !in charArrayOf('M','F','<')) return null
        if (f.length <= 43 || !f[9].isDigit() || !f[19].isDigit() || !f[27].isDigit() || !f[43].isDigit()) return null

        val doc = f.substring(0, 9); val docCD = f[9]; val dobCD = f[19]
        val expCD = f[27]; val opt = f.substring(28, 43); val cmpCD = f[43]

        if (mrzCheck(doc) != docCD) return null
        if (mrzCheck(dob) != dobCD) return null
        if (mrzCheck(exp) != expCD) return null
        val composite = doc + docCD + dob + dobCD + exp + expCD + opt
        if (mrzCheck(composite) != cmpCD) return null
        return f
    }

    private fun mrzCheck(field: String): Char {
        val w = intArrayOf(7, 3, 1); var s = 0
        for (i in field.indices) {
            val v = when (val ch = field[i]) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                '<' -> 0
                else -> 0
            }
            s += v * w[i % 3]
        }
        return ('0'.code + (s % 10)).toChar()
    }

    private fun processMRZResult(line1: String, line2: String) {
        try {
            val info = MRZInfo("$line1\n$line2")
            mrzInfo = info

            binding.resultText.text = "✅ MRZ Scanned!\n${info.secondaryIdentifier} ${info.primaryIdentifier}"

            if (nfcAdapter?.isEnabled != true) {
                toastOnce("ℹ️ NFC not available — continuing without chip")
                proceedWithoutNfc(info)
                return
            }
            toastOnce("✅ MRZ scanned — place phone on passport")
            switchToNfcMode()
        } catch (e: Exception) {
            Log.e(TAG, "MRZ parsing error: ${e.message}", e)
            binding.resultText.text = "❌ Parse Error. Try again."
            isScanning = false
            attemptToStartScanning()
        }
    }

    private fun proceedWithoutNfc(info: MRZInfo) {
        val pd = PassportData(
            name = "${info.secondaryIdentifier} / ${info.primaryIdentifier}",
            passportNumber = info.documentNumber,
            dateOfBirth = info.dateOfBirth,
            expiryDate = info.dateOfExpiry,
            photo = null
        )
        sharedViewModel.passportData.postValue(pd)
        (activity as? MainActivity)?.navigateToConfirmationFragment("passport")
    }

    private fun switchToNfcMode() {
        binding.cameraGroup.visibility = View.GONE
        binding.nfcGroup.visibility = View.VISIBLE
        enableNfcForegroundDispatch()
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: run {
            toastOnce("❌ NFC not available"); return
        }
        if (!adapter.isEnabled) {
            toastOnce("⚠️ Enable NFC in settings")
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }
        val intent = Intent(requireContext(), requireActivity().javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            requireContext(), 0, intent, PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(requireActivity(), pendingIntent, null, null)
        toastOnce("✅ Place phone on passport")
    }

    fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TECH_DISCOVERED != intent.action) return
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val currentMrzInfo = mrzInfo ?: return
        if (tag != null) {
            toastOnce("📳 Reading chip…")
            ReadPassportTask(tag, currentMrzInfo,
                onSuccess = { passportData ->
                    sharedViewModel.passportData.postValue(passportData)
                    activity?.runOnUiThread {
                        toastOnce("✅ Chip scanned!")
                        (activity as? MainActivity)?.navigateToConfirmationFragment("passport")
                    }
                },
                onFailure = {
                    activity?.runOnUiThread { toastOnce("❌ Failed. Hold phone steady.") }
                }
            ).execute()
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(requireActivity())
    }

    override fun onResume() {
        super.onResume()
        if (binding.nfcGroup.visibility == View.VISIBLE) enableNfcForegroundDispatch()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
        isCameraReady = false
        mlKitReady = false
        isScanning = false
    }

    @Suppress("DEPRECATION")
    private class ReadPassportTask(
        private val tag: Tag,
        private val mrzInfo: MRZInfo,
        private val onSuccess: (PassportData) -> Unit,
        private val onFailure: () -> Unit
    ) : android.os.AsyncTask<Void, Void, PassportData?>() {

        override fun doInBackground(vararg params: Void?): PassportData? {
            return try {
                val isoDep = IsoDep.get(tag)
                isoDep.timeout = 10000

                val cardService = CardService.getInstance(isoDep)
                cardService.open()

                val passportService = PassportService(
                    cardService,
                    PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                    PassportService.DEFAULT_MAX_BLOCKSIZE,
                    false, false
                )
                passportService.open()

                val bacKey = BACKey(
                    mrzInfo.documentNumber,
                    mrzInfo.dateOfBirth,
                    mrzInfo.dateOfExpiry
                )
                passportService.doBAC(bacKey)

                val dg1InputStream: InputStream =
                    passportService.getInputStream(PassportService.EF_DG1)
                val dg1File = DG1File(dg1InputStream)
                val mrzFromChip = dg1File.mrzInfo

                var bitmap: Bitmap? = null
                try {
                    val dg2InputStream: InputStream =
                        passportService.getInputStream(PassportService.EF_DG2)
                    val dg2File = DG2File(dg2InputStream)
                    val faceInfos = dg2File.faceInfos
                    if (faceInfos.isNotEmpty()) {
                        val imageInfos = faceInfos[0].faceImageInfos
                        if (imageInfos.isNotEmpty()) {
                            val imageBytes = imageInfos[0].imageInputStream.readBytes()
                            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        }
                    }
                } catch (_: Exception) { /* optional photo */ }

                passportService.close()
                cardService.close()

                return PassportData(
                    name = "${mrzFromChip.secondaryIdentifier} / ${mrzFromChip.primaryIdentifier}",
                    passportNumber = mrzFromChip.documentNumber,
                    dateOfBirth = mrzFromChip.dateOfBirth,
                    expiryDate = mrzFromChip.dateOfExpiry,
                    photo = bitmap
                )
            } catch (e: Exception) {
                Log.e(TAG, "NFC read error: ${e.message}", e)
                null
            }
        }

        override fun onPostExecute(result: PassportData?) {
            if (result != null) onSuccess(result) else onFailure()
        }
    }
}
