/*
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.airo.cameratranslate.analyzer

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import com.airo.cameratranslate.MainViewModel
import com.airo.cameratranslate.TextGraphic
import com.airo.cameratranslate.java.GraphicOverlayNew
import com.airo.cameratranslate.util.ResultOrError
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

//import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Analyzes the frames passed in from the camera and returns any detected text within the requested
 * crop region.
 */
class TextAnalyzer(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val result: MutableLiveData<String>,
//    private val imageCropPercentages: MutableLiveData<Pair<Int, Int>>,
    private val mGraphicOverlay : GraphicOverlayNew,
    private var viewModel : MainViewModel
) : ImageAnalysis.Analyzer {

    // TODO: Instantiate TextRecognition detector
//    private val detector = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val detector = TextRecognition.getClient()
    var needUpdateGraphicOverlayImageSourceInfo : Boolean = true
//    private val detector = TextRecognition.getClient()
    // TODO: Add lifecycle observer to properly close ML Kit detectors
    init {
        lifecycle.addObserver(detector)
    }
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        if (needUpdateGraphicOverlayImageSourceInfo) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees == 0 || rotationDegrees == 180) {
                mGraphicOverlay.setImageSourceInfo(
                    imageProxy.width, imageProxy.height, false
                )
            } else {
                mGraphicOverlay.setImageSourceInfo(
                    imageProxy.height, imageProxy.width, false
                )
            }
            needUpdateGraphicOverlayImageSourceInfo = false
        }
        // TODO call recognizeText() once implemented
        val inputImage: InputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
//        recognizeTextOnDevice(InputImage.fromBitmap(croppedBitmap, 0)).addOnCompleteListener {
//            imageProxy.close()
//        }
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            // Simulate background work
            Thread.sleep(2000)

            // Update the UI on the main thread
            recognizeTextOnDevice(inputImage).addOnCompleteListener {
                imageProxy.close()
            }
        }


    }

    private fun recognizeTextOnDevice(
        image: InputImage
    ): Task<Text> {
        // Pass image to an ML Kit Vision API
        return detector.process(image)
            .addOnSuccessListener { visionText ->
                // Task completed successfully
                result.value = visionText.text
//                if(visionText.textBlocks.size > 0){
//                    for( i in 0..<visionText.textBlocks.size){
//                        for( j in 0..<visionText.textBlocks[i].lines.size){
//                            result.value = visionText.textBlocks[i].lines[j].text
//                        }
//                    }
//                }
//                Log.e(TAG, "recognizeTextOnDevice" + visionText.text)
                processTextRecognitionResult(visionText,viewModel)
            }
            .addOnFailureListener { exception ->
                // Task failed with an exception
//                Log.e(TAG, "Text recognition error", exception)
                val message = getErrorMessage(exception)
                message?.let {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun processTextRecognitionResult(texts: Text , viewModel : MainViewModel) {

        val blocks = texts.textBlocks
        if (blocks.size == 0) {
            mGraphicOverlay.clear()
            return
        }
        mGraphicOverlay.clear()
//        for (i in blocks.indices) {
//            val lines = blocks[i].lines
//            for (j in lines.indices) {
//                val elements = lines[j].elements
//                for (k in elements.indices) {
//                    val textGraphic: GraphicOverlay.Graphic = TextGraphic(mGraphicOverlay, elements[k])
//                    mGraphicOverlay.add(textGraphic)
//                }
//            }
//        }

//        var list  = listOf("Enjoy drinking", "take care", "the driving")
        val listInit = emptyList<String>().toMutableList()
        for (i in blocks.indices) {
            val lines = blocks[i].lines
            for (j in lines.indices) {
                listInit += lines[j].text
            }

        }

        val string = listInit.joinToString(
            separator = "^",
        )
        Log.d("TTT1",""+string+ "" + listInit.size)
        val processTranslation =
            OnCompleteListener<String> { task ->
                if(task.isSuccessful){
                    val list = task.result!!.splitToSequence("^")
                        .filter { it.isNotEmpty() } // or: .filter { it.isNotBlank() }
                        .toList()
                    Log.d("TTT1",""+task.result.toString() + "" + list.size)
                    if(list.isNotEmpty() && list.size == listInit.size){
                        val textGraphic: GraphicOverlayNew.Graphic = TextGraphic(mGraphicOverlay, texts ,viewModel,list)
                        mGraphicOverlay.add(textGraphic)
                    }
                }else {
                    if (task.isCanceled) {
                        // Tasks are cancelled for reasons such as gating; ignore.
                        return@OnCompleteListener
                    }
//                    translatedText.value = ResultOrError(null, task.exception)
                    Log.d("TTT1","Error")
                }
            }
        viewModel.translateText(string).addOnCompleteListener(processTranslation)

//        val textGraphic: GraphicOverlayNew.Graphic = TextGraphic(mGraphicOverlay, texts ,viewModel)
//        mGraphicOverlay.add(textGraphic)

    }

    private fun getErrorMessage(exception: Exception): String? {
        val mlKitException = exception as? MlKitException ?: return exception.message
        return if (mlKitException.errorCode == MlKitException.UNAVAILABLE) {
            "Waiting for text recognition model to be downloaded"
        } else exception.message
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "TextAnalyzer"
    }
}