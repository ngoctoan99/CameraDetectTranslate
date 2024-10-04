// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.airo.cameratranslate

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.airo.cameratranslate.java.GraphicOverlayNew
import com.google.android.gms.tasks.OnCompleteListener
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Objects
import java.util.stream.Collectors
import kotlin.math.max
import kotlin.math.min

/**
 * Graphic instance for rendering TextBlock position, size, and ID within an associated graphic
 * overlay view.
 */
class TextGraphic(overlay: GraphicOverlayNew?, private val element: Text?, private val lists : List<String>) : GraphicOverlayNew.Graphic(
    overlay!!
) {
    private val rectPaint = Paint()
    private val textPaint: Paint
    private val labelPaint: Paint
    private var position: Int = 0

    init {
        rectPaint.color = Color.TRANSPARENT
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeWidth = STROKE_WIDTH

        textPaint = Paint()
        textPaint.color = TEXT_COLOR
        textPaint.textSize = TEXT_SIZE

        labelPaint = Paint()
        labelPaint.color = MARKER_COLOR
        labelPaint.style = Paint.Style.FILL
        // Redraw the overlay, as this graphic has been added.
        postInvalidate()

    }

    /**
     * Draws the text block annotations for position, size, and raw value on the supplied canvas.
     */
    override fun draw(canvas: Canvas?) {
        checkNotNull(element) { "Attempting to draw a null text." }
        for (textBlock in element.textBlocks) { // Renders the text at the bottom of the box.
            if (false) {
//                drawText(
//                    textBlock.text,
////                    RectF(textBlock.boundingBox),
//                    RectF(0f,0f,0f,0f),
//                    TEXT_SIZE * textBlock.lines.size + 2 * STROKE_WIDTH,
//                    canvas!!
//                )
            } else {
                Log.d("TTTT size","${textBlock.lines.size} // ${lists.size}")
                for (line in textBlock.lines) {
                    val rect = RectF(line.boundingBox)
                    drawText(
                        lists[position],
                        rect,
                        line.boundingBox!!.height() + 2 * STROKE_WIDTH,
                        canvas!!
                    )

                    if(position == textBlock.lines.size - 1){
                        GlobalScope.launch(Dispatchers.Main) {
                            position = 0
                        }

                    }else {
                        position++
                    }
                }
            }
        }
    }

    private fun drawText(text: String, rect: RectF, textHeight: Float, canvas: Canvas) {
        // If the image is flipped, the left will be translated to right, and the right to left.
        val x0 = translateX(rect.left)
        val x1 = translateX(rect.right)
        rect.left = min(x0, x1)
        rect.right = max(x0, x1)
        rect.top = translateY(rect.top)
        rect.bottom = translateY(rect.bottom)
        canvas.drawRect(rect, rectPaint)
        textPaint.textSize =textHeight
        val textWidth = textPaint.measureText(text)
        canvas.drawRect(
            rect.left - STROKE_WIDTH,
            rect.top - textHeight,
            rect.left + textWidth + 2 * STROKE_WIDTH,
            rect.top,
            labelPaint
        )
        // Renders the text at the bottom of the box.
        canvas.drawText(text, rect.left, rect.top - STROKE_WIDTH, textPaint)
    }

//    private fun getFormattedText(text: String, languageTag: String, confidence: Float?): String {
//        val res =
//            if (false) String.format(TEXT_WITH_LANGUAGE_TAG_FORMAT, languageTag, text) else text
//        return if (false && confidence != null) String.format("%s (%.2f)", res, confidence)
//        else res
//    }

    companion object {
        private const val TAG = "TextGraphic"
        private const val TEXT_COLOR = Color.BLACK
        private const val TEXT_SIZE = 30.0f
        private const val STROKE_WIDTH = 4.0f
        private const val TEXT_WITH_LANGUAGE_TAG_FORMAT = "%s:%s"
        private const val MARKER_COLOR = Color.WHITE
    }
}
