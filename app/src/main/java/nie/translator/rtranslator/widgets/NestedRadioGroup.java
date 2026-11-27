/*
 * Copyright 2026 MagiTranslator contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.Nullable;

/**
 * A radio group whose RadioButtons may sit anywhere inside it (in nested layouts), unlike
 * android.widget.RadioGroup, which only handles direct children. Same behaviour as RadioGroup otherwise:
 * checking one button unchecks the others, {@link #check(int)} and taps notify the listener once.
 * Unlike RadioGroupPlus, a button being unchecked (state restore) is not taken for a selection.
 * The group sets each button's OnCheckedChangeListener: listen to the group, not to the buttons.
 * Replaces the RadioGroupPlus library (2016), which depended on the legacy support library and was the
 * only reason the build still needed Jetifier.
 */
public class NestedRadioGroup extends LinearLayout {
    public interface OnCheckedChangeListener {
        void onCheckedChanged(NestedRadioGroup group, int checkedId);
    }

    private int checkedId = View.NO_ID;
    private boolean protectFromCheckedChange = false;
    @Nullable
    private OnCheckedChangeListener listener;

    private final CompoundButton.OnCheckedChangeListener childListener = (button, isChecked) -> {
        if (protectFromCheckedChange || !isChecked) {
            return;
        }
        int id = button.getId();
        if (checkedId != View.NO_ID && checkedId != id) {
            setCheckedStateForView(checkedId, false);
        }
        setCheckedId(id);
    };

    public NestedRadioGroup(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    public NestedRadioGroup(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        attach(this);
    }

    private void attach(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof RadioButton) {
                RadioButton button = (RadioButton) child;
                if (button.getId() == View.NO_ID) {
                    button.setId(View.generateViewId());
                }
                button.setOnCheckedChangeListener(childListener);   // the group owns the buttons' listener (as RadioGroupPlus did)
                if (button.isChecked()) {   // checked in the layout: the last one wins, as in RadioGroup
                    if (checkedId != View.NO_ID) {
                        setCheckedStateForView(checkedId, false);
                    }
                    checkedId = button.getId();
                }
            } else if (child instanceof ViewGroup) {
                attach((ViewGroup) child);
            }
        }
    }

    /** Checks the button with this id (View.NO_ID clears the selection); the listener is told of a change. */
    public void check(int id) {
        if (id != View.NO_ID && id == checkedId) {
            return;
        }
        if (checkedId != View.NO_ID) {
            setCheckedStateForView(checkedId, false);
        }
        if (id != View.NO_ID) {
            setCheckedStateForView(id, true);
        }
        setCheckedId(id);
    }

    public int getCheckedRadioButtonId() {
        return checkedId;
    }

    public void setOnCheckedChangeListener(@Nullable OnCheckedChangeListener listener) {
        this.listener = listener;
    }

    private void setCheckedId(int id) {   // the listener hears every check (RadioGroup and RadioGroupPlus do the same)
        checkedId = id;
        if (listener != null) {
            listener.onCheckedChanged(this, id);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {   // TalkBack announces a radio group, as for RadioGroup
        return RadioGroup.class.getName();
    }

    private void setCheckedStateForView(int id, boolean checked) {
        View view = findViewById(id);
        if (view instanceof RadioButton) {
            protectFromCheckedChange = true;
            ((RadioButton) view).setChecked(checked);
            protectFromCheckedChange = false;
        }
    }
}
