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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translationapplication.ui.theme.BackgroundWhite
import com.example.translationapplication.ui.theme.PrimaryBlue
import com.example.translationapplication.ui.theme.TranslationApplicationTheme
import java.io.ByteArrayOutputStream

data class LanguageOption(val code: String, val label: String)

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var imageReader: ImageReader
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var viewOverlay: TranslationOverlay

    @Volatile private var sourceLang: String = "ja"
    @Volatile private var targetLang: String = "en"

    private var lastSentTime = 0L
    private var lastFramePixels: IntArray? = null
    private val captureIntervalMs = 1500L
    private var isTranslationPaused = false
    private var floatingWidget: FloatingControlWidget? = null

    private fun hasScreenChanged(bitmap: Bitmap): Boolean {
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val currentPixels = IntArray(32 * 32)
        smallBitmap.getPixels(currentPixels, 0, 32, 0, 0, 32, 32)

        if (lastFramePixels == null) {
            lastFramePixels = currentPixels
            return true
        }

        var significantPixelChanges = 0

        for (i in currentPixels.indices) {
            val p1 = currentPixels[i]
            val p2 = lastFramePixels!![i]

            val rDiff = kotlin.math.abs(android.graphics.Color.red(p1) - android.graphics.Color.red(p2))
            val gDiff = kotlin.math.abs(android.graphics.Color.green(p1) - android.graphics.Color.green(p2))
            val bDiff = kotlin.math.abs(android.graphics.Color.blue(p1) - android.graphics.Color.blue(p2))

            if ((rDiff + gDiff + bDiff) > 60) {
                significantPixelChanges++
            }
        }

        lastFramePixels = currentPixels
        return significantPixelChanges > (1024 * 0.04)
    }

    private val startCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                startForegroundService(serviceIntent)

                Handler(Looper.getMainLooper()).postDelayed({
                    mediaProjection = mediaProjectionManager.getMediaProjection(
                        result.resultCode,
                        result.data!!
                    )
                    startCapture()
                    Toast.makeText(this@MainActivity, "Translation started", Toast.LENGTH_SHORT).show()

                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)

                    if (floatingWidget == null && Settings.canDrawOverlays(this@MainActivity)) {
                        floatingWidget = FloatingControlWidget(this@MainActivity) { isPlaying ->
                            isTranslationPaused = !isPlaying
                            val status = if (isPlaying) "Translation Resumed" else "Translation Paused"
                            Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show()
                        }
                    }
                }, 500)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun startScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Please grant overlay permission and try again", Toast.LENGTH_LONG).show()
            return
        }

        if (viewOverlay.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(viewOverlay, params)
        }

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

            // Send 1 frame every 1.5 s and app not paused
            if (now - lastSentTime < captureIntervalMs || isTranslationPaused) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastSentTime = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

            if (!hasScreenChanged(croppedBitmap)) {
                return@setOnImageAvailableListener
            }

            runOnUiThread { viewOverlay.clearBoxes() }

            val stream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val byteArray = stream.toByteArray()

            webSocketManager.sendImage(byteArray)
        }, backgroundHandler)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewOverlay = TranslationOverlay(this)

        webSocketManager = WebSocketManager { result ->
            Log.d("TRANSLATION_RAW", result)
            runOnUiThread {
                viewOverlay.updateTranslation(result)
            }
        }
        webSocketManager.connect()
        webSocketManager.updateLanguagePair(sourceLang, targetLang)

        setContent {
            TranslationApplicationTheme {
                var selectedSourceLang by remember { mutableStateOf(sourceLang) }
                var selectedTargetLang by remember { mutableStateOf(targetLang) }
                var activeScreen by remember { mutableStateOf("home") }

                if (activeScreen == "camera") {
                    CameraTranslationScreen (
                        webSocketManager = webSocketManager,
                        hasScreenChanged = ::hasScreenChanged,
                        onNavigateBack = { activeScreen = "home" }
                    )
                } else {
                    AppUI(
                        selectedSourceLang = selectedSourceLang,
                        selectedTargetLang = selectedTargetLang,
                        onLanguageChange = { source, target ->
                            selectedSourceLang = source
                            selectedTargetLang = target
                            webSocketManager.updateLanguagePair(source, target)
                        },
                        onStartScreen = {
                            startScreenCapture()
                        },
                        onStartCamera = {
                            activeScreen = "camera"
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUI(
    selectedSourceLang: String,
    selectedTargetLang: String,
    onLanguageChange: (String, String) -> Unit,
    onStartScreen: () -> Unit,
    onStartCamera: () -> Unit
) {
    val languageOptions = remember {
        listOf(
            LanguageOption("ja", "Japanese"),
            LanguageOption("zh", "Chinese"),
            LanguageOption("en", "English"),
            LanguageOption("id", "Bahasa Indonesia")
        )
    }

    val sourceLabel = languageOptions.first { it.code == selectedSourceLang }.label
    val targetLabel = languageOptions.first { it.code == selectedTargetLang }.label

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Translation App", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Screen") },
                    selected = true,
                    onClick = { },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PrimaryBlue,
                        selectedTextColor = PrimaryBlue,
                        indicatorColor = Color(0xFFE3F2FD)
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = "Camera") },
                    label = { Text("Camera") },
                    selected = false,
                    onClick = onStartCamera
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Screen Translation",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue
            )

            Spacer(modifier = Modifier.height(48.dp))

            FloatingActionButton(
                onClick = onStartScreen,
                containerColor = PrimaryBlue,
                modifier = Modifier.size(120.dp),
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Start Recording",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            LanguageSelectorRow(
                sourceLabel = sourceLabel,
                targetLabel = targetLabel,
                languageOptions = languageOptions,
                onSourceSelected = { newSource ->
                    onLanguageChange(newSource, selectedTargetLang)
                },
                onTargetSelected = { newTarget ->
                    onLanguageChange(selectedSourceLang, newTarget)
                },
                onSwap = {
                    onLanguageChange(selectedTargetLang, selectedSourceLang)
                }
            )
        }
    }
}

@Composable
fun LanguageSelectorRow(
    sourceLabel: String,
    targetLabel: String,
    languageOptions: List<LanguageOption>,
    onSourceSelected: (String) -> Unit,
    onTargetSelected: (String) -> Unit,
    onSwap: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownButton(
                title = "Translate from",
                selectedLabel = sourceLabel,
                languageOptions = languageOptions,
                onSelected = onSourceSelected
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onSwap) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Swap languages",
                tint = PrimaryBlue
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownButton(
                title = "Translate to",
                selectedLabel = targetLabel,
                languageOptions = languageOptions,
                onSelected = onTargetSelected
            )
        }
    }
}

@Composable
fun LanguageDropdownButton(
    title: String,
    selectedLabel: String,
    languageOptions: List<LanguageOption>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = selectedLabel,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.code)
                    }
                )
            }
        }
    }
}