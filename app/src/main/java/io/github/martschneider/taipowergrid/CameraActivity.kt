package io.github.martschneider.taipowergrid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import io.github.martschneider.taipowergrid.databinding.ActivityCameraBinding
import io.github.martschneider.taipowergrid.model.TaipowerCoordinate
import io.github.martschneider.taipowergrid.utils.CoordinateParser
import io.github.martschneider.taipowergrid.utils.CoordinateConverter
import android.text.TextWatcher
import android.text.Editable
import android.view.Menu
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.view.MotionEvent

class CameraActivity : AppCompatActivity() {
    

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var lastCapturedImageFile: File? = null
    
    // ML Kit text recognizer
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    // Coordinate processing
    private val coordinateParser = CoordinateParser()
    private val coordinateConverter = CoordinateConverter()
    
    // Detection state
    private var isProcessing = false
    private var lastDetectedCoordinate: TaipowerCoordinate? = null
    private var isCameraActive = false
    
    companion object {
        private const val TAG = "CameraActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Activity created")
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Don't start camera automatically - wait for user interaction
        setupUI()
        setupToolbar()
        setupBackPressedCallback()
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Initialize pinch-to-zoom gesture detector
        scaleGestureDetector = ScaleGestureDetector(this, ScaleListener())
        
        // Pre-warm the text recognizer to avoid delay on first capture
        Log.d(TAG, "Pre-warming text recognizer")
        warmUpTextRecognizer()
    }
    
    
    private fun setupUI() {
        binding.apply {
            // Start camera button (initial state)
            btnStartCamera.setOnClickListener {
                Log.d(TAG, "Start camera requested")
                if (allPermissionsGranted()) {
                    startCamera()
                } else {
                    ActivityCompat.requestPermissions(
                        this@CameraActivity, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                    )
                }
            }
            
            // Capture button (camera active state)
            btnCapture.setOnClickListener {
                Log.d(TAG, "Capture requested")
                captureCurrentFrame()
            }
            
            // Close button (camera active state)
            btnCloseCameraMode.setOnClickListener {
                Log.d(TAG, "Close camera mode requested")
                showInitialState()
            }
            
            // Manual entry button
            btnManualEntry.setOnClickListener {
                Log.d(TAG, "Manual entry requested")
                showManualEntryDialog()
            }
            // Show on map button
            btnShowOnMap.setOnClickListener {
                Log.d(TAG, "Map requested")
                showOnMap()
            }
            
            
            // Close button
            btnCloseResults.setOnClickListener {
                Log.d(TAG, "Close result panel")
                hideResultPanel()
            }
            
            // Close readme button
            btnCloseReadme.setOnClickListener {
                Log.d(TAG, "Close readme overlay")
                hideReadmeOverlay()
            }
            
            // Close readme overlay when tapping background
            readmeOverlay.setOnClickListener {
                Log.d(TAG, "Close readme overlay via background tap")
                hideReadmeOverlay()
            }
            
            
            // Cancel progress button
            btnCancelProgress.setOnClickListener {
                Log.d(TAG, "Cancel progress requested")
                cancelCapture()
            }
            // Initially hide result panel and progress overlay
            resultPanel.visibility = View.GONE
            progressOverlay.visibility = View.GONE
            
            // Add validation to coordinate input field
            setupCoordinateValidation()
            
            // Add touch listener for zoom gestures
            viewFinder.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                true
            }
        }
    }
    
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }
    
    private fun setupCoordinateValidation() {
        binding.etCoordinate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                validateCoordinateInput(text)
            }
        })
        
        // Format input when field loses focus
        binding.etCoordinate.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val text = binding.etCoordinate.text?.toString() ?: ""
                val formatted = formatCoordinateInput(text)
                if (formatted != text) {
                    binding.etCoordinate.setText(formatted)
                }
            }
        }
    }
    
    private fun formatCoordinateInput(text: String): String {
        // Remove any existing spaces and convert to uppercase
        val cleanText = text.replace(" ", "").uppercase()
        
        // Check if we have at least 5 characters and they form a valid sector+zone combination
        if (cleanText.length >= 5) {
            val sectorLetter = cleanText[0]
            val zoneDigits = cleanText.substring(1, 5)
            
            // Check if first character is a letter and next 4 are digits
            if (sectorLetter.isLetter() && zoneDigits.all { it.isDigit() }) {
                // Valid sector+zone, insert space after first 5 characters
                val sector = cleanText.substring(0, 5)
                val rest = cleanText.substring(5)
                return if (rest.isNotEmpty()) "$sector $rest" else sector
            }
        }
        
        // Return cleaned text without space if pattern doesn't match
        return cleanText
    }
    
    private fun validateCoordinateInput(text: String) {
        val isValid = if (text.isEmpty()) {
            true // Empty is neutral state
        } else {
            val parsed = TaipowerCoordinate.parse(text)
            if (parsed != null) {
                true // Valid coordinate
            } else {
                // Check if this could be a partial input that's still being typed
                couldBeValidPartialInput(text)
            }
        }
        
        // Update the input field appearance based on validation
        binding.tilCoordinate.apply {
            if (isValid) {
                // Valid or empty - remove error styling
                error = null
                isErrorEnabled = false
                boxStrokeColor = ContextCompat.getColor(this@CameraActivity, android.R.color.white)
                boxStrokeWidth = 1 // Reset to normal width
            } else {
                // Invalid - show error styling with proper error message
                isErrorEnabled = true
                error = getString(R.string.invalid_coordinate_format)
                boxStrokeColor = ContextCompat.getColor(this@CameraActivity, android.R.color.holo_red_light)
                boxStrokeWidth = 3 // Make border thicker for better visibility
            }
        }
    }
    
    private fun couldBeValidPartialInput(text: String): Boolean {
        val cleanText = text.replace(" ", "").uppercase()
        
        // Check various partial input patterns that could be completed
        return when {
            cleanText.isEmpty() -> true
            cleanText.length == 1 -> cleanText[0].isLetter() // Just sector letter
            cleanText.length <= 5 -> {
                // Sector + partial zone (should be letter + digits)
                val sectorLetter = cleanText[0]
                val zoneDigits = cleanText.substring(1)
                sectorLetter.isLetter() && zoneDigits.all { it.isDigit() }
            }
            cleanText.length <= 7 -> {
                // Sector + zone + partial block (should be letter + 4 digits + letters)
                val sectorLetter = cleanText[0]
                val zoneDigits = cleanText.substring(1, 5)
                val blockLetters = cleanText.substring(5)
                sectorLetter.isLetter() && zoneDigits.all { it.isDigit() } && blockLetters.all { it.isLetter() }
            }
            cleanText.length <= 11 -> {
                // Full partial input - check basic pattern
                val sectorLetter = cleanText[0]
                val zoneDigits = cleanText.substring(1, 5)
                val blockLetters = cleanText.substring(5, 7)
                val precisionDigits = cleanText.substring(7)
                sectorLetter.isLetter() && zoneDigits.all { it.isDigit() } && 
                blockLetters.all { it.isLetter() } && precisionDigits.all { it.isDigit() }
            }
            else -> false // Too long
        }
    }
    
    private fun warmUpTextRecognizer() {
        // Create a dummy image to initialize the text recognizer
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val image = InputImage.fromBitmap(bitmap, 0)
        
        textRecognizer.process(image)
            .addOnSuccessListener {
                Log.d(TAG, "Text recognizer pre-warmed successfully")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Text recognizer pre-warming failed", e)
            }
    }
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Preview
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
            
            // Image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            
            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                
                // Switch to camera active state
                showCameraActiveState()
                
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun showCameraActiveState() {
        isCameraActive = true
        binding.apply {
            // Hide initial state
            initialStateLayout.visibility = View.GONE
            
            // Show camera preview and controls
            viewFinder.visibility = View.VISIBLE
            cameraOverlay.visibility = View.VISIBLE
            btnCapture.visibility = View.VISIBLE
            btnCloseCameraMode.visibility = View.VISIBLE
            
            // Hide toolbar for full-screen experience
            toolbar.visibility = View.GONE
        }
    }
    
    private fun showInitialState() {
        isCameraActive = false
        binding.apply {
            // Show initial state
            initialStateLayout.visibility = View.VISIBLE
            
            // Hide camera preview and controls
            viewFinder.visibility = View.GONE
            cameraOverlay.visibility = View.GONE
            btnCapture.visibility = View.GONE
            btnCloseCameraMode.visibility = View.GONE
            
            // Show toolbar
            toolbar.visibility = View.VISIBLE
        }
    }
    
    private fun captureCurrentFrame() {
        if (!isProcessing) {
            isProcessing = true
            Log.d(TAG, "Starting capture process")
            showProgressOverlay()
            
            // Capture a high-quality still image for analysis
            val imageCapture = imageCapture ?: run {
                Log.e(TAG, "ImageCapture is null")
                showError(getString(R.string.image_processing_error))
                return
            }
            
            // Create time stamped name and MediaStore entry
            val name = SimpleDateFormat(getString(R.string.logcat_time_format), Locale.US)
                .format(System.currentTimeMillis())
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(
                File(cacheDir, "$name${getString(R.string.file_extension_jpg)}")
            ).build()
            
            // Set up image capture listener
            imageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                        showError(getString(R.string.image_processing_error))
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "Image saved successfully")
                        // Process the captured image
                        val savedUri = output.savedUri
                        if (savedUri != null) {
                            Log.d(TAG, "Processing saved image: $savedUri")
                            try {
                                val imageFile = File(savedUri.path!!)
                                lastCapturedImageFile = imageFile
                                processImageFile(imageFile)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing saved image", e)
                                showError(getString(R.string.image_processing_error))
                            }
                        } else {
                            Log.e(TAG, "Saved URI is null")
                            showError(getString(R.string.image_processing_error))
                        }
                    }
                }
            )
        }
    }
    
    private fun processImageFile(imageFile: File) {
        Log.d(TAG, "Processing image file: ${imageFile.absolutePath}")
        try {
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.e(TAG, "Image file is invalid or empty")
                showError(getString(R.string.image_processing_error))
                return
            }
            
            val image = InputImage.fromFilePath(this, android.net.Uri.fromFile(imageFile))
            Log.d(TAG, "Starting text recognition on image: ${imageFile.length()} bytes, ${image.width}x${image.height}")
            
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    Log.i(TAG, "OCR result: ${visionText.text.replace("\n", " ")}")
                    // Note: Don't delete image file immediately - keep for potential bug reports
                    processDetectedText(visionText.text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    runOnUiThread {
                        showError(getString(R.string.no_coordinates_detected))
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image file", e)
            runOnUiThread {
                showError(getString(R.string.image_processing_error))
            }
        }
    }
    
    private fun showProgressOverlay() {
        binding.apply {
            progressOverlay.visibility = View.VISIBLE
            resultPanel.visibility = View.GONE
            if (isCameraActive) {
                btnCapture.visibility = View.GONE
                btnCloseCameraMode.visibility = View.GONE
            } else {
                initialStateLayout.visibility = View.GONE
            }
            progressText.text = getString(R.string.finding_coordinates)
        }
        freezeCamera()
    }
    
    private fun hideProgressOverlay() {
        binding.apply {
            progressOverlay.visibility = View.GONE
            if (isCameraActive) {
                btnCapture.visibility = View.VISIBLE
                btnCloseCameraMode.visibility = View.VISIBLE
            } else {
                initialStateLayout.visibility = View.VISIBLE
            }
        }
        // Only unfreeze camera if result panel is not visible
        if (binding.resultPanel.visibility != View.VISIBLE) {
            unfreezeCamera()
        }
    }
    
    private fun showError(message: String) {
        Log.d(TAG, "Error toast displayed: " + message)
        hideProgressOverlay()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        isProcessing = false
    }
    
    private fun processDetectedText(text: String) {
        runOnUiThread {
            try {
                // Parse detected text for Taipower coordinates
                val coordinates = coordinateParser.parseText(text)
                
                if (coordinates.isNotEmpty()) {
                    val bestCoordinate = coordinates.first() // Take the first/best match
                    Log.i(TAG, "Parsed coordinates: ${bestCoordinate.rawCoordinate}")
                    lastDetectedCoordinate = bestCoordinate
                    
                    hideProgressOverlay()
                    showDetectedCoordinate(bestCoordinate)
                } else {
                    // Show detected text for user to edit
                    Log.d(TAG, "No coordinates found, showing detected text for editing")
                    hideProgressOverlay()
                    val cleanedText = text.trim().replace(Regex(getString(R.string.regex_clean_pattern)), "")
                    showDetectedTextForEditing(cleanedText.take(10))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing coordinates", e)
                // Show detected text for user to edit even on parsing error
                hideProgressOverlay()
                val cleanedText = text.trim().replace(Regex(getString(R.string.regex_clean_pattern)), "")
                showDetectedTextForEditing(cleanedText.take(10))
            }
        }
    }
    
    private fun showOnMap() {
        val editedCoordinate = binding.etCoordinate.text.toString().trim()
        
        if (editedCoordinate.isEmpty()) {
            showError(getString(R.string.empty_coordinates_error))
            return
        }
        
        // Parse and convert the edited coordinate
        val coordinate = TaipowerCoordinate.parse(editedCoordinate)
        if (coordinate == null) {
            showError(getString(R.string.invalid_coordinate_format))
            return
        }
        
        try {
            val gpsCoordinate = coordinateConverter.convertToWGS84(coordinate)
            
            val lat = gpsCoordinate.latitude
            val lon = gpsCoordinate.longitude
            val label = getString(R.string.taipower_label_format, editedCoordinate)
            
            // Use geo intent to let user choose which app to use
            try {
                val geoUri = Uri.parse(getString(R.string.geo_uri_with_label_format, lat, lon, label))
                val intent = Intent(Intent.ACTION_VIEW, geoUri)
                startActivity(intent)
            } catch (e: Exception) {
                showError(getString(R.string.no_maps_app))
            }
                
        } catch (e: Exception) {
            Log.e(TAG, "Error converting coordinate", e)
            showError(getString(R.string.invalid_coordinate_format))
        }
    }
    
    private fun hideResultPanel() {
        binding.apply {
            resultPanel.visibility = View.GONE
            progressOverlay.visibility = View.GONE
            if (isCameraActive) {
                btnCapture.visibility = View.VISIBLE
                btnCloseCameraMode.visibility = View.VISIBLE
            } else {
                initialStateLayout.visibility = View.VISIBLE
            }
            etCoordinate.text?.clear()
        }
        lastDetectedCoordinate = null
        isProcessing = false
        unfreezeCamera()
    }
    
    private fun showManualEntryDialog() {
        // Show the result panel with empty text for manual entry
        binding.apply {
            progressOverlay.visibility = View.GONE
            resultPanel.visibility = View.VISIBLE
            if (isCameraActive) {
                btnCapture.visibility = View.GONE
                btnCloseCameraMode.visibility = View.GONE
            } else {
                initialStateLayout.visibility = View.GONE
            }
            etCoordinate.text?.clear()
            isProcessing = false
        }
        freezeCamera()
        
        // Focus on the input field
        binding.etCoordinate.requestFocus()
    }
    
    private fun cancelCapture() {
        Log.d(TAG, "Cancelling capture")
        isProcessing = false
        hideProgressOverlay()
        unfreezeCamera()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_readme -> {
                openReadme()
                true
            }
            R.id.action_file_bug -> {
                fileBug()
                true
            }
            R.id.action_exit -> {
                exitApp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    
    private fun exitApp() {
        Log.d(TAG, "Exiting app")
        finish()
    }
    
    private fun openReadme() {
        Log.d(TAG, "Open readme overlay")
        showReadmeOverlay()
    }
    
    private fun showReadmeOverlay() {
        binding.apply {
            readmeOverlay.visibility = View.VISIBLE
            if (isCameraActive) {
                btnCapture.visibility = View.GONE
                btnCloseCameraMode.visibility = View.GONE
            } else {
                initialStateLayout.visibility = View.GONE
            }
            
            // Set HTML content for tutorial introduction with clickable links
            val htmlContent = getString(R.string.tutorial_introduction)
            val spannedText = HtmlCompat.fromHtml(htmlContent, HtmlCompat.FROM_HTML_MODE_LEGACY)
            tvTutorialIntroduction.text = spannedText
            tvTutorialIntroduction.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        freezeCamera()
    }
    
    private fun hideReadmeOverlay() {
        binding.apply {
            readmeOverlay.visibility = View.GONE
            if (isCameraActive) {
                btnCapture.visibility = View.VISIBLE
                btnCloseCameraMode.visibility = View.VISIBLE
            } else {
                initialStateLayout.visibility = View.VISIBLE
            }
        }
        // Only unfreeze camera if no other overlays are visible
        if (binding.resultPanel.visibility != View.VISIBLE && binding.progressOverlay.visibility != View.VISIBLE) {
            unfreezeCamera()
        }
    }
    
    
    
    private fun getRecentLogcatMessages(): String {
        return try {
            Log.d(TAG, "Collecting last 10 logcat messages")
            
            val process = ProcessBuilder()
                .command("logcat", "-d", "-v", "time", "CameraActivity:I", "*:S")
                .redirectErrorStream(true)
                .start()
            
            val lines = mutableListOf<String>()
            
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { lines.add(it) }
                }
            }
            
            process.waitFor()
            
            val lastTenLines = lines.takeLast(10)
            
            if (lastTenLines.isEmpty()) {
                getString(R.string.no_log_messages_found)
            } else {
                lastTenLines.joinToString("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect logcat messages", e)
            getString(R.string.failed_to_collect_logcat, e.message ?: "")
        }
    }
    
    private fun fileBug() {
        val subject = getString(R.string.bug_report_subject, getString(R.string.app_name))
        val body = getRecentLogcatMessages()
        
        val emailIntent = Intent(Intent.ACTION_SEND).apply {
            type = getString(R.string.rfc822_type)
            setPackage(getString(R.string.gmail_package)) // Target Gmail specifically
            putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.support_email)))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            
            // Attach the last captured image if available
            lastCapturedImageFile?.let { imageFile ->
                if (imageFile.exists()) {
                    try {
                        Log.d(TAG, "Attempting to create URI for file: ${imageFile.absolutePath}")
                        Log.d(TAG, "FileProvider authority: ${packageName}.fileprovider")
                        Log.d(TAG, "Cache dir: ${cacheDir.absolutePath}")
                        Log.d(TAG, "External cache dir: ${externalCacheDir?.absolutePath}")
                        
                        val imageUri = androidx.core.content.FileProvider.getUriForFile(
                            this@CameraActivity,
                            "${packageName}${getString(R.string.fileprovider_suffix)}",
                            imageFile
                        )
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        Log.d(TAG, "Successfully created image URI: $imageUri")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create file URI for attachment", e)
                    }
                } else {
                    Log.w(TAG, "Image file does not exist: ${imageFile.absolutePath}")
                }
            } ?: Log.w(TAG, "No captured image available for attachment")
        }
        
        try {
            // Try Gmail first
            startActivity(emailIntent)
        } catch (e: Exception) {
            // Fallback to email chooser if Gmail not available
            Log.w(TAG, "Gmail not available, falling back to email chooser")
            val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                type = getString(R.string.rfc822_type)
                putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.support_email)))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                
                lastCapturedImageFile?.let { imageFile ->
                    if (imageFile.exists()) {
                        try {
                            val imageUri = androidx.core.content.FileProvider.getUriForFile(
                                this@CameraActivity,
                                "${packageName}${getString(R.string.fileprovider_suffix)}",
                                imageFile
                            )
                            putExtra(Intent.EXTRA_STREAM, imageUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create file URI for fallback attachment", e)
                        }
                    }
                }
            }
            
            try {
                startActivity(Intent.createChooser(fallbackIntent, getString(R.string.send_bug_report_chooser)))
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.no_email_app_available), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showError(getString(R.string.camera_permission_denied))
                finish()
            }
        }
    }
    
    private fun setupBackPressedCallback() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.readmeOverlay.visibility == View.VISIBLE -> {
                        Log.d(TAG, "Back pressed - closing readme overlay")
                        hideReadmeOverlay()
                    }
                    binding.resultPanel.visibility == View.VISIBLE -> {
                        Log.d(TAG, "Back pressed - closing result panel")
                        hideResultPanel()
                    }
                    isCameraActive -> {
                        // Return to initial state instead of exiting
                        Log.d(TAG, "Back pressed - returning to initial state")
                        showInitialState()
                    }
                    else -> {
                        finish()
                    }
                }
            }
        })
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Activity destroyed")
        cameraExecutor.shutdown()
        textRecognizer.close()
        
        // Clean up old image files (keep only the last one for bug reports)
        cleanupOldImages()
    }
    
    private fun cleanupOldImages() {
        try {
            val imageDir = File(externalCacheDir, getString(R.string.images_folder))
            if (imageDir.exists()) {
                val imageFiles = imageDir.listFiles { file -> 
                    file.name.endsWith(getString(R.string.file_extension_jpg)) && file != lastCapturedImageFile
                }
                imageFiles?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Cleaned up old image: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old images", e)
        }
    }
    
    private fun freezeCamera() {
        Log.d(TAG, "Freezing camera preview")
        preview?.setSurfaceProvider(null)
    }
    
    private fun unfreezeCamera() {
        Log.d(TAG, "Unfreezing camera preview")
        preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }
    
    private fun showDetectedCoordinate(coordinate: TaipowerCoordinate) {
        binding.apply {
            // Hide progress overlay and show result panel
            progressOverlay.visibility = View.GONE
            resultPanel.visibility = View.VISIBLE
            if (isCameraActive) {
                btnCapture.visibility = View.GONE
                btnCloseCameraMode.visibility = View.GONE
            } else {
                initialStateLayout.visibility = View.GONE
            }
            
            // Display coordinate in editable field
            etCoordinate.setText(coordinate.rawCoordinate)
            // Focus on the input field to ensure it's active
            etCoordinate.requestFocus()
            // Trigger validation to show immediate visual feedback
            validateCoordinateInput(coordinate.rawCoordinate)
            isProcessing = false
        }
        freezeCamera()
    }
    
    private fun showDetectedTextForEditing(text: String) {
        binding.apply {
            // Hide progress overlay and show result panel
            progressOverlay.visibility = View.GONE
            resultPanel.visibility = View.VISIBLE
            if (isCameraActive) {
                btnCapture.visibility = View.GONE
                btnCloseCameraMode.visibility = View.GONE
            } else {
                initialStateLayout.visibility = View.GONE
            }
            
            // Display detected text for user to edit
            val cleanedText = text.replace("\n", getString(R.string.newline_replacement)).trim()
            etCoordinate.setText(cleanedText)
            // Focus on the input field to ensure it's active
            etCoordinate.requestFocus()
            // Trigger validation to show immediate visual feedback
            validateCoordinateInput(cleanedText)
            isProcessing = false
        }
        freezeCamera()
    }
    
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
            val delta = detector.scaleFactor
            
            val newZoomRatio = currentZoomRatio * delta
            
            // Clamp zoom ratio to camera's supported range
            camera?.let { cam ->
                val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                val clampedZoom = newZoomRatio.coerceIn(minZoom, maxZoom)
                
                cam.cameraControl.setZoomRatio(clampedZoom)
                Log.d(TAG, "Zoom ratio: $clampedZoom (min: $minZoom, max: $maxZoom)")
            }
            
            return true
        }
    }
    
}