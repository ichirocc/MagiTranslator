package android.view;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class ViewGroup extends View {
    public interface OnHierarchyChangeListener {
        void onChildViewAdded(View parent, View child);

        void onChildViewRemoved(View parent, View child);
    }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;
        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }
    }

    private final List<View> children = new ArrayList<>();
    private OnHierarchyChangeListener hierarchyListener;

    public ViewGroup(Context context) {
        super(context);
    }

    public void setOnHierarchyChangeListener(OnHierarchyChangeListener listener) {
        hierarchyListener = listener;
    }

    public void addView(View child) {
        addView(child, -1, null);
    }

    public void addView(View child, int index, LayoutParams params) {
        if (index < 0) {
            children.add(child);
        } else {
            children.add(index, child);
        }
        child.parent = this;
        if (hierarchyListener != null) {
            hierarchyListener.onChildViewAdded(this, child);
        }
    }

    public int getChildCount() {
        return children.size();
    }

    public View getChildAt(int index) {
        return children.get(index);
    }

    @Override
    <T extends View> T findViewTraversal(int id) {
        T self = super.findViewTraversal(id);
        if (self != null) {
            return self;
        }
        for (View child : children) {
            T found = child.findViewTraversal(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
