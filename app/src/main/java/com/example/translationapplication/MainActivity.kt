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
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.translationapplication.ui.theme.TranslationApplicationTheme
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenuItem

data class LanguageOption(val code: String, val label: String)

class MainActivity : ComponentActivity() {

    private var webSocket: okhttp3.WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var imageReader: ImageReader

    private var lastSentTime = 0L
    private val captureIntervalMs = 1000L
    private var isProcessingFrame = false
    private var lastBitmapHash = 0

    private lateinit var overlayManager: OverlayManager

    private var isTranslating = mutableStateOf(false)
    private var statusMessage = mutableStateOf("Ready")

    private val clearOverlayHandler = Handler(Looper.getMainLooper())
    private val clearOverlayRunnable = Runnable {
        overlayManager.clear()
        statusMessage.value = "No text detected"
    }

    companion object {
        const val SERVER_IP = "192.168.1.17"
    }

    private val startCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                startForegroundService(serviceIntent)

                Handler(Looper.getMainLooper()).postDelayed({
                    mediaProjection = mediaProjectionManager.getMediaProjection(
                        result.resultCode, result.data!!
                    )
                    val metrics = resources.displayMetrics
                    // 🔧 FIXED: Match 1:1 full resolution to eliminate coordinate stretching
                    overlayManager.show(metrics.widthPixels, metrics.heightPixels)
                    startCapture()
                }, 500)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overlayManager = OverlayManager(this)
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setContent {
            TranslationApplicationTheme {
                AppUI(
                    isTranslating = isTranslating.value,
                    statusMessage = statusMessage.value,
                    onStart = { srcLang, tgtLang ->
                        if (!Settings.canDrawOverlays(this)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                            statusMessage.value =
                                "Please grant overlay permission, then press Start again"
                        } else {
                            connectWebSocket(srcLang, tgtLang)
                            startScreenCapture()
                            isTranslating.value = true
                            statusMessage.value = "Connecting..."
                        }
                    },
                    onStop = { stopTranslation() }
                )
            }
        }
    }

    private fun connectWebSocket(srcLang: String, tgtLang: String) {
        // URL must be base path only
        val url = "ws://$SERVER_IP:8000/ws"
        println("🔗 Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                // Send the handshake JSON immediately after opening
                val handshake = """{"type": "language_pair", "sourceLang": "$srcLang", "targetLang": "$tgtLang"}"""
                webSocket.send(handshake)

                Handler(Looper.getMainLooper()).post {
                    statusMessage.value = "Connected — translating..."
                }
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                println("📥 Response: $text")
                Handler(Looper.getMainLooper()).post {
                    parseAndUpdateOverlay(text)
                }
            }

            override fun onFailure(
                webSocket: okhttp3.WebSocket,
                t: Throwable,
                response: Response?
            ) {
                println("❌ WebSocket error: ${t.message}")
                Handler(Looper.getMainLooper()).post {
                    statusMessage.value = "Connection failed: ${t.message}"
                    isTranslating.value = false
                }
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                println("⚠️ WebSocket closed: $reason")
                Handler(Looper.getMainLooper()).post {
                    statusMessage.value = "Disconnected"
                    isTranslating.value = false
                }
            }
        })
    }

    private fun parseAndUpdateOverlay(json: String) {
        clearOverlayHandler.removeCallbacks(clearOverlayRunnable)

        try {
            val array = JSONArray(json)

            if (array.length() == 0) {
                clearOverlayHandler.postDelayed(clearOverlayRunnable, 2000)
                statusMessage.value = "No text detected"
                return
            }

            val blocks = mutableListOf<TranslationBlock>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val translated = obj.getString("translated")
                val boxArray = obj.getJSONArray("box")
                val box = (0 until boxArray.length()).map { j ->
                    val pt = boxArray.getJSONArray(j)
                    listOf(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat())
                }
                blocks.add(TranslationBlock(box, translated))
                println("✅ Block: $translated")
            }

            overlayManager.updateBlocks(blocks)
            statusMessage.value = "Showing ${blocks.size} translations"

        } catch (e: Exception) {
            println("❌ Parse error: ${e.message}")
        }
    }

    private fun stopTranslation() {
        clearOverlayHandler.removeCallbacks(clearOverlayRunnable)
        webSocket?.close(1000, "User stopped")
        webSocket = null
        mediaProjection?.stop()
        mediaProjection = null
        overlayManager.dismiss()
        isTranslating.value = false
        statusMessage.value = "Stopped"
    }

    private fun startScreenCapture() {
        startCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { imageReader.close() }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )
        mediaProjection?.createVirtualDisplay(
            "ScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        val handlerThread = HandlerThread("ImageReaderThread")
        handlerThread.start()

        imageReader.setOnImageAvailableListener({
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()

            if (now - lastSentTime < captureIntervalMs || isProcessingFrame) {
                image.close()
                return@setOnImageAvailableListener
            }

            isProcessingFrame = true
            lastSentTime = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowPadding = plane.rowStride - plane.pixelStride * metrics.widthPixels
                val fullBitmap = Bitmap.createBitmap(
                    metrics.widthPixels + rowPadding / plane.pixelStride,
                    metrics.heightPixels,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val bitmap = Bitmap.createBitmap(fullBitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)

                // 🔧 FIXED: Removed Bitmap.createScaledBitmap downsizing to preserve original, unblurred text edges.
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream) // Quality bumped to 80 for clear OCR boundaries
                val jpegBytes = stream.toByteArray()

                val midPoint = jpegBytes.size / 2
                val sampleSize = minOf(1000, jpegBytes.size)
                val sample = jpegBytes.copyOfRange(midPoint, midPoint + sampleSize)
                val newHash = sample.contentHashCode()

                if (newHash == lastBitmapHash) {
                    println("⏭ Skipping unchanged frame")
                    isProcessingFrame = false
                    return@setOnImageAvailableListener
                }
                lastBitmapHash = newHash

                val sent = webSocket?.send(jpegBytes.toByteString())
                println("📤 Sent ${jpegBytes.size} bytes (${metrics.widthPixels}x${metrics.heightPixels}), success=$sent")

            } catch (e: Exception) {
                println("❌ Capture error: ${e.message}")
                try { image.close() } catch (_: Exception) {}
            } finally {
                isProcessingFrame = false
            }

        }, Handler(handlerThread.looper))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTranslation()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUI(
    isTranslating: Boolean,
    statusMessage: String,
    onStart: (srcLang: String, tgtLang: String) -> Unit,
    onStop: () -> Unit
) {
    val languageOptions = remember {
        listOf(
            LanguageOption("ja", "Japanese"),
            LanguageOption("en", "English"),
            LanguageOption("id", "Bahasa Indonesia"),
            LanguageOption("zh", "Chinese"),
            LanguageOption("ko", "Korean")
        )
    }

    var selectedSourceLang by remember { mutableStateOf("ja") }
    var selectedTargetLang by remember { mutableStateOf("en") }

    val sourceLabel = languageOptions.first { it.code == selectedSourceLang }.label
    val targetLabel = languageOptions.first { it.code == selectedTargetLang }.label

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Translation App",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Screen") },
                    label = { Text("Screen") },
                    selected = true,
                    onClick = { },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color(0xFFE3F2FD)
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
                text = "Screen Translation",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusMessage,
                fontSize = 13.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(32.dp))

            FloatingActionButton(
                onClick = {
                    if (isTranslating) onStop()
                    else if (selectedSourceLang != selectedTargetLang)
                        onStart(selectedSourceLang, selectedTargetLang)
                },
                containerColor = if (isTranslating) Color.Red
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(120.dp),
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(
                    imageVector = if (isTranslating) Icons.Default.Stop
                    else Icons.Default.PlayArrow,
                    contentDescription = if (isTranslating) "Stop" else "Start",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            if (selectedSourceLang == selectedTargetLang) {
                Text(
                    "⚠️ Source and target language are the same",
                    color = Color.Red,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            LanguageSelectorRow(
                sourceLabel = sourceLabel,
                targetLabel = targetLabel,
                languageOptions = languageOptions,
                onSourceSelected = { selectedSourceLang = it },
                onTargetSelected = { selectedTargetLang = it },
                onSwap = {
                    val temp = selectedSourceLang
                    selectedSourceLang = selectedTargetLang
                    selectedTargetLang = temp
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
                tint = MaterialTheme.colorScheme.primary
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