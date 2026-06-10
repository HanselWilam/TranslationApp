package com.example.translationapplication

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.ByteArrayOutputStream

data class CameraTextBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val text: String
)

fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }

        if (bitmap.width > 2048 || bitmap.height > 2048) {
            val ratio = minOf(2048f / bitmap.width, 2048f / bitmap.height)
            bitmap.scale((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e("CameraTranslation", "Error loading gallery image", e)
        null
    }
}

@Composable
fun CameraTranslationScreen(
    webSocketManager: WebSocketManager,
    sourceLang: String,
    targetLang: String,
    onLanguageChange: (String, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember { mutableStateOf(false) }

    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isFromGallery by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var textBoxes by remember { mutableStateOf(listOf<CameraTextBox>()) }

    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var isHUDVisible by remember { mutableStateOf(true) }

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val imageCapture = remember { ImageCapture.Builder().build() }

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

    val sourceLabel = languageOptions.firstOrNull { it.code == sourceLang }?.label ?: "Unknown"
    val targetLabel = languageOptions.firstOrNull { it.code == targetLang }?.label ?: "Unknown"

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(2000)
            focusPoint = null
        }
    }

    BackHandler {
        if (capturedBitmap != null) {
            capturedBitmap = null
            textBoxes = emptyList()
            showOriginal = false
            zoomScale = 1f
            panOffset = Offset.Zero
            isHUDVisible = true
        } else {
            onNavigateBack()
        }
    }

    val sendImageToBackend = { bitmap: Bitmap, fromGallery: Boolean ->
        isProcessing = true
        textBoxes = emptyList()
        capturedBitmap = bitmap
        isFromGallery = fromGallery
        showOriginal = false
        zoomScale = 1f
        panOffset = Offset.Zero
        isHUDVisible = true

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        webSocketManager.sendImage(stream.toByteArray())
    }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bitmap = loadBitmapFromUri(context, uri)
            if (bitmap != null) {
                sendImageToBackend(bitmap, true)
            }
        }
    }

    LaunchedEffect(sourceLang, targetLang) {
        if (capturedBitmap != null && !isProcessing) {
            sendImageToBackend(capturedBitmap!!, isFromGallery  )
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        val originalCallback = webSocketManager.onResultReceived

        webSocketManager.onResultReceived = { jsonString ->
            try {
                val jsonArray = JSONArray(jsonString)
                val newBoxes = mutableListOf<CameraTextBox>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val box = item.getJSONArray("box")
                    val topLeft = box.getJSONArray(0)
                    val bottomRight = box.getJSONArray(2)

                    newBoxes.add(
                        CameraTextBox(
                            left = topLeft.getDouble(0).toFloat(),
                            top = topLeft.getDouble(1).toFloat(),
                            right = bottomRight.getDouble(0).toFloat(),
                            bottom = bottomRight.getDouble(1).toFloat(),
                            text = item.getString("translated")
                        )
                    )
                }
                textBoxes = newBoxes
            } catch (e: Exception) {
                Log.e("CameraTranslation", "Error parsing response data", e)
            } finally {
                isProcessing = false
            }
        }
        onDispose {
            webSocketManager.onResultReceived = originalCallback
        }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Camera permission is required.", color = Color.White)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(capturedBitmap) {
                    if (capturedBitmap != null) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(0.8f, 5f)

                            val bitmapWidth = capturedBitmap!!.width.toFloat()
                            val bitmapHeight = capturedBitmap!!.height.toFloat()
                            val scaleX = size.width.toFloat() / bitmapWidth
                            val scaleY = size.height.toFloat() / bitmapHeight
                            val scale = if (isFromGallery) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)

                            val maxPanX = maxOf(0f, (bitmapWidth * scale * zoomScale - size.width) / 2f)
                            val maxPanY = maxOf(0f, (bitmapHeight * scale * zoomScale - size.height) / 2f)

                            panOffset = Offset(
                                x = (panOffset.x + pan.x * zoomScale).coerceIn(-maxPanX, maxPanX),
                                y = (panOffset.y + pan.y * zoomScale).coerceIn(-maxPanY, maxPanY)
                            )
                        }
                    }
                }
                .pointerInput(capturedBitmap) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            if (capturedBitmap != null) {
                                isHUDVisible = !isHUDVisible
                            } else {
                                previewViewRef?.let { pv ->
                                    val factory = pv.meteringPointFactory
                                    val point = factory.createPoint(tapOffset.x, tapOffset.y)
                                    cameraControl?.cancelFocusAndMetering()

                                    val action = FocusMeteringAction.Builder(
                                        point,
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
                                    ).disableAutoCancel().build()

                                    cameraControl?.startFocusAndMetering(action)
                                    focusPoint = tapOffset
                                }
                            }
                        }
                    )
                }
                .graphicsLayer(
                    scaleX = zoomScale,
                    scaleY = zoomScale,
                    translationX = panOffset.x,
                    translationY = panOffset.y
                )
        ) {
            if (capturedBitmap == null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PreviewView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewViewRef = this
                        }.also { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                try {
                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture
                                    )
                                    cameraControl = camera.cameraControl
                                } catch (exc: Exception) {
                                    Log.e("CameraX", "Use case binding failed", exc)
                                }
                            }, ContextCompat.getMainExecutor(context))
                        }
                    }
                )

                if (focusPoint != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color(0xCCFFFFFF),
                            radius = 80f,
                            center = focusPoint!!,
                            style = Stroke(width = 4f)
                        )
                    }
                }
            } else {
                Image(
                    bitmap = capturedBitmap!!.asImageBitmap(),
                    contentDescription = "Captured Frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isFromGallery) ContentScale.Fit else ContentScale.Crop
                )

                if (!showOriginal) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val bitmapWidth = capturedBitmap?.width?.toFloat() ?: 1f
                        val bitmapHeight = capturedBitmap?.height?.toFloat() ?: 1f

                        val scaleX = size.width / bitmapWidth
                        val scaleY = size.height / bitmapHeight
                        val scale = if (isFromGallery) minOf(scaleX, scaleY) else maxOf(scaleX, scaleY)

                        val offsetX = (size.width - (bitmapWidth * scale)) / 2f
                        val offsetY = (size.height - (bitmapHeight * scale)) / 2f

                        textBoxes.forEach { box ->
                            val scaledLeft = (box.left * scale) + offsetX
                            val scaledTop = (box.top * scale) + offsetY
                            val scaledRight = (box.right * scale) + offsetX

                            val padding = 8f
                            val baseBoxWidth = scaledRight - scaledLeft

                            val maxAllowedWidth = baseBoxWidth + 250f
                            val screenRightBound = size.width - scaledLeft - (padding * 2)
                            val finalMaxWidth = minOf(maxAllowedWidth, screenRightBound).toInt()

                            val textLayoutResult = textMeasurer.measure(
                                text = box.text,
                                style = TextStyle(
                                    color = Color(0xFF202124),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                constraints = Constraints(
                                    maxWidth = maxOf(20, finalMaxWidth)
                                )
                            )

                            // Dynamic Background sizing
                            val bgWidth = textLayoutResult.size.width.toFloat() + (padding * 2)
                            val bgHeight = textLayoutResult.size.height.toFloat() + (padding * 2)

                            drawRoundRect(
                                color = Color(0xFFF8F9FA),
                                topLeft = Offset(scaledLeft, scaledTop),
                                size = Size(bgWidth, bgHeight),
                                cornerRadius = CornerRadius(8f, 8f)
                            )

                            drawText(
                                textLayoutResult = textLayoutResult,
                                topLeft = Offset(scaledLeft + padding, scaledTop + padding)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isHUDVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (capturedBitmap != null) {
                            capturedBitmap = null
                            textBoxes = emptyList()
                            showOriginal = false
                            zoomScale = 1f
                            panOffset = Offset.Zero
                            isHUDVisible = true
                        } else {
                            onNavigateBack()
                        }
                    },
                    modifier = Modifier.background(Color(0x66000000), CircleShape)
                ) {
                    Icon(
                        imageVector = if (capturedBitmap != null) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (capturedBitmap != null && !isProcessing) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0x99000000))
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (showOriginal) Color.White else Color.Transparent)
                                .clickable { showOriginal = true }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Original", color = if (showOriginal) Color.Black else Color.White, fontSize = 14.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (!showOriginal) Color.White else Color.Transparent)
                                .clickable { showOriginal = false }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Translated", color = if (!showOriginal) Color.Black else Color.White, fontSize = 14.sp)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (capturedBitmap == null) {
                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            cameraControl?.enableTorch(isTorchOn)
                        },
                        modifier = Modifier.background(Color(0x66000000), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Flash",
                            tint = if (isTorchOn) Color.Yellow else Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }

        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        AnimatedVisibility(
            visible = isHUDVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (capturedBitmap == null && !isProcessing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                pickVisualMediaLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x80000000))
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Gallery",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))

                        FloatingActionButton(
                            onClick = {
                                imageCapture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                            val bitmap = imageProxy.toBitmap()
                                            val matrix = Matrix().apply {
                                                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                                            }
                                            val rotatedBitmap = Bitmap.createBitmap(
                                                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                                            )
                                            sendImageToBackend(rotatedBitmap, false)
                                            imageProxy.close()
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            Log.e("CameraX", "Capture failed", exception)
                                            isProcessing = false
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            containerColor = Color.White
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Capture Shutter",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Spacer(modifier = Modifier.height(72.dp + 24.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xB3000000))
                        .padding(vertical = 16.dp, horizontal = 16.dp)
                ) {
                    LanguageSelectorRow(
                        sourceLabel = sourceLabel,
                        targetLabel = targetLabel,
                        languageOptions = languageOptions,
                        onSourceSelected = { newSource -> onLanguageChange(newSource, targetLang) },
                        onTargetSelected = { newTarget -> onLanguageChange(sourceLang, newTarget) },
                        onSwap = { onLanguageChange(targetLang, sourceLang) }
                    )
                }
            }
        }
    }
}