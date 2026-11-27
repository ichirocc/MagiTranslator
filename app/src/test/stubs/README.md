Minimal stand-ins for a few Android framework classes (View, ViewGroup, LinearLayout, CompoundButton, RadioButton,
RadioGroup), so that view logic without drawing (widgets/NestedRadioGroup) can be tested on a plain JVM in CI
(`.github/workflows/release-build.yml`, pure-Java tests). They model only what that logic uses: ids,
findViewById, the child list with hierarchy listeners, and the checked state with its listener (a change
notifies once, the same state does not; a tap on a RadioButton only checks it). Never on the app's classpath.
