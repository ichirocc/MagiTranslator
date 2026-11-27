package android.widget;

import android.content.Context;

public class RadioButton extends CompoundButton {
    public RadioButton(Context context) {
        super(context);
    }

    /** As in the framework: a tap checks a radio button, it never unchecks it. */
    @Override
    public void toggle() {
        if (!isChecked()) {
            super.toggle();
        }
    }
}
