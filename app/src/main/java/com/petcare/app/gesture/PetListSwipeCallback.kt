package com.petcare.app.gesture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.petcare.app.R

/**
 * # Gesture 1: Swipe Left to Delete for Pet Cards
 *
 * ## Technical Operation:
 * - Extends [ItemTouchHelper.SimpleCallback] listening for leftward swipe gestures on pet cards.
 * - Suppressed during contextual multi-selection mode (`isSelectionModeActive() == true`).
 * - Emits tactile [HapticFeedbackConstants.CONFIRM] haptic feedback upon swipe completion.
 * - Renders a vivid red background canvas with a centered trash icon during swipe translation.
 * - Immediately triggers [onSwipeDelete] and displays an undoable [com.google.android.material.snackbar.Snackbar].
 *
 * ## Usability & UX Benefit:
 * - Streamlines pet profile management with quick gestural deletion while ensuring safety
 *   through instantaneous Undo recovery.
 */
class PetListSwipeCallback(
    context: Context,
    private val isSelectionModeActive: () -> Boolean,
    private val onSwipeDelete: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val deleteColor = Color.parseColor("#D32F2F") // Material Red
    private val deleteIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_delete)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (isSelectionModeActive()) {
            return 0
        }
        return super.getSwipeDirs(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return

        viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onSwipeDelete(position)
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView

        if (dX < 0) {
            backgroundPaint.color = deleteColor
            val backgroundRect = RectF(
                itemView.right.toFloat() + dX,
                itemView.top.toFloat(),
                itemView.right.toFloat(),
                itemView.bottom.toFloat()
            )
            c.drawRect(backgroundRect, backgroundPaint)

            deleteIcon?.let { icon ->
                val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + icon.intrinsicHeight
                val iconRight = itemView.right - iconMargin
                val iconLeft = iconRight - icon.intrinsicWidth
                icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                icon.draw(c)
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
