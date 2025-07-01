package com.example.roomacoustic.screens.measure

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Looper
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import com.example.roomacoustic.viewmodel.RoomViewModel
import com.example.roomacoustic.yolo.BoundingBox
import com.example.roomacoustic.yolo.Constants
import com.example.roomacoustic.yolo.Detector
import com.example.roomacoustic.yolo.OverlayView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import androidx.compose.foundation.background       // Modifier.background(…)
import androidx.compose.ui.graphics.Color          // Color.Black 등


@Composable
fun MeasureScreen(nav: NavController, vm: RoomViewModel) {
    val ctx             = LocalContext.current
    val lifecycleOwner  = ctx as LifecycleOwner
    val uiScope         = rememberCoroutineScope()

    /* ---------------- state ---------------- */
    val previewView = remember { PreviewView(ctx) }
    var overlayView  by remember { mutableStateOf<OverlayView?>(null) }

    val camExecutor = remember { Executors.newSingleThreadExecutor() }
    var detector    by remember { mutableStateOf<Detector?>(null) }

    // 연속 성공·실패 카운트
    var detectStreak by remember { mutableStateOf(0) }
    var noDetectCnt  by remember { mutableStateOf(0) }
    val REQUIRED_STREAK = 10
    val MAX_NO_DETECT   = 40

    var processing by remember { mutableStateOf(true) }      // GPU 커널 종료 플래그
    var bmpReuse   by remember { mutableStateOf<Bitmap?>(null) }   // 재사용 버퍼

    var showFailDialog by remember { mutableStateOf(false) }
    var measurementFinished by remember { mutableStateOf(false) }

    /* ---------- 종료 공통 함수 ---------- */
    fun finishMeasurement(success: Boolean) {
        if (measurementFinished) return        // 이미 끝났으면 무시
        measurementFinished = true             // ✅ 한 번만 실행하도록 플래그 ON

        processing = false                     // 분석 루프 중단
        uiScope.launch {
            ProcessCameraProvider.getInstance(ctx).get().unbindAll()
            detector?.close()
            nav.navigate("Render?detected=$success") {
                popUpTo("Measure") { inclusive = true }
            }
        }
    }



    /* ------------- init detector ------------- */
    LaunchedEffect(Unit) {
        detector = Detector(ctx, Constants.MODEL_PATH, Constants.LABELS_PATH,
            detectorListener = object : Detector.DetectorListener {
                override fun onEmptyDetect() {
                    overlayView?.clear()
                    vm.setSpeakerBoxes(emptyList())
                    detectStreak = 0

                    /* ✔ 모든 프레임에서 카운트 */
                    noDetectCnt++

                    /* ✔ 실패 판단 */
                    if (noDetectCnt >= MAX_NO_DETECT && processing) {
                        processing     = false      // ★ 측정 일시 중단
                        showFailDialog = true       // ★ 다이얼로그 호출
                    }
                }
                override fun onDetect(boxes: List<BoundingBox>, inferenceTime: Long) {
                    detectStreak++
                    noDetectCnt++                    // ✔ 카운트 유지 (리셋 ❌)

                    overlayView?.setResults(boxes)
                    vm.setSpeakerBoxes(
                        boxes.map { RectF(it.x1, it.y1, it.x2, it.y2) }
                    )

                    when {
                        detectStreak >= REQUIRED_STREAK -> finishMeasurement(true)
                        noDetectCnt  >= MAX_NO_DETECT && processing -> {
                            processing     = false
                            showFailDialog = true
                        }
                    }
                }
            }, message = { /* log */ }
        ).apply {
            restart(isGpu = true)
            warmUp()
        }
    }


    /* -------------- UI -------------- */
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)              // 남는 영역 검정 레터박스
            .wrapContentSize(Alignment.Center)    // 4:3 화면을 가운데 고정
    ) {
        /* Preview  ─────────────────────────────── */
        AndroidView(
            factory  = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)              // ★ 4:3 고정
        )

        /* Overlay  ─────────────────────────────── */
        AndroidView(
            factory  = { ctx -> OverlayView(ctx) },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)              // ★ Preview 와 동일 비율
                .zIndex(1f)
        ) { ov -> overlayView = ov }

        /* ---------- 실패 AlertDialog ---------- */
        if (showFailDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { /* 무시 (강제 선택) */ },
                title = { Text("스피커 미탐지") },
                text  = { Text("스피커가 감지되지 않았습니다.\n다시 측정하시겠습니까?") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        // ★ 재측정
                        detectStreak  = 0
                        noDetectCnt   = 0
                        overlayView?.clear()
                        processing    = true      // 측정 재개
                        showFailDialog = false
                    }) { Text("재측정") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showFailDialog = false
                        finishMeasurement(false)   // ★ 그대로 진행
                    }) { Text("계속 진행") }
                })
        }
    }


    /* ----------- CameraX setup ----------- */
    LaunchedEffect(detector) {                                  // previewView 는 remember 라 재생성 X
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build().apply {
                setAnalyzer(camExecutor) { px ->
                    if (!processing) { px.close(); return@setAnalyzer }

                    /* ---------- ① Buffer → Bitmap ---------- */
                    // 재사용 버퍼
                    if (bmpReuse == null || bmpReuse!!.width != px.width) {
                        bmpReuse = Bitmap.createBitmap(px.width, px.height, Bitmap.Config.ARGB_8888)
                    }
                    val bmp = bmpReuse!!
                    bmp.copyPixelsFromBuffer(px.planes[0].buffer)

                    /* ---------- ② 회전 보정 ---------- */
                    val rotated = if (px.imageInfo.rotationDegrees != 0) {
                        val m = Matrix().apply { postRotate(px.imageInfo.rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                    } else bmp

                    /* ---------- ③ 모델 입력크기(정사각형)로 리사이즈 ---------- */
                    val square = Bitmap.createScaledBitmap(
                        rotated,
                        detector!!.inputSize,          // ex) 640
                        detector!!.inputSize,
                        false
                    )

                    /* ---------- ④ 탐지 ---------- */
                    detector?.detect(square, rotated.width, rotated.height)

                    px.close()
                }
            }

        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }

    /* ---------- 정리 ---------- */
    DisposableEffect(Unit) {
        onDispose {
            detector?.close()
            camExecutor.shutdownNow()
        }
    }
}