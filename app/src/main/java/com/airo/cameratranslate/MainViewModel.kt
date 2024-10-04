/*
 * Copyright 2020 Google Inc. All Rights Reserved.
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

package com.airo.cameratranslate

import android.app.Application
import android.os.Handler
import android.util.Log
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.airo.cameratranslate.MainActivity.Companion.DESIRED_HEIGHT_CROP_PERCENT
import com.airo.cameratranslate.MainActivity.Companion.DESIRED_WIDTH_CROP_PERCENT
import com.airo.cameratranslate.util.Language
import com.airo.cameratranslate.util.ResultOrError
import com.airo.cameratranslate.util.SmoothedMutableLiveData
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.text.Text


class MainViewModel(application: Application) : AndroidViewModel(application) {

    // TODO Instantiate LanguageIdentification
    private val languageIdentification = LanguageIdentification.getClient()
    val targetLang = MutableLiveData<Language>()
    val sourceText = SmoothedMutableLiveData<String>(SMOOTHING_DURATION)
    val textTranslate = SmoothedMutableLiveData<String>(SMOOTHING_DURATION)

    // We set desired crop percentages to avoid having to analyze the whole image from the live
    // camera feed. However, we are not guaranteed what aspect ratio we will get from the camera, so
    // we use the first frame we get back from the camera to update these crop percentages based on
    // the actual aspect ratio of images.
//    val imageCropPercentages = MutableLiveData<Pair<Int, Int>>()
//        .apply { value = Pair(DESIRED_HEIGHT_CROP_PERCENT, DESIRED_WIDTH_CROP_PERCENT) }
    val translatedText = MediatorLiveData<ResultOrError>()
    private val translating = MutableLiveData<Boolean>()
    val modelDownloading = SmoothedMutableLiveData<Boolean>(SMOOTHING_DURATION)

    private var modelDownloadTask: Task<Void> = Tasks.forCanceled()
    val modelManager = RemoteModelManager.getInstance()

    private val translators =
        object : LruCache<TranslatorOptions, Translator>(NUM_TRANSLATORS) {
            override fun create(options: TranslatorOptions): Translator {
//                Log.d("TranslatorCache", "Creating new translator for options: $options")
                return Translation.getClient(options)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: TranslatorOptions,
                oldValue: Translator,
                newValue: Translator?
            ) {
//                Log.d("TranslatorCache", "Removing translator for options: $key")
                try {
                    oldValue.close()
                } catch (e: Exception) {
//                    Log.e("TranslatorCache", "Error closing translator: ${e.message}")
                }
            }
        }

    val sourceLang = sourceText.switchMap{ text ->
        val result = MutableLiveData<Language>()
        // TODO  Call the language identification method and assigns the result if it is not
        //  undefined (“und”)
        languageIdentification.identifyLanguage(text)
            .addOnSuccessListener {
                if (it != "und")
                    result.value = Language(it)
            }
        result
    }

    override fun onCleared() {
        // TODO Shut down ML Kit clients.
        languageIdentification.close()
        translators.evictAll()
    }

    fun translate(): Task<String> {
        val text = sourceText.value
        val source = sourceLang.value
        val target = targetLang.value
        if (modelDownloading.value != false || translating.value != false) {
            return Tasks.forCanceled()
        }
        if (source == null || target == null || text == null || text.isEmpty()) {
            return Tasks.forResult("")
        }
        val sourceLangCode = TranslateLanguage.fromLanguageTag(source.code)
        val targetLangCode = TranslateLanguage.fromLanguageTag(target.code)
        if (sourceLangCode == null || targetLangCode == null) {
            return Tasks.forCanceled()
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode)
            .build()
        val translator = translators[options]
        modelDownloading.setValue(true)

        // Register watchdog to unblock long running downloads
        Handler().postDelayed({ modelDownloading.setValue(false) }, 15000)
        modelDownloadTask = translator.downloadModelIfNeeded().addOnCompleteListener {
            modelDownloading.setValue(false)
        }
        translating.value = true
        return modelDownloadTask.onSuccessTask {
            translator.translate(text)
        }.addOnCompleteListener {
            translating.value = false
        }
    }


    fun translateText( str : String): Task<String> {
//        Log.d("TTTT","translateText1" +str)
        val text = str
        val source = sourceLang.value
        val target = targetLang.value
        Log.d("TTTT translateText","source:" + sourceLang.value  +"target:" + targetLang.value)
        if (modelDownloading.value != false || translating.value != false) {
            return Tasks.forCanceled()
        }
        if (source == null || target == null || text == null || text.isEmpty()) {
            return Tasks.forResult("")
        }
        val sourceLangCode = TranslateLanguage.fromLanguageTag(source.code)
        val targetLangCode = TranslateLanguage.fromLanguageTag(target.code)
        if (sourceLangCode == null || targetLangCode == null) {
            return Tasks.forCanceled()
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode)
            .build()
        val translator = translators[options]
        modelDownloading.setValue(true)

        // Register watchdog to unblock long running downloads
        Handler().postDelayed({ modelDownloading.setValue(false) }, 15000)
        modelDownloadTask = translator.downloadModelIfNeeded().addOnCompleteListener {
            modelDownloading.setValue(false)
        }
        translating.value = true
        return modelDownloadTask.onSuccessTask {
            translator.translate(text)
        }.addOnCompleteListener {
            translating.value = false
        }
    }


    fun translateStrings(inputList: List<String>, callback: (List<String>) -> Unit) {
        val source = sourceLang.value
        val target = targetLang.value
        Log.d("TTTT translateText","source:" + sourceLang.value  +"target:" + targetLang.value)
        if (modelDownloading.value != false || translating.value != false) {
            return
        }
        if (source == null || target == null || inputList == null || inputList.isEmpty()) {
            return
        }
        val sourceLangCode = TranslateLanguage.fromLanguageTag(source.code)
        val targetLangCode = TranslateLanguage.fromLanguageTag(target.code)
        if (sourceLangCode == null || targetLangCode == null) {
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode)
            .build()
        val translator = translators[options]

        // Tải xuống mô hình ngôn ngữ nếu cần thiết
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                // Mô hình đã sẵn sàng, bắt đầu dịch từng chuỗi
                val translatedList = mutableListOf<String>()
                var count = 0

                inputList.forEach { text ->
                    translator.translate(text)
                        .addOnSuccessListener { translatedText ->
                            // Thêm chuỗi đã dịch vào danh sách kết quả
                            translatedList.add(translatedText)
                            count++

                            // Khi tất cả các chuỗi đã được dịch
                            if (count == inputList.size) {
                                // Gọi callback với danh sách kết quả đã dịch
                                callback(translatedList)
                            }
                        }
                        .addOnFailureListener { exception ->
                            // Xử lý lỗi dịch
                            exception.printStackTrace()
                        }
                }
            }
            .addOnFailureListener { exception ->
                // Xử lý lỗi tải mô hình
                exception.printStackTrace()
            }
    }

    // Gets a list of all available translation languages.
    val availableLanguages: List<Language> = TranslateLanguage.getAllLanguages()
        .map { Language(it) }

    init {
        modelDownloading.setValue(false)
        translating.value = false
        // Create a translation result or error object.
        val processTranslation =
            OnCompleteListener<String> { task ->
                if (task.isSuccessful) {
                    translatedText.value = ResultOrError(task.result, null)
                } else {
                    if (task.isCanceled) {
                        // Tasks are cancelled for reasons such as gating; ignore.
                        return@OnCompleteListener
                    }
                    translatedText.value = ResultOrError(null, task.exception)
                }
            }
        // Start translation if any of the following change: detected text, source lang, target lang.
//        translatedText.addSource(sourceText) { translate().addOnCompleteListener(processTranslation) }
//        translatedText.addSource(sourceLang) { translate().addOnCompleteListener(processTranslation) }
//        translatedText.addSource(targetLang) { translate().addOnCompleteListener(processTranslation) }


        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { downloadedModels ->
                // Iterate through the downloaded models and log their languages
                if(downloadedModels.size > 5){
                    for (model in downloadedModels) {
                        val language = model.language
                        println("Downloaded model for language: $language")
                        modelManager.deleteDownloadedModel(model)
                            .addOnSuccessListener {
                                // Model removed successfully
                                println("Removed model for language: ${model.language}")
                            }
                            .addOnFailureListener { exception ->
                                // Handle failure in removing model
                                exception.printStackTrace()
                            }
                    }
                }else {
                    println("Downloaded model for language: ${downloadedModels.size}")
                }

            }
            .addOnFailureListener { exception ->
                // Handle failure
                exception.printStackTrace()
            }
    }

    companion object {
        // Amount of time (in milliseconds) to wait for detected text to settle
        private const val SMOOTHING_DURATION = 50L

        private const val NUM_TRANSLATORS = 1
    }
}
