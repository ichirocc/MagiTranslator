package nie.translator.rtranslator.tools;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._walkie_talkie_mode._walkie_talkie.WalkieTalkieService;

/** Ends the app: stops the voice services, closes every activity of the task and ends the process,
 *  so the loaded models (several GB) are released at once. */
public final class AppExit {
    private AppExit() {}

    public static void exit(Activity activity) {
        try { activity.stopService(new Intent(activity, ConversationService.class)); } catch (Exception ignored) {}
        try { activity.stopService(new Intent(activity, WalkieTalkieService.class)); } catch (Exception ignored) {}
        activity.finishAffinity();
        activity.finishAndRemoveTask();
        // give the services a moment to run onDestroy (cancels translations, closes the mic) before the process ends
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 500);
    }
}
