package android.view;

import android.content.Context;

public class View {
    public static final int NO_ID = -1;
    private static int nextGeneratedId = 0x10000;
    private final Context context;
    private int id = NO_ID;
    ViewGroup parent;

    public View(Context context) {
        this.context = context;
    }

    public static int generateViewId() {
        return nextGeneratedId++;
    }

    public Context getContext() {
        return context;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public <T extends View> T findViewById(int id) {
        return findViewTraversal(id);
    }

    @SuppressWarnings("unchecked")
    <T extends View> T findViewTraversal(int id) {
        return id != NO_ID && id == this.id ? (T) this : null;
    }

    protected void onFinishInflate() {
    }

    public CharSequence getAccessibilityClassName() {
        return View.class.getName();
    }

    /** Test helper: what LayoutInflater does once a view and all its children are inflated. */
    public static void finishInflate(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                finishInflate(group.getChildAt(i));
            }
        }
        view.onFinishInflate();
    }
}
