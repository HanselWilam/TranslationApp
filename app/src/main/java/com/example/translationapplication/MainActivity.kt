package com.example.translationapplication

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import androidx.core.net.toUri
import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translationapplication.ui.theme.TranslationApplicationTheme
import java.io.ByteArrayOutputStream
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

data class LanguageOption(val code: String, val label: String)

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var imageReader: ImageReader
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var viewOverlay: TranslationOverlay

    @Volatile private var sourceLang: String = "ja"
    @Volatile private var targetLang: String = "en"

    private var lastCheckTime = 0L
    private var lastStableTime = 0L
    private var lastFramePixels: IntArray? = null
    private var ignoreChangesUntil = 0L
    private var stopReceiver: android.content.BroadcastReceiver? = null

    private val checkIntervalMs = 300L
    private val settleLatencyMs = 1500L

    private var isTranslationPaused = false
    private var floatingWidget: FloatingControlWidget? = null

    private fun hasScreenChanged(bitmap: Bitmap): Boolean {
        val smallBitmap = bitmap.scale(32, 32, true)
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

            if ((rDiff + gDiff + bDiff) > 80) {
                significantPixelChanges++
            }
        }

        lastFramePixels = currentPixels
        if (SystemClock.elapsedRealtime() < ignoreChangesUntil) {
            return false
        }

        return significantPixelChanges > 60
    }

    private val startCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
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
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            startActivity(intent)
            Toast.makeText(this, "Please allow overlay permission and try again", Toast.LENGTH_LONG).show()
            return
        }

        if (viewOverlay.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.addView(viewOverlay, params)
        }

        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startCaptureLauncher.launch(intent)
    }

    private fun startCapture() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.maximumWindowMetrics
            width = metrics.bounds.width()
            height = metrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }

        val density = resources.displayMetrics.densityDpi

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

            if (isTranslationPaused) {
                image.close()
                return@setOnImageAvailableListener
            }

            if (now - lastCheckTime < checkIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastCheckTime = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

            val screenChanged = hasScreenChanged(croppedBitmap)

            if (screenChanged) {
                lastStableTime = now
                runOnUiThread { viewOverlay.clearBoxes() }
            } else {
                if (now - lastStableTime > settleLatencyMs) {

                    val stream = ByteArrayOutputStream()
                    croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    val byteArray = stream.toByteArray()

                    webSocketManager.sendImage(byteArray)

                    lastStableTime = now + 100000L
                }
            }
        }, backgroundHandler)
    }

    private fun stopScreenCapture() {
        mediaProjection?.stop()
        mediaProjection = null

        if (viewOverlay.parent != null) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeView(viewOverlay)
        }

        floatingWidget?.remove()
        floatingWidget = null

        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)

        Toast.makeText(this, "Translation Stopped", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver?.let { unregisterReceiver(it) }
        stopScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        viewOverlay = TranslationOverlay(this)

        webSocketManager = WebSocketManager { result ->
            Log.d("TRANSLATION_RAW", result)
            runOnUiThread {
                viewOverlay.updateTranslation(result)
                ignoreChangesUntil = SystemClock.elapsedRealtime() + 1500L
            }
        }
        webSocketManager.connect()
        webSocketManager.updateLanguagePair(sourceLang, targetLang)

        stopReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                stopScreenCapture()
            }
        }
        val filter = android.content.IntentFilter("com.example.translationapplication.STOP_CAPTURE")
        androidx.core.content.ContextCompat.registerReceiver(this, stopReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            TranslationApplicationTheme {
                var selectedSourceLang by remember { mutableStateOf(sourceLang) }
                var selectedTargetLang by remember { mutableStateOf(targetLang) }
                var activeScreen by remember { mutableStateOf("home") }

                if (activeScreen == "camera") {
                    CameraTranslationScreen (
                        webSocketManager = webSocketManager,
                        sourceLang = selectedSourceLang,
                        targetLang = selectedTargetLang,
                        onLanguageChange = { source, target ->
                            selectedSourceLang = source
                            selectedTargetLang = target
                            webSocketManager.updateLanguagePair(source, target)
                        },
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
            LanguageOption("id", "Bahasa Indonesia"),
            LanguageOption("zh", "Chinese (Simplified)"),
            LanguageOption("en", "English"),
//            LanguageOption("fr", "French"),
//            LanguageOption("de", "German"),
            LanguageOption("ja", "Japanese"),
            LanguageOption("ko", "Korean"),
//            LanguageOption("es", "Spanish"),
//            LanguageOption("ru", "Russian"),
        )
    }

    val sourceLabel = languageOptions.first { it.code == selectedSourceLang }.label
    val targetLabel = languageOptions.first { it.code == selectedTargetLang }.label

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Translation App", fontWeight = FontWeight.Bold, fontSize = 24.sp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.shadow(4.dp)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Screen") },
                    label = { Text("Screen") },
                    selected = true,
                    onClick = { },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PhotoCamera, contentDescription = "Camera") },
                    label = { Text("Camera") },
                    selected = false,
                    onClick = onStartCamera,
                    colors = NavigationBarItemDefaults.colors(
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                text = "Tap to Start",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            FloatingActionButton(
                onClick = onStartScreen,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(110.dp),
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Start Translation",
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            LanguageSelectorRow(
                sourceLabel = sourceLabel,
                targetLabel = targetLabel,
                languageOptions = languageOptions,
                onSourceSelected = { newSource -> onLanguageChange(newSource, selectedTargetLang) },
                onTargetSelected = { newTarget -> onLanguageChange(selectedSourceLang, newTarget) },
                onSwap = { onLanguageChange(selectedTargetLang, selectedSourceLang) }
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownButton(
                selectedLabel = sourceLabel,
                languageOptions = languageOptions,
                onSelected = onSourceSelected
            )
        }

        IconButton(
            onClick = onSwap,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Swap languages",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            LanguageDropdownButton(
                selectedLabel = targetLabel,
                languageOptions = languageOptions,
                onSelected = onTargetSelected
            )
        }
    }
}

@Composable
fun LanguageDropdownButton(
    selectedLabel: String,
    languageOptions: List<LanguageOption>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = selectedLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            languageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(
                        text = option.label,
                        color = MaterialTheme.colorScheme.onSurface
                    )},
                    onClick = {
                        expanded = false
                        onSelected(option.code)
                    }
                )
            }
        }
    }
}