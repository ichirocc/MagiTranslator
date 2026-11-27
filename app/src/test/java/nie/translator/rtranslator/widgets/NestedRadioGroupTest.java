package nie.translator.rtranslator.widgets;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;

import java.util.ArrayList;
import java.util.List;

/**
 * NestedRadioGroup on a plain JVM (Android stand-ins in app/src/test/stubs), with the layout of the model
 * manager: group > LinearLayout > ConstraintLayout > RadioButton, three times. Behaviour expected = that of
 * RadioGroupPlus, which it replaces (same listener calls for check() and taps).
 * Run: see .github/workflows/release-build.yml (pure-Java tests).
 */
public class NestedRadioGroupTest {
    static final int MOZILLA = 101, HY = 102, MADLAD = 103;
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static final Context CTX = new Context();
    static NestedRadioGroup group;
    static RadioButton mozilla, hy, madlad;
    static List<Integer> events;

    /** As LayoutInflater builds it: children are added before their parent is added, then onFinishInflate. */
    static void inflate(boolean mozillaChecked, boolean madladChecked) {
        group = new NestedRadioGroup(CTX, null);
        LinearLayout column = new LinearLayout(CTX);
        column.setOrientation(LinearLayout.VERTICAL);
        mozilla = button(MOZILLA, mozillaChecked);
        hy = button(HY, false);
        madlad = button(MADLAD, madladChecked);
        for (RadioButton b : new RadioButton[]{mozilla, hy, madlad}) {
            ViewGroup row = new ViewGroup(CTX);   // the ConstraintLayout around each button
            row.addView(b);
            column.addView(row);
        }
        group.addView(column);
        View.finishInflate(group);
        events = new ArrayList<>();
        group.setOnCheckedChangeListener((g, id) -> {
            check(g == group, "listener gets the group");
            events.add(id);
        });
    }

    static RadioButton button(int id, boolean checked) {
        RadioButton b = new RadioButton(CTX);
        b.setId(id);
        b.setChecked(checked);
        return b;
    }

    static String state() {
        return (mozilla.isChecked() ? "M" : "-") + (hy.isChecked() ? "H" : "-") + (madlad.isChecked() ? "D" : "-")
                + " id=" + group.getCheckedRadioButtonId();
    }

    public static void main(String[] args) {
        inflate(false, false);
        check(group.getCheckedRadioButtonId() == View.NO_ID, "nothing checked after inflating");
        check(group.getOrientation() == LinearLayout.VERTICAL, "vertical, as RadioGroupPlus");
        check("android.widget.RadioGroup".contentEquals(group.getAccessibilityClassName()), "TalkBack: radio group");

        // restoreGuiPreferenceState(): check() of the saved model
        group.check(MADLAD);
        check(state().equals("--D id=" + MADLAD), "check(): only that button checked (" + state() + ")");
        check(events.equals(List.of(MADLAD)), "check(): listener once " + events);

        // user taps another model (nested two levels below the group)
        hy.performClick();
        check(state().equals("-H- id=" + HY), "tap: previous unchecked, tapped checked (" + state() + ")");
        check(events.equals(List.of(MADLAD, HY)), "tap: listener once " + events);

        hy.performClick();
        check(state().equals("-H- id=" + HY) && events.size() == 2, "tap on the checked button: nothing happens");

        group.check(HY);
        check(events.size() == 2, "check() of the checked id: no listener call (as RadioGroupPlus)");

        mozilla.performClick();
        check(state().equals("M-- id=" + MOZILLA) && events.equals(List.of(MADLAD, HY, MOZILLA)), "tap Mozilla " + state());

        group.check(MADLAD);
        check(state().equals("--D id=" + MADLAD) && events.get(events.size() - 1) == MADLAD, "check() after taps " + state());

        group.check(View.NO_ID);
        check(state().equals("--- id=-1") && events.get(events.size() - 1) == View.NO_ID, "check(NO_ID) clears " + state());
        group.check(View.NO_ID);
        check(events.get(events.size() - 1) == View.NO_ID && events.size() == 6, "check(NO_ID) again: listener (as RadioGroupPlus)");

        // the framework restores a button's state: an uncheck is not taken for a selection
        group.check(HY);
        int before = events.size();
        hy.setChecked(false);
        check(events.size() == before, "uncheck from outside: no selection event");

        // checked in the layout: the last checked button wins, the others are unchecked
        inflate(true, true);
        check(state().equals("--D id=" + MADLAD), "two checked in the layout: the last wins (" + state() + ")");
        hy.performClick();
        check(state().equals("-H- id=" + HY), "then a tap works as usual (" + state() + ")");

        // a button without an id gets one
        NestedRadioGroup g = new NestedRadioGroup(CTX);
        RadioButton noId = new RadioButton(CTX);
        g.addView(noId);
        View.finishInflate(g);
        noId.performClick();
        check(noId.getId() != View.NO_ID && g.getCheckedRadioButtonId() == noId.getId(), "button without id gets one");

        System.out.println(failures == 0 ? "NestedRadioGroupTest: all passed" : "NestedRadioGroupTest: " + failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
