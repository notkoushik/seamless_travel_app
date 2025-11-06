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
import com.google.mlkit.vision.text.Text // This import is still needed
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

@kotlin.OptIn(ExperimentalGetImage::class) // ✅ Opt-in to ImageProxy.getImage()
class PassportFragment : Fragment() {

    private var _binding: FragmentPassportBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var nfcAdapter: NfcAdapter? = null
    private var mrzInfo: MRZInfo? = null

    private var isScanning = false
    private var mlKitReady = false

    // This flag solves the race condition
    private var isCameraReady = false

    // Reuse recognizer
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Stability gate (1 = accept first good hit)
    private var lastBestL2: String? = null
    private var stableHits: Int = 0
    private val STABLE_REQUIRED = 1

    // Throttle toasts (avoid "already queued" spam)
    private var lastToastAtMs = 0L
    private fun toastOnce(msg: String, minIntervalMs: Long = 2500) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastAtMs >= minIntervalMs) {
            // Check context to avoid crashes if fragment is detached
            context?.let {
                Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
            }
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
        Log.d(TAG, "Fragment created")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: AndroidBundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated - Initializing")

        cameraExecutor = Executors.newSingleThreadExecutor()
        nfcAdapter = NfcAdapter.getDefaultAdapter(requireContext())

        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available")
            toastOnce("ℹ️ NFC not available on this device")
        } else {
            toastOnce(if (nfcAdapter!!.isEnabled) "NFC: ✅ Enabled" else "NFC: ⚠️ Disabled")
            Log.d(TAG, "NFC available, enabled=${nfcAdapter!!.isEnabled}")
        }

        // --- These two are now in a race ---
        checkMLKitModels()

        // We are making the scan automatic, so the button is no longer needed.
        binding.scanMrzButton.visibility = View.GONE

        if (allPermissionsGranted()) startCamera()
        else permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    private fun checkMLKitModels() {
        Log.d(TAG, "Checking ML Kit models…")
        toastOnce("⏳ Checking ML Kit models…")

        val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val testImage = InputImage.fromBitmap(testBitmap, 0)
        recognizer.process(testImage)
            .addOnSuccessListener {
                mlKitReady = true
                Log.d(TAG, "✅ ML Kit models ready and verified")
                toastOnce("✅ ML Kit ready")
                // --- Call the ready check ---
                attemptToStartScanning()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit models not ready: ${e.message}", e)
                toastOnce("⏳ ML Kit downloading models…")
                // Use postDelayed on the view, checking if it's still valid
                _binding?.root?.postDelayed({ checkMLKitModels() }, 5000)
            }
    }

    private fun startCamera() {
        Log.d(TAG, "Starting camera…")
        toastOnce("📷 Initializing camera…")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Portable 4:3 strategy (no convenience constant)
                val aspect = AspectRatioStrategy(
                    /* preferredAspectRatio = */ AspectRatio.RATIO_4_3,
                    /* fallbackRule = */ AspectRatioStrategy.FALLBACK_RULE_AUTO
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

                Log.d(TAG, "Binding camera to lifecycle…")
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )

                toastOnce("✅ Camera ready")
                Log.d(TAG, "✅ Camera bound successfully (4:3 strategy)")

                // --- Set flag and call ready check ---
                isCameraReady = true
                attemptToStartScanning()

            } catch (e: Exception) {
                toastOnce("❌ Camera failed: ${e.message}")
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /**
     * This function is called by *both* the camera and ML Kit when they
     * become ready. It will only start the scan when *both* are ready
     * and a scan isn't already running.
     */
    private fun attemptToStartScanning() {
        // Check if both are ready AND we are not already scanning
        if (mlKitReady && isCameraReady && !isScanning) {
            Log.d(TAG, "✅ Both camera and ML Kit are ready. Starting scan...")
            activity?.runOnUiThread {
                binding.resultText.text = "📸 Scanning MRZ…"
            }
            isScanning = true
            lastBestL2 = null
            stableHits = 0
            analyzeImage() // Start the analyzer!
        } else {
            Log.d(TAG, "Not starting scan. mlKitReady=$mlKitReady, isCameraReady=$isCameraReady, isScanning=$isScanning")
        }
    }

    private fun analyzeImage() {
        Log.d(TAG, "Starting image analysis…")

        val analysis = imageAnalysis
        if (analysis == null) {
            toastOnce("❌ Camera not ready!")
            Log.e(TAG, "Image analysis is null")
            isScanning = false
            return
        }

        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanning) {
                imageProxy.close()
                return@setAnalyzer
            }

            try {
                val mediaImage = imageProxy.image   // Experimental API — class is opted-in above
                if (mediaImage != null) {
                    val image = InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees
                    )
                    recognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            // Use new structured-line parser
                            if (visionText.textBlocks.isNotEmpty()) {

                                // Call our new (and only) parser
                                val mrz = findMrzFromStructuredLines(visionText)

                                if (mrz != null) {
                                    val (line1, line2) = mrz

                                    if (lastBestL2 == null || lastBestL2 != line2) {
                                        lastBestL2 = line2
                                        stableHits = 1
                                    } else {
                                        stableHits += 1
                                    }

                                    activity?.runOnUiThread {
                                        binding.resultText.text =
                                            "🔎 MRZ candidate ($stableHits/$STABLE_REQUIRED)"
                                    }

                                    if (stableHits >= STABLE_REQUIRED) {
                                        Log.i(TAG, "✅ MRZ STABLE & FOUND")
                                        isScanning = false
                                        imageAnalysis?.clearAnalyzer()
                                        processMRZResult(line1, line2)
                                    }
                                } else {
                                    activity?.runOnUiThread {
                                        binding.resultText.text =
                                            "⚠️ No MRZ pattern found\nTip: fill the screen with the two MRZ lines at the bottom of the passport"
                                    }
                                }
                            } else {
                                activity?.runOnUiThread {
                                    binding.resultText.text =
                                        "⚠️ No text detected\nAdjust angle/lighting"
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Text recognition error: ${e.message}", e)
                            activity?.runOnUiThread {
                                toastOnce("❌ ${e.message}")
                                binding.resultText.text = "❌ ${e.message}"
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    Log.e(TAG, "Media image is null")
                    activity?.runOnUiThread { binding.resultText.text = "❌ Camera frame error" }
                    imageProxy.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}", e)
                imageProxy.close()
            }
        }
    }

    // ===========================
    // ROBUST MRZ PARSER
    // ===========================

    /**
     * This is now the ONLY parser. It scans all structured text blocks,
     * finds all valid Line 1s and Line 2s, and returns the first match.
     */
    private fun findMrzFromStructuredLines(vText: Text): Pair<String, String>? {
        val potentialLine1s = mutableListOf<String>()
        val potentialLine2s = mutableListOf<String>()

        // 1. Sanitize and sort all lines from all blocks
        val candidates = mutableListOf<String>()
        for (block in vText.textBlocks) {
            for (line in block.lines) {
                candidates += sanitizeMrzLine(line.text)
            }
        }

        // 2. Validate all candidates and sort them into L1/L2 buckets
        for (line in candidates) {
            if (line.isEmpty()) continue

            // Check for Line 1
            if (line.startsWith("P<")) {
                potentialLine1s += line.padEnd(44, '<').take(44)
            }

            // Check for Line 2
            val validLine2 = tryFixAndValidateLine2(line)
            if (validLine2 != null) {
                potentialLine2s += validLine2
            }
        }

        Log.d(TAG, "MRZ Candidates - Line 1: ${potentialLine1s.size}, Line 2: ${potentialLine2s.size}")

        // 3. If we have at least one of each, we have a match.
        if (potentialLine1s.isNotEmpty() && potentialLine2s.isNotEmpty()) {
            // Return the first valid pair.
            return potentialLine1s.first() to potentialLine2s.first()
        }

        // 4. No match found
        return null
    }

    // ===========================
    // PARSER HELPER FUNCTIONS
    // ===========================

    // More aggressive normalization for MRZ glyphs and noise
    private fun sanitizeMrzLine(src: String): String {
        var t = src.uppercase()

        // Map lookalike symbols to MRZ filler '<'
        t = t.replace("«", "<<")
        t = t.replace("»", "<")
        t = t.replace("‹", "<")
        t = t.replace("›", "<")
        t = t.replace("[\\[\\{\\(]".toRegex(), "<")
        t = t.replace("[\\]\\}\\)]".toRegex(), "<")
        t = t.replace("[=]+".toRegex(), "<")    // OCR sometimes sees <<= as ===

        // Drop spaces and non-MRZ chars (after the mappings above)
        t = t.replace("\\s+".toRegex(), "")
        t = t.replace("[^A-Z0-9<]".toRegex(), "")

        // Collapse runs like "<<<<<" (harmless) and trim
        return t
    }

    // --- THIS IS THE MODIFIED, SMARTER FUNCTION ---
    // Try to coerce a line2 candidate to a valid TD3 line2 and verify check digits.
    // Returns a 44-char fixed line2 or null.
    private fun tryFixAndValidateLine2(raw: String): String? {
        if (raw.length < 30) return null
        // Pad/truncate to 44 so indexes exist
        var f = raw.padEnd(44, '<').take(44)

        // --- Re-introducing "smart" fixers ---
        fun toDigit(c: Char): Char = when (c) {
            'O','D','Q' -> '0'
            'I','L'      -> '1'
            'Z'          -> '2'
            'S'          -> '5'
            'G'          -> '6'
            'B'          -> '8'
            else         -> c
        }

        fun fixRange(s: String, a: Int, b: Int): String {
            val ch = s.toCharArray()
            for (i in a until b) ch[i] = toDigit(ch[i])
            return String(ch)
        }

        fun fixAt(s: String, idx: Int): String {
            val ch = s.toCharArray(); ch[idx] = toDigit(ch[idx]); return String(ch)
        }
        // --- End of smart fixers ---


        // TD3 layout checks; coerce numeric fields
        // We apply fixes ONLY to fields that MUST be numeric.
        f = fixRange(f, 0, 9)       // Document number
        f = fixAt(f, 9)             // doc# check digit
        f = fixRange(f, 13, 19)     // DOB YYMMDD
        f = fixAt(f, 19)            // DOB check digit
        f = fixRange(f, 21, 27)     // EXP YYMMDD
        f = fixAt(f, 27)            // EXP check digit
        f = fixRange(f, 28, 42)     // Optional data (often numeric)
        f = fixAt(f, 42)            // Optional data check digit
        f = fixAt(f, 43)            // composite check digit

        // Quick structure plausibility
        val nat = f.substring(10, 13)
        val dob = f.substring(13, 19)
        val sex = f[20]
        val exp = f.substring(21, 27)
        if (!nat.matches(Regex("^[A-Z<]{3}$"))) return null
        if (!dob.matches(Regex("^\\d{6}$"))) return null
        if (!exp.matches(Regex("^\\d{6}$"))) return null
        if (sex !in charArrayOf('M','F','<')) return null

        // Add bounds check for safety
        if (f.length <= 43 || !f[9].isDigit() || !f[19].isDigit() || !f[27].isDigit() || !f[43].isDigit()) return null

        // Check-digits (ICAO 9303)
        val doc = f.substring(0, 9)
        val docCD = f[9]
        val dobCD = f[19]
        val expCD = f[27]
        val opt   = f.substring(28, 43) // This includes the optional CD at index 42
        val cmpCD = f[43]

        // Now we check the digits on the CLEANED string
        if (mrzCheck(doc) != docCD) return null
        if (mrzCheck(dob) != dobCD) return null
        if (mrzCheck(exp) != expCD) return null

        // The optional field check digit is part of the final composite check.
        // We don't need to check it individually if it's all <'s.

        val composite = doc + docCD + dob + dobCD + exp + expCD + opt

        if (mrzCheck(composite) != cmpCD) return null

        // If we got here, it's a valid Line 2!
        return f
    }

    // Char arithmetic (friendly to older Kotlin toolchains)
    private fun mrzCheck(field: String): Char {
        val w = intArrayOf(7, 3, 1)
        var s = 0
        for (i in field.indices) {
            val ch = field[i]
            val v = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'A'..'Z' -> ch - 'A' + 10
                '<' -> 0
                else -> 0
            }
            s += v * w[i % 3]
        }
        return '0' + (s % 10)
    }

    // ===========================
    // END OF PARSER
    // ===========================


    private fun processMRZResult(line1: String, line2: String) {
        Log.d(TAG, "Processing MRZ result…")
        try {
            val mrzString = "$line1\n$line2"
            val info = MRZInfo(mrzString)
            mrzInfo = info

            val name = "${info.secondaryIdentifier} ${info.primaryIdentifier}"
            @Suppress("UNUSED_VARIABLE") val passport = info.documentNumber

            activity?.runOnUiThread {
                binding.resultText.text = "✅ MRZ Scanned!\n$name"
            }

            // NFC fallback: proceed with MRZ-only if NFC absent/disabled
            if (nfcAdapter?.isEnabled != true) {
                Log.i(TAG, "NFC not available/enabled → continuing without chip")
                toastOnce("ℹ️ NFC not available — continuing without chip")
                proceedWithoutNfc(info)
                return
            }

            toastOnce("✅ MRZ scanned — place phone on passport")
            switchToNfcMode()
        } catch (e: Exception) {
            Log.e(TAG, "MRZ parsing error: ${e.message}", e)
            activity?.runOnUiThread {
                toastOnce("❌ MRZ Parse Error: ${e.message}")
                binding.resultText.text = "❌ Parse Error. Try again."
            }
            isScanning = false
        }
    }

    private fun proceedWithoutNfc(info: MRZInfo) {
        val pd = PassportData(
            name = "${info.secondaryIdentifier} / ${info.primaryIdentifier}",
            passportNumber = info.documentNumber,
            dateOfBirth = info.dateOfBirth,
            expiryDate = info.dateOfExpiry,
            photo = null // no chip photo
        )
        sharedViewModel.passportData.postValue(pd)
        activity?.runOnUiThread {
            (activity as? MainActivity)?.navigateToConfirmationFragment("passport")
        }
    }

    private fun switchToNfcMode() {
        Log.d(TAG, "Switching to NFC mode")
        binding.cameraGroup.visibility = View.GONE
        binding.nfcGroup.visibility = View.VISIBLE
        enableNfcForegroundDispatch()
    }

    private fun enableNfcForegroundDispatch() {
        Log.d(TAG, "Enabling NFC foreground dispatch")
        val adapter = nfcAdapter ?: run {
            toastOnce("❌ NFC not available")
            return
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
        // --- Make sure to reset flags when view is destroyed ---
        isCameraReady = false
        mlKitReady = false
        isScanning = false
    }

    // ------------------------
    // NFC read (kept as AsyncTask for now)
    // ------------------------
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
                            val imageBytes = ByteArray(imageInfos[0].imageLength)
                            imageInfos[0].imageInputStream.read(imageBytes)
                            bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        }
                    }
                } catch (_: Exception) { /* photo optional */ }

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