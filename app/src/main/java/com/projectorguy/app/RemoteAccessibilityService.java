package com.projectorguy.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the Remote Control page's D-pad/OK buttons move real input focus and
 * click, in whatever app is currently on screen — the one path left for
 * this after AndroidBridge.sendProjectorCommand's class comment ruled out
 * both root and a vendor SDK command for it. This works because assistive
 * technology is specifically allowed to move focus (ACTION_FOCUS) and
 * activate (ACTION_CLICK) via the accessibility node tree without holding
 * INJECT_EVENTS — that's the actual mechanism real "phone as TV remote"
 * apps use, not a workaround.
 *
 * Requires the user to manually enable "Projector Guy" once under
 * Settings > Accessibility — the system will not grant this silently, by
 * design. Until then isConnected() is false and moveFocus()/
 * activateFocused() just report failure.
 *
 * The "nearest focusable node in a direction" search below is a simplified
 * approximation of Android's internal FocusFinder algorithm, since that
 * class only operates on this process's own Views, not another app's
 * accessibility node tree. It works well for standard Android
 * layouts/TV apps; a screen with no real accessibility tree (e.g. a
 * custom GL/Canvas-drawn UI that exposes no nodes at all) has nothing for
 * this to find, and moveFocus() will honestly report failure rather than
 * silently doing nothing.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static final String TAG = "RemoteA11yService";

    static final int DIRECTION_UP = 1;
    static final int DIRECTION_DOWN = 2;
    static final int DIRECTION_LEFT = 3;
    static final int DIRECTION_RIGHT = 4;

    private static volatile RemoteAccessibilityService instance;

    static boolean isConnected() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op — commands are pulled on demand from sendProjectorCommand,
        // this service doesn't need to react to anything itself.
    }

    @Override
    public void onInterrupt() {
    }

    static boolean moveFocus(int direction) {
        RemoteAccessibilityService svc = instance;
        return svc != null && svc.doMoveFocus(direction);
    }

    static boolean activateFocused() {
        RemoteAccessibilityService svc = instance;
        return svc != null && svc.doActivateFocused();
    }

    private boolean doMoveFocus(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            Rect fromRect = new Rect();
            if (current != null) {
                current.getBoundsInScreen(fromRect);
            }
            // No node focused yet — treat the screen's top-left corner as the
            // origin so the first press picks whatever's nearest overall.

            List<AccessibilityNodeInfo> candidates = new ArrayList<>();
            collectFocusable(root, candidates);

            AccessibilityNodeInfo best = null;
            long bestScore = Long.MAX_VALUE;
            Rect candidateRect = new Rect();
            for (AccessibilityNodeInfo node : candidates) {
                if (node.equals(current)) continue;
                node.getBoundsInScreen(candidateRect);
                long score = directionalScore(fromRect, candidateRect, direction);
                if (score >= 0 && score < bestScore) {
                    bestScore = score;
                    best = node;
                }
            }

            if (best == null) return false;
            boolean ok = best.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!ok) {
                // Some views only accept an accessibility-focus ring, not real input focus.
                ok = best.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "moveFocus(" + direction + ") failed: " + e);
            return false;
        }
    }

    private boolean doActivateFocused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return false;
            return focused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception e) {
            Log.e(TAG, "activateFocused failed: " + e);
            return false;
        }
    }

    private static void collectFocusable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null || !node.isVisibleToUser()) return;
        if (node.isFocusable() || node.isClickable()) out.add(node);
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectFocusable(node.getChild(i), out);
        }
    }

    /**
     * Lower is better; -1 means "not in that direction at all". Candidate
     * must lie strictly past the source's centre on the requested axis;
     * ranked by distance on that axis plus a heavier penalty for drifting
     * off-axis, so pressing "down" prefers something roughly below rather
     * than far to the side.
     */
    private static long directionalScore(Rect from, Rect to, int direction) {
        long dx = to.centerX() - from.centerX();
        long dy = to.centerY() - from.centerY();
        long primary;
        long secondary;
        switch (direction) {
            case DIRECTION_UP:
                primary = -dy;
                secondary = Math.abs(dx);
                break;
            case DIRECTION_DOWN:
                primary = dy;
                secondary = Math.abs(dx);
                break;
            case DIRECTION_LEFT:
                primary = -dx;
                secondary = Math.abs(dy);
                break;
            case DIRECTION_RIGHT:
                primary = dx;
                secondary = Math.abs(dy);
                break;
            default:
                return -1;
        }
        if (primary <= 0) return -1;
        return primary * primary + secondary * secondary * 4;
    }
}
