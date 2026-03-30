package com.example.translationapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translationapplication.ui.theme.BackgroundWhite
import com.example.translationapplication.ui.theme.PrimaryBlue
import com.example.translationapplication.ui.theme.TranslationApplicationTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var imageReader: ImageReader

    private lateinit var textRecognizer: TextRecognizer

    private var lastSentTime = 0L
    private val captureIntervalMs = 500L
    private var isProcessingFrame = false

    private val startCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                startForegroundService(serviceIntent)

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    mediaProjection = mediaProjectionManager.getMediaProjection(
                        result.resultCode,
                        result.data!!
                    )
                    startCapture()
                }, 500)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            TranslationApplicationTheme {
                AppUI {
                    startScreenCapture()
                }
            }
        }
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startCaptureLauncher.launch(intent)
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                imageReader.close()
            }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        val handlerThread = HandlerThread("ImageReaderThread")
        handlerThread.start()
        val backgroundHandler = Handler(handlerThread.looper)

        imageReader.setOnImageAvailableListener({
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()

            // Throttle captures and prevent concurrent processing
            if (now - lastSentTime < captureIntervalMs || isProcessingFrame) {
                image.close()
                return@setOnImageAvailableListener
            }

            isProcessingFrame = true
            lastSentTime = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels

            val bitmap = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / pixelStride, metrics.heightPixels, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            processImageWithMLKit(bitmap)

        }, backgroundHandler)
    }

    private fun processImageWithMLKit(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detectedTexts = mutableListOf<String>()
                for (block in visionText.textBlocks) {
                    detectedTexts.add(block.text)
                }

                // Print the detected text to Logcat
                if (detectedTexts.isNotEmpty()) {
                    println("Local OCR Found: $detectedTexts")
                }
                isProcessingFrame = false
            }
            .addOnFailureListener { e ->
                println("Local OCR Failed: ${e.message}")
                isProcessingFrame = false
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUI(onStart: () -> Unit) {
    Scaffold(
        containerColor = BackgroundWhite,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Translation App", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = PrimaryBlue)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = true,
                    onClick = { /* TODO */ },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = PrimaryBlue, selectedTextColor = PrimaryBlue)
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.TextFields, contentDescription = "Text") },
                    label = { Text("Text") },
                    selected = false,
                    onClick = { /* TODO */ }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Image, contentDescription = "Docs/Image") },
                    label = { Text("Docs/Image") },
                    selected = false,
                    onClick = { /* TODO */ }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Start Translate",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue
            )
            Spacer(modifier = Modifier.height(24.dp))
            FloatingActionButton(
                onClick = onStart,
                containerColor = PrimaryBlue,
                modifier = Modifier.size(100.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Start Recording",
                    tint = Color.White,
                    modifier = Modifier.size(60.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewUI() {
    TranslationApplicationTheme {
        AppUI {}
    }
}