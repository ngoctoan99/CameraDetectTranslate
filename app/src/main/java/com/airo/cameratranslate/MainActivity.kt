package com.airo.cameratranslate

import android.Manifest
import android.R
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.airo.cameratranslate.analyzer.TextAnalyzer
import com.airo.cameratranslate.databinding.ActivityMainBinding
import com.airo.cameratranslate.util.Language
import com.airo.cameratranslate.util.ScopedExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object{
        val DESIRED_WIDTH_CROP_PERCENT = 0
        val DESIRED_HEIGHT_CROP_PERCENT = 0
    }


    // This is an arbitrary number we are using to keep tab of the permission
    // request. Where an app has multiple context for requesting permission,
    // this can help differentiate the different contexts
    val REQUEST_CODE_PERMISSIONS = 10

    // This is an array of all the permission specified in the manifest
    val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    val RATIO_4_3_VALUE = 4.0 / 3.0
    val RATIO_16_9_VALUE = 16.0 / 9.0
    private var displayId: Int = -1

    private val TAG = "TTTLog"

    private val viewModel: MainViewModel by viewModels()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
//    private lateinit var container: ConstraintLayout
//    private lateinit var viewFinder: PreviewView

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var binding: ActivityMainBinding
    private lateinit var scopedExecutor: ScopedExecutor
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        scopedExecutor = ScopedExecutor(cameraExecutor)

        if (allPermissionsGranted()) {
            // Wait for the views to be properly laid out
            binding.viewFinder.post {
                // Keep track of the display in which this view is attached
                displayId = binding.viewFinder.display.displayId

                // Set up the camera and its use cases
                setUpCamera()
            }
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        val adapter = ArrayAdapter(
            this,
            R.layout.simple_spinner_dropdown_item, viewModel.availableLanguages
        )

        binding.targetLangSelector.adapter = adapter
        binding.targetLangSelector.setSelection(adapter.getPosition(Language("en")))
        binding.targetLangSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                viewModel.targetLang.value = adapter.getItem(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        viewModel.sourceLang.observe(this, Observer { binding.srcLang.text = it.displayName })
        viewModel.translatedText.observe(this, Observer { resultOrError ->
            resultOrError?.let {
                if (it.error != null) {
                    binding.translatedText.error = resultOrError.error?.localizedMessage
                } else {
                    binding.translatedText.text = resultOrError.result
                }
            }
        })
        viewModel.modelDownloading.observe(this, Observer { isDownloading ->
            binding.progressBar.visibility = if (isDownloading) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            binding.progressText.visibility = binding.progressBar.visibility
        })

        binding.overlay.apply {
            setZOrderOnTop(true)
            holder.setFormat(PixelFormat.TRANSPARENT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                }

                override fun surfaceCreated(holder: SurfaceHolder) {
//                    holder?.let { drawOverlay(it, DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }
                }

            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scopedExecutor.shutdown()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            this, it
        ) == PackageManager.PERMISSION_GRANTED
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                binding.viewFinder.post {
                    // Keep track of the display in which this view is attached
                    displayId = binding.viewFinder.display.displayId

                    // Set up the camera and its use cases
                    setUpCamera()
                }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { binding.viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = binding.viewFinder.display.rotation

        val preview = Preview.Builder()
//            .setTargetAspectRatio(screenAspectRatio)
//            .setTargetRotation(rotation)
            .build()

        // Build the image analysis use case and instantiate our analyzer
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
//            .setTargetAspectRatio(screenAspectRatio)
//            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor
                    , TextAnalyzer(
                        this,
                        lifecycle,
                        viewModel.sourceText,
                        viewModel.imageCropPercentages,
                        binding.graphicOverlay
                    )
                )
            }
        viewModel.sourceText.observe(this, Observer { binding.srcText.text = it })
//        viewModel.imageCropPercentages.observe(this,
//            Observer { drawOverlay(binding.overlay.holder, it.first, it.second) })

        // Select back camera since text detection does not work with front camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview.setSurfaceProvider(binding.viewFinder.createSurfaceProvider())
        } catch (exc: IllegalStateException) {
            Log.e(TAG, "Use case binding failed. This must be running on main thread.", exc)
        }
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = ln(max(width, height).toDouble() / min(width, height))
        if (abs(previewRatio - ln(RATIO_4_3_VALUE))
            <= abs(previewRatio - ln(RATIO_16_9_VALUE))
        ) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

//    private fun drawOverlay(
//        holder: SurfaceHolder,
//        heightCropPercent: Int,
//        widthCropPercent: Int
//    ) {
//        val canvas = holder.lockCanvas()
////        val bgPaint = Paint().apply {
////            alpha = 140
////        }
////        canvas.drawPaint(bgPaint)
//        val rectPaint = Paint()
//        rectPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
//        rectPaint.style = Paint.Style.FILL
//        rectPaint.color = Color.WHITE
//        val outlinePaint = Paint()
//        outlinePaint.style = Paint.Style.STROKE
//        outlinePaint.color = Color.RED
//        outlinePaint.strokeWidth = 4f
//        val surfaceWidth = holder.surfaceFrame.width()
//        val surfaceHeight = holder.surfaceFrame.height()
//
//        val cornerRadius = 25f
//        // Set rect centered in frame
//        val rectTop = surfaceHeight * heightCropPercent / 2 / 100f
//        val rectLeft = surfaceWidth * widthCropPercent / 2 / 100f
//        val rectRight = surfaceWidth * (1 - widthCropPercent / 2 / 100f)
//        val rectBottom = surfaceHeight * (1 - heightCropPercent / 2 / 100f)
//        val rect = RectF(rectLeft, rectTop, rectRight, rectBottom)
//        canvas.drawRoundRect(
//            rect, cornerRadius, cornerRadius, rectPaint
//        )
//        canvas.drawRoundRect(
//            rect, cornerRadius, cornerRadius, outlinePaint
//        )
//        val textPaint = Paint()
//        textPaint.color = Color.WHITE
//        textPaint.textSize = 50F
//
//        val overlayText =""
//        val textBounds = Rect()
//        textPaint.getTextBounds(overlayText, 0, overlayText.length, textBounds)
//        val textX = (surfaceWidth - textBounds.width()) / 2f
//        val textY = rectBottom + textBounds.height() + 15f // put text below rect and 15f padding
//        canvas.drawText("", textX, textY, textPaint)
//        holder.unlockCanvasAndPost(canvas)
//    }
}