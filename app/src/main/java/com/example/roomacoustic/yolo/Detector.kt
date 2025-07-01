package com.example.roomacoustic.yolo

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.roomacoustic.yolo.MetaData.extractNamesFromLabelFile
import com.example.roomacoustic.yolo.MetaData.extractNamesFromMetadata
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String?,
    private val detectorListener: DetectorListener,
    private val message: (String) -> Unit
) {

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    private var isGpu = true     // restart()·init에서 동일 플래그 사용

    var frameW = 0            // 분석기에 들어온 “원본” 폭
        private set
    var frameH = 0            // 분석기에 들어온 “원본” 높이
        private set
    val inputSize get() = tensorWidth      // 모델 한 변(=정사각형 크기)


    init {
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply {
            if (isGpu && compatList.isDelegateSupportedOnThisDevice) {
                val gpuOpts = compatList.bestOptionsForThisDevice.apply {
                    setQuantizedModelsAllowed(true)      // ★ INT8 허용
                }
                val gpu = GpuDelegate(gpuOpts)
                addDelegate(gpu)
                // fallback 검사용 로그
                println("GPU-delegate created: ${gpuOpts}")
            } else {
                setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        labels.addAll(extractNamesFromMetadata(model))
        if (labels.isEmpty()) {
            if (labelPath == null) {
                message("Model not contains metadata, provide LABELS_PATH in Constants.kt")
                labels.addAll(MetaData.TEMP_CLASSES)
            } else {
                labels.addAll(extractNamesFromLabelFile(context, labelPath))
            }
        }

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // If in case input shape is in format of [1, 3, ..., ...]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply{
                if(compatList.isDelegateSupportedOnThisDevice){
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply{
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        interpreter.close()
    }

    /* ──────────────────────────────────────────────── */
    /* ① “외부”에서 호출할 detect – 원본 크기 전달용   */
    fun detect(square: Bitmap, origW: Int, origH: Int) {
        // 원본 프레임 폭·높이를 저장해 두었다가 bestBox()에서 사용
        frameW = origW
        frameH = origH
        detect(square)                 // ↓ ②번 함수(아래) 호출
    }

    /* ② 실제 추론을 수행하는 기존 detect(이름 그대로 둬도 됨) */
    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 ||
            numChannel  == 0 || numElements == 0) return

        var inferenceTime = SystemClock.uptimeMillis()

        /*  ── 입력 크기 맞추기 ──  */
        val inputBmp = if (frame.width != tensorWidth)
            Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, /*filter=*/false)
        else frame

        /*  ── 전처리 & 추론 ──  */
        val tensorImage   = TensorImage(INPUT_IMAGE_TYPE).apply { load(inputBmp) }
        val processed     = imageProcessor.process(tensorImage)
        val outputBuffer  = TensorBuffer.createFixedSize(
            intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE
        )
        interpreter.run(processed.buffer, outputBuffer.buffer)

        /*  ── 후처리 ──  */
        val boxes = bestBox(outputBuffer.floatArray)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        if (boxes == null) detectorListener.onEmptyDetect()
        else                detectorListener.onDetect(boxes, inferenceTime)
    }

    fun warmUp() {
        if (tensorWidth == 0 || tensorHeight == 0) return
        val dummy = Bitmap.createBitmap(tensorWidth, tensorHeight, Bitmap.Config.ARGB_8888)
        detect(dummy)
    }


    private fun bestBox(array: FloatArray) : List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()

        for (c in 0 until numElements) {
            var maxConf = CONFIDENCE_THRESHOLD
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > CONFIDENCE_THRESHOLD) {
                val clsName = labels[maxIdx]
                val cx = array[c] // 0
                val cy = array[c + numElements] // 1
                val w = array[c + numElements * 2]
                val h = array[c + numElements * 3]
                val x1 = cx - (w/2F)
                val y1 = cy - (h/2F)
                val x2 = cx + (w/2F)
                val y2 = cy + (h/2F)
                if (x1 < 0F || x1 > 1F) continue
                if (y1 < 0F || y1 > 1F) continue
                if (x2 < 0F || x2 > 1F) continue
                if (y2 < 0F || y2 > 1F) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>) : MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while(sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val CONFIDENCE_THRESHOLD = 0.3F
        private const val IOU_THRESHOLD = 0.5F
    }


}