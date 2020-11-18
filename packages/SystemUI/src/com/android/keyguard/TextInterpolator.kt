/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.keyguard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.text.PositionedGlyphs
import android.graphics.text.TextRunShaper
import android.text.Layout
import android.util.MathUtils
import com.android.internal.graphics.ColorUtils
import java.lang.Math.max

/**
 * Provide text style linear interpolation for plain text.
 */
class TextInterpolator(
    layout: Layout,
    lines: Int = 1
) {

    /**
     * Returns base paint used for interpolation.
     *
     * Once you modified the style parameters, you have to call reshapeText to recalculate base text
     * layout.
     *
     * @return an array list of paint objects representing one paint per line of text. If this
     * list has a smaller size than the number of lines, all extra lines will use the Paint an
     * index 0.
     */
    val basePaint = createDefaultPaint(layout.paint, lines)

    /**
     * Returns target paint used for interpolation.
     *
     * Once you modified the style parameters, you have to call reshapeText to recalculate target
     * text layout.
     *
     * @return an array list of paint objects representing one paint per line of text. If this
     * list has a smaller size than the number of lines, all extra lines will use the Paint an
     * index 0.
     */
    val targetPaint = createDefaultPaint(layout.paint, lines)

    private fun createDefaultPaint(paint: Paint, lines: Int): ArrayList<Paint> {
        val paintList = ArrayList<Paint>()
        for (i in 0 until lines)
            paintList.add(Paint(paint))
        return paintList
    }

    /**
     * A class represents a single font run.
     *
     * A font run is a range that will be drawn with the same font.
     */
    private data class FontRun(
        val start: Int, // inclusive
        val end: Int, // exclusive
        var baseFont: Font,
        var targetFont: Font
    ) {
        val length: Int get() = end - start
    }

    /**
     * A class represents text layout of a single line.
     */
    private class Line(
        val glyphIds: IntArray,
        val baseX: FloatArray, // same length as glyphIds
        val baseY: FloatArray, // same length as glyphIds
        val targetX: FloatArray, // same length as glyphIds
        val targetY: FloatArray, // same length as glyphIds
        val fontRuns: List<FontRun>
    )

    private var lines = listOf<Line>()
    private val fontInterpolator = FontInterpolator()

    // Recycling object for glyph drawing. Will be extended for the longest font run if needed.
    private val tmpDrawPaints = ArrayList<Paint>()
    private var tmpPositionArray = FloatArray(20)

    /**
     * The progress position of the interpolation.
     *
     * The 0f means the start state, 1f means the end state.
     */
    var progress: Float = 0f

    /**
     * The layout used for drawing text.
     *
     * Only non-styled text is supported. Even if the given layout is created from Spanned, the
     * span information is not used.
     *
     * The paint objects used for interpolation are not changed by this method call.
     *
     * Note: disabling ligature is strongly recommended if you give extra letter spacing since they
     * may be disjointed based on letter spacing value and cannot be interpolated. Animator will
     * throw runtime exception if they cannot be interpolated.
     */
    var layout: Layout = layout
        get() = field
        set(value) {
            field = value
            shapeText(value)
        }

    init {
        // shapeText needs to be called after all members are initialized.
        shapeText(layout)
    }

    /**
     * Recalculate internal text layout for interpolation.
     *
     * Whenever the target paint is modified, call this method to recalculate internal
     * text layout used for interpolation.
     */
    fun onTargetPaintModified() {
        updatePositionsAndFonts(shapeText(layout, targetPaint), updateBase = false)
    }

    /**
     * Recalculate internal text layout for interpolation.
     *
     * Whenever the base paint is modified, call this method to recalculate internal
     * text layout used for interpolation.
     */
    fun onBasePaintModified() {
        updatePositionsAndFonts(shapeText(layout, basePaint), updateBase = true)
    }

    /**
     * Rebase the base state to the middle of the interpolation.
     *
     * The text interpolator does not calculate all the text position by text shaper due to
     * performance reasons. Instead, the text interpolator shape the start and end state and
     * calculate text position of the middle state by linear interpolation. Due to this trick,
     * the text positions of the middle state is likely different from the text shaper result.
     * So, if you want to start animation from the middle state, you will see the glyph jumps due to
     * this trick, i.e. the progress 0.5 of interpolation between weight 400 and 700 is different
     * from text shape result of weight 550.
     *
     * After calling this method, do not call onBasePaintModified() since it reshape the text and
     * update the base state. As in above notice, the text shaping result at current progress is
     * different shaped result. By calling onBasePaintModified(), you may see the glyph jump.
     *
     * By calling this method, the progress will be reset to 0.
     *
     * This API is useful to continue animation from the middle of the state. For example, if you
     * animate weight from 200 to 400, then if you want to move back to 200 at the half of the
     * animation, it will look like
     *
     * <pre>
     * <code>
     *     val interp = TextInterpolator(layout)
     *
     *     // Interpolate between weight 200 to 400.
     *     interp.basePaint.fontVariationSettings = "'wght' 200"
     *     interp.onBasePaintModified()
     *     interp.targetPaint.fontVariationSettings = "'wght' 400"
     *     interp.onTargetPaintModified()
     *
     *     // animate
     *     val animator = ValueAnimator.ofFloat(1f).apply {
     *         addUpdaterListener {
     *             interp.progress = it.animateValue as Float
     *         }
     *     }.start()
     *
     *     // Here, assuming you receive some event and want to start new animation from current
     *     // state.
     *     OnSomeEvent {
     *         animator.cancel()
     *
     *         // start another animation from the current state.
     *         interp.rebase() // Use current state as base state.
     *         interp.targetPaint.fontVariationSettings = "'wght' 200" // set new target
     *         interp.onTargetPaintModified() // reshape target
     *
     *         // Here the textInterpolator interpolate from 'wght' from 300 to 200 if the current
     *         // progress is 0.5
     *         animator.start()
     *     }
     * </code>
     * </pre>
     *
     */
    fun rebase() {
        if (progress == 0f) {
            return
        } else if (progress == 1f) {
            updatePaint(basePaint, targetPaint)
        } else {
            lerp(basePaint, targetPaint, progress, tmpDrawPaints)
            updatePaint(basePaint, tmpDrawPaints)
        }

        lines.forEach { line ->
            for (i in line.baseX.indices) {
                line.baseX[i] = MathUtils.lerp(line.baseX[i], line.targetX[i], progress)
                line.baseY[i] = MathUtils.lerp(line.baseY[i], line.targetY[i], progress)
            }
            line.fontRuns.forEach {
                it.baseFont = fontInterpolator.lerp(it.baseFont, it.targetFont, progress)
            }
        }

        progress = 0f
    }

    companion object {
        fun updatePaint(toUpdate: ArrayList<Paint>, newValues: ArrayList<Paint>) {
            toUpdate.clear()
            for (paint in newValues)
                toUpdate.add(Paint(paint))
        }
    }

    /**
     * Draws interpolated text at the given progress.
     *
     * @param canvas a canvas.
     */
    fun draw(canvas: Canvas) {
        lerp(basePaint, targetPaint, progress, tmpDrawPaints)
        lines.forEachIndexed { lineNo, line ->
            canvas.save()
            try {
                // Move to drawing origin.
                val origin = layout.getDrawOrigin(lineNo)
                canvas.translate(origin, layout.getLineBaseline(lineNo).toFloat())

                line.fontRuns.forEach { run ->
                    if (lineNo >= tmpDrawPaints.size)
                        drawFontRun(canvas, line, run, tmpDrawPaints[0])
                    else
                        drawFontRun(canvas, line, run, tmpDrawPaints[lineNo])
                }
            } finally {
                canvas.restore()
            }
        }
    }

    // Shape text with current paint parameters.
    private fun shapeText(layout: Layout) {
        val baseLayout = shapeText(layout, basePaint)
        val targetLayout = shapeText(layout, targetPaint)

        require(baseLayout.size == targetLayout.size) {
            "The new layout result has different line count."
        }

        var maxRunLength = 0
        lines = baseLayout.zip(targetLayout) { base, target ->
            require(base.glyphCount() == target.glyphCount()) {
                "Inconsistent glyph count at line ${lines.size}"
            }

            val glyphCount = base.glyphCount()

            // Good to recycle the array if the existing array can hold the new layout result.
            val glyphIds = IntArray(glyphCount) {
                base.getGlyphId(it).also { baseGlyphId ->
                    require(baseGlyphId == target.getGlyphId(it)) {
                        "Inconsistent glyph ID at $it in line ${lines.size}"
                    }
                }
            }

            val baseX = FloatArray(glyphCount) { base.getGlyphX(it) }
            val baseY = FloatArray(glyphCount) { base.getGlyphY(it) }
            val targetX = FloatArray(glyphCount) { target.getGlyphX(it) }
            val targetY = FloatArray(glyphCount) { target.getGlyphY(it) }

            // Calculate font runs
            val fontRun = mutableListOf<FontRun>()
            if (glyphCount != 0) {
                var start = 0
                var baseFont = base.getFont(start)
                var targetFont = target.getFont(start)
                require(FontInterpolator.canInterpolate(baseFont, targetFont)) {
                    "Cannot interpolate font at $start ($baseFont vs $targetFont)"
                }

                for (i in 1 until glyphCount) {
                    val nextBaseFont = base.getFont(i)
                    val nextTargetFont = target.getFont(i)

                    if (baseFont !== nextBaseFont) {
                        require(targetFont !== nextTargetFont) {
                            "Base font has changed at $i but target font has not changed."
                        }
                        // Font transition point. push run and reset context.
                        fontRun.add(FontRun(start, i, baseFont, targetFont))
                        maxRunLength = max(maxRunLength, i - start)
                        baseFont = nextBaseFont
                        targetFont = nextTargetFont
                        start = i
                        require(FontInterpolator.canInterpolate(baseFont, targetFont)) {
                            "Cannot interpolate font at $start ($baseFont vs $targetFont)"
                        }
                    } else { // baseFont === nextBaseFont
                        require(targetFont === nextTargetFont) {
                            "Base font has not changed at $i but target font has changed."
                        }
                    }
                }
                fontRun.add(FontRun(start, glyphCount, baseFont, targetFont))
                maxRunLength = max(maxRunLength, glyphCount - start)
            }
            Line(glyphIds, baseX, baseY, targetX, targetY, fontRun)
        }

        // Update float array used for drawing.
        if (tmpPositionArray.size < maxRunLength * 2) {
            tmpPositionArray = FloatArray(maxRunLength * 2)
        }
    }

    // Draws single font run.
    private fun drawFontRun(c: Canvas, line: Line, run: FontRun, paint: Paint) {
        var arrayIndex = 0
        for (i in run.start until run.end) {
            tmpPositionArray[arrayIndex++] =
                    MathUtils.lerp(line.baseX[i], line.targetX[i], progress)
            tmpPositionArray[arrayIndex++] =
                    MathUtils.lerp(line.baseY[i], line.targetY[i], progress)
        }

        c.drawGlyphs(
                line.glyphIds,
                run.start,
                tmpPositionArray,
                0,
                run.length,
                fontInterpolator.lerp(run.baseFont, run.targetFont, progress),
                paint)
    }

    private fun updatePositionsAndFonts(
        layoutResult: List<PositionedGlyphs>,
        updateBase: Boolean
    ) {
        // Update target positions with newly calculated text layout.
        check(layoutResult.size == lines.size) {
            "The new layout result has different line count."
        }

        lines.zip(layoutResult) { line, newGlyphs ->
            require(newGlyphs.glyphCount() == line.glyphIds.size) {
                "The new layout has different glyph count."
            }

            line.fontRuns.forEach { run ->
                val newFont = newGlyphs.getFont(run.start)
                for (i in run.start until run.end) {
                    require(newGlyphs.getGlyphId(run.start) == line.glyphIds[run.start]) {
                        "The new layout has different glyph ID at ${run.start}"
                    }
                    require(newFont === newGlyphs.getFont(i)) {
                        "The new layout has different font run." +
                                " $newFont vs ${newGlyphs.getFont(i)} at $i"
                    }
                }

                // The passing base font and target font is already interpolatable, so just check
                // new font can be interpolatable with base font.
                require(FontInterpolator.canInterpolate(newFont, run.baseFont)) {
                    "New font cannot be interpolated with existing font. $newFont, ${run.baseFont}"
                }

                if (updateBase) {
                    run.baseFont = newFont
                } else {
                    run.targetFont = newFont
                }
            }

            if (updateBase) {
                for (i in line.baseX.indices) {
                    line.baseX[i] = newGlyphs.getGlyphX(i)
                    line.baseY[i] = newGlyphs.getGlyphY(i)
                }
            } else {
                for (i in line.baseX.indices) {
                    line.targetX[i] = newGlyphs.getGlyphX(i)
                    line.targetY[i] = newGlyphs.getGlyphY(i)
                }
            }
        }
    }

    // Linear interpolate the paint.
    private fun lerp(
        from: ArrayList<Paint>,
        to: ArrayList<Paint>,
        progress: Float,
        out: ArrayList<Paint>
    ) {
        out.clear()
        // Currently only font size & colors are interpolated.
        // TODO(172943390): Add other interpolation or support custom interpolator.
        for (index in from.indices) {
            val paint = Paint(from[index])
            paint.textSize = MathUtils.lerp(from[index].textSize, to[index].textSize, progress)
            paint.color = ColorUtils.blendARGB(from[index].color, to[index].color, progress)
            out.add(paint)
        }
    }

    // Shape the text and stores the result to out argument.
    private fun shapeText(layout: Layout, paints: ArrayList<Paint>): List<PositionedGlyphs> {
        val out = mutableListOf<PositionedGlyphs>()
        for (lineNo in 0 until layout.lineCount) { // Shape all lines.
            val lineStart = layout.getLineStart(lineNo)
            val count = layout.getLineEnd(lineNo) - lineStart
            out.add(TextRunShaper.shapeTextRun(
                    layout.text, // Styles are ignored.
                    lineStart, count, // shape range
                    lineStart, count, // shape context = shape range.
                    0f, 0f, // the layout offset. Not changed.
                    layout.getParagraphDirection(lineNo) == Layout.DIR_RIGHT_TO_LEFT,
                    paints[lineNo])) // Use given paint instead of layout's for style interpolation.
        }
        return out
    }
}

private fun Layout.getDrawOrigin(lineNo: Int) =
        if (getParagraphDirection(lineNo) == Layout.DIR_LEFT_TO_RIGHT) {
            getLineLeft(lineNo)
        } else {
            getLineRight(lineNo)
        }
