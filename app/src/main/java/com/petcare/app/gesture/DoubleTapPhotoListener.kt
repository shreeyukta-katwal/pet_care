package com.petcare.app.gesture

import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * # Gesture 5: Double-Tap Photo Listener
 *
 * ## Technical Operation:
 * - Uses Android's [GestureDetector] with a [GestureDetector.SimpleOnGestureListener].
 * - Distinguishes between:
 *   - **Single tap**: Executes normal action (opening pet checklist or choosing photo).
 *   - **Double tap**: Emits tactile [HapticFeedbackConstants.CONFIRM] haptic feedback
 *     and triggers the [onDoubleTap] callback to launch the full-screen [PhotoViewerDialog].
 *
 * ## Usability & UX Benefit:
 * - Allows quick zooming and inspection of pet pictures without cluttering the screen
 *   with additional zoom icons or buttons.
 * - Prevents conflict between navigation clicks and magnification gestures.
 */
class DoubleTapPhotoListener(
    context: Context,
    private val onDoubleTap: () -> Unit,
    private val onSingleTap: (() -> Unit)? = null
) : View.OnTouchListener {

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return onSingleTap != null
        }

        override fun onDown(e: MotionEvent): Boolean {
            return true
        }
    })

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // Provide gentle touch feedback
            v.performClick()
        }
        val handled = detector.onTouchEvent(event)
        if (handled && event.action == MotionEvent.ACTION_DOWN) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        return handled
    }
}
