package nie.translator.rtranslator.tools;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Android 16 envelope haptics: a short, soft "translation ready" cue so the user does not have to
 * watch the screen during a face-to-face conversation. Falls back to a predefined tick.
 */
public final class Haptics {
    private Haptics() {}

    public static void translationReady(Context context) {
        try {
            VibratorManager vm = context.getSystemService(VibratorManager.class);
            if (vm == null) return;
            Vibrator v = vm.getDefaultVibrator();
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect effect;
            if (v.areEnvelopeEffectsSupported()) {
                effect = new VibrationEffect.BasicEnvelopeBuilder()
                        .setInitialSharpness(0.3f)
                        .addControlPoint(0.7f, 0.5f, 40)
                        .addControlPoint(0.0f, 0.3f, 90)
                        .build();
            } else {
                effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
            v.vibrate(effect);
        } catch (Throwable t) {
            // haptics are a nicety, never break translation
        }
    }
}
