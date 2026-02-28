package com.kitchenboard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.viewpager2.widget.ViewPager2;

/**
 * Layout that wraps a scrollable component (e.g. ViewPager2) and ensures a
 * nested-scroll parent (another ViewPager2) does not intercept touch events
 * when the child can handle them.
 *
 * <p><strong>Concept – preventing nested ViewPager2 conflicts:</strong></p>
 * <p>When an inner {@link ViewPager2} lives inside an outer {@link ViewPager2}
 * (both oriented horizontally), every horizontal swipe reaches the outer pager
 * first via {@code onInterceptTouchEvent}. Without intervention the outer pager
 * would consume the gesture and page away, making the inner pager impossible to
 * swipe. The same problem arises if the inner pager is vertical inside a
 * vertical outer pager.</p>
 *
 * <p>The fix uses {@link android.view.ViewParent#requestDisallowInterceptTouchEvent}:</p>
 * <ul>
 *   <li>On {@code ACTION_DOWN} the host immediately tells the parent chain
 *       <em>not</em> to intercept, giving the inner view first access to the
 *       gesture.</li>
 *   <li>On {@code ACTION_MOVE}, once the dominant gesture direction is clear,
 *       the host either keeps the "disallow" flag (inner pager handles it) or
 *       clears it (outer pager may intercept) based on whether the child can
 *       still scroll in that direction.</li>
 *   <li>Perpendicular gestures (e.g. vertical swipe inside a horizontal pager)
 *       are immediately released to the parent chain so they are not swallowed.</li>
 * </ul>
 *
 * <p><strong>Important:</strong> the {@link ViewPager2} (or other scrollable
 * child) must be the <em>first</em> direct child of this host so that
 * {@link View#canScrollHorizontally} / {@link View#canScrollVertically} are
 * evaluated on the pager itself and not on a non-scrolling wrapper.</p>
 *
 * <p>Based on the official AndroidX ViewPager2 sample:<br>
 * https://github.com/android/views-widgets-samples/blob/master/ViewPager2/app/src/main/java/androidx/viewpager2/integration/testapp/NestedScrollableHost.kt</p>
 */
public class NestedScrollableHost extends FrameLayout {

    private int touchSlop;
    private float initialX;
    private float initialY;

    public NestedScrollableHost(Context context) {
        super(context);
        init(context);
    }

    public NestedScrollableHost(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    private ViewPager2 getParentViewPager() {
        View v = (View) getParent();
        while (v != null && !(v instanceof ViewPager2)) {
            v = (View) v.getParent();
        }
        return (ViewPager2) v;
    }

    private View getChild() {
        return getChildCount() > 0 ? getChildAt(0) : null;
    }

    private boolean canChildScroll(int orientation, float delta) {
        int direction = (int) -Math.signum(delta);
        View child = getChild();
        if (child == null) return false;
        if (orientation == ViewPager2.ORIENTATION_HORIZONTAL) {
            return child.canScrollHorizontally(direction);
        } else {
            return child.canScrollVertically(direction);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        handleInterceptTouchEvent(e);
        return super.onInterceptTouchEvent(e);
    }

    private void handleInterceptTouchEvent(MotionEvent e) {
        ViewPager2 parentVp = getParentViewPager();
        if (parentVp == null) return;

        int orientation = parentVp.getOrientation();

        // Early exit if the child cannot scroll in the same direction as the parent VP2.
        if (!canChildScroll(orientation, -1f) && !canChildScroll(orientation, 1f)) {
            return;
        }

        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            initialX = e.getX();
            initialY = e.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
            float dx = e.getX() - initialX;
            float dy = e.getY() - initialY;
            boolean isVpHorizontal = orientation == ViewPager2.ORIENTATION_HORIZONTAL;

            float scaledDx = Math.abs(dx) * (isVpHorizontal ? 0.5f : 1.0f);
            float scaledDy = Math.abs(dy) * (isVpHorizontal ? 1.0f : 0.5f);

            if (scaledDx > touchSlop || scaledDy > touchSlop) {
                if (isVpHorizontal == (scaledDy > scaledDx)) {
                    // Gesture is perpendicular to the VP2 — let the parent handle it.
                    getParent().requestDisallowInterceptTouchEvent(false);
                } else {
                    // Gesture is in the same direction as the VP2 — keep it if child can scroll.
                    if (canChildScroll(orientation, isVpHorizontal ? dx : dy)) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
            }
        }
    }
}
