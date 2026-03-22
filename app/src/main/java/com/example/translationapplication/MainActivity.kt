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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.tooling.preview.Preview
import com.example.translationapplication.ui.theme.TranslationApplicationTheme
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.Uri
import android.provider.Settings

class MainActivity : ComponentActivity() {
    private val translatedTexts = mutableStateOf<List<String>>(emptyList())
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private lateinit var imageReader: ImageReader
    private var webSocket: WebSocket? = null

    private var lastSentTime = 0L
    private val sendIntervalMs = 200L

    private var waitingForResponse = false

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
                    setupWebSocket()
                    startCapture()
                }, 500)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        setContent {
            TranslationApplicationTheme {
                AppUI(onStart = { startScreenCapture() })
            }
        }
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startCaptureLauncher.launch(intent)
    }

    private fun setupWebSocket() {
        val client = OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url("ws://172.20.10.5:8000/ws").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("WebSocket connected!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                waitingForResponse = false
                runOnUiThread {
                    try {
                        val jsonArray = org.json.JSONArray(text)
                        val items = mutableListOf<TranslationItem>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val box = obj.getJSONArray("box")
                            val x1 = box.getJSONArray(0).getDouble(0).toFloat()
                            val y1 = box.getJSONArray(0).getDouble(1).toFloat()
                            val x2 = box.getJSONArray(2).getDouble(0).toFloat()
                            val y2 = box.getJSONArray(2).getDouble(1).toFloat()
                            val translated = obj.getString("translated")
                            items.add(TranslationItem(x1, y1, x2, y2, translated))
                        }

                        // Build JSON string to pass to OverlayService
                        val jsonItems = org.json.JSONArray()
                        for (item in items) {
                            val o = org.json.JSONObject()
                            o.put("x1", item.x1)
                            o.put("y1", item.y1)
                            o.put("x2", item.x2)
                            o.put("y2", item.y2)
                            o.put("translated", item.translated)
                            jsonItems.put(o)
                        }

                        val overlayIntent = Intent(this@MainActivity, OverlayService::class.java)
                        overlayIntent.putExtra("items_json", jsonItems.toString())
                        startService(overlayIntent)

                    } catch (e: Exception) {
                        println("Failed to parse: ${e.message}")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                waitingForResponse = false
                runOnUiThread {
                    println("WebSocket error: ${t.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("WebSocket closed: $reason")
            }
        })
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
            val image = imageReader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener

            val now = SystemClock.elapsedRealtime()

            if (now - lastSentTime < sendIntervalMs || waitingForResponse) {
                image.close()
                return@setOnImageAvailableListener
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            lastSentTime = now
            waitingForResponse = true
            sendToServer(bitmap)

        }, backgroundHandler)
    }

    private fun sendToServer(bitmap: Bitmap) {
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val byteArray = stream.toByteArray()
            webSocket?.send(ByteString.of(*byteArray))
        } catch (e: Exception) {
            waitingForResponse = false
            println("Failed to send frame: ${e.message}")
        }
    }
}

@Composable
fun AppUI(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(onClick = { onStart() }) {
            Text("Start Translation")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewUI() {
    TranslationApplicationTheme {
        AppUI(onStart = {})
    }
}