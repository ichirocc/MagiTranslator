package android.widget;

import android.content.Context;
import android.view.View;

public class CompoundButton extends View {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton buttonView, boolean isChecked);
    }

    private boolean checked;
    private OnCheckedChangeListener listener;

    public CompoundButton(Context context) {
        super(context);
    }

    public boolean isChecked() {
        return checked;
    }

    /** As in the framework: the listener is told only of a change. */
    public void setChecked(boolean checked) {
        if (this.checked != checked) {
            this.checked = checked;
            if (listener != null) {
                listener.onCheckedChanged(this, checked);
            }
        }
    }

    public void toggle() {
        setChecked(!checked);
    }

    public boolean performClick() {
        toggle();
        return true;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }
}
