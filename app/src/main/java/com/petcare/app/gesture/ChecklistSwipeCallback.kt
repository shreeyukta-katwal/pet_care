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
import com.petcare.app.ui.checklist.ChecklistAdapter

/**
 * # Gesture 1 & 2: Bidirectional Swipe Callback for Care Checklist
 *
 * ## Technical Operation:
 * - Extends [ItemTouchHelper.SimpleCallback] listening for horizontal swipes.
 * - **Directional Actions**:
 *   - **Swipe LEFT (`ItemTouchHelper.LEFT`)**: Deletes the task immediately and shows an
 *     Undo [com.google.android.material.snackbar.Snackbar]. No blocking dialog is required
 *     because the action is safely recoverable. Reminders are cancelled and restored on undo.
 *   - **Swipe RIGHT (`ItemTouchHelper.RIGHT`)**: Toggles the task's completion status for today,
 *     persists the new state to Room database, and immediately bounces/snaps the row back into place
 *     using [RecyclerView.Adapter.notifyItemChanged] while presenting an Undo action.
 * - **Conflict Avoidance**:
 *   - Inspects [RecyclerView.ViewHolder.getItemViewType] in [getSwipeDirs]. Category header rows
 *     return `0` (swiping completely disabled on headers).
 *   - Checks [isSelectionModeActive]: when multi-selection ActionMode is active, all swipe gestures
 *     are suppressed (`getSwipeDirs = 0`) to prevent accidental deletion while tapping items.
 * - **Custom Canvas Rendering (`onChildDraw`)**:
 *   - Swiping Left: Paints a vivid red ([deleteColor]) background with a centered white trash icon.
 *   - Swiping Right: Paints a vibrant green ([doneColor]) background with a centered white check icon.
 * - **Haptic Feedback**:
 *   - Triggers tactile [HapticFeedbackConstants.CONFIRM] upon swipe completion.
 *
 * ## Usability & UX Benefit:
 * - Drastically accelerates routine daily logging: a pet owner can complete or delete routines
 *   with one fluid thumb gesture without tapping tiny checkboxes or opening overflow sub-menus.
 * - Non-gesture alternatives: Checkbox clicking, item overflow menus, and toolbar ActionMode
 *   ensure 100% feature accessibility.
 */
class ChecklistSwipeCallback(
    context: Context,
    private val isSelectionModeActive: () -> Boolean,
    private val onSwipeDelete: (position: Int) -> Unit,
    private val onSwipeToggleDone: (position: Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val deleteColor = Color.parseColor("#D32F2F") // Material Red
    private val doneColor = Color.parseColor("#2E7D32")   // Material Green

    private val deleteIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_delete)
    private val doneIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_check)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        // Suppress swipes during contextual selection mode
        if (isSelectionModeActive()) {
            return 0
        }

        // Suppress swipes on category header rows
        if (viewHolder.itemViewType != ChecklistAdapter.VIEW_TYPE_TASK) {
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

        if (direction == ItemTouchHelper.LEFT) {
            onSwipeDelete(position)
        } else if (direction == ItemTouchHelper.RIGHT) {
            onSwipeToggleDone(position)
        }
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
            // Swiping LEFT -> Delete (Red background + Trash icon)
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
        } else if (dX > 0) {
            // Swiping RIGHT -> Mark Done (Green background + Checkmark icon)
            backgroundPaint.color = doneColor
            val backgroundRect = RectF(
                itemView.left.toFloat(),
                itemView.top.toFloat(),
                itemView.left.toFloat() + dX,
                itemView.bottom.toFloat()
            )
            c.drawRect(backgroundRect, backgroundPaint)

            doneIcon?.let { icon ->
                val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                val iconTop = itemView.top + iconMargin
                val iconBottom = iconTop + icon.intrinsicHeight
                val iconLeft = itemView.left + iconMargin
                val iconRight = iconLeft + icon.intrinsicWidth
                icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                icon.draw(c)
            }
        }

        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
