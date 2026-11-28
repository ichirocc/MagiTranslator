package nie.translator.rtranslator.voice_translation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.tools.gui.messages.GuiMessage;

public class TTSEngine {
    public final static int DELAY_AFTER_TTS_INTERRUPTION_MS = 1500;
    private final ArrayDeque<Pair<GuiMessage, CustomLocale>> messageQueue = new ArrayDeque<>();
    @Nullable
    private Pair<GuiMessage, CustomLocale> executingMessage = null;
    private boolean speaking = false;
    @Nullable
    private final TTS tts;
    private boolean pause = false;
    private boolean ready = false;   //set on the main thread once the TTS engine is initialized
    private Context context;
    @Nullable
    private TTSEngineListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());


    public TTSEngine(Context context, @Nullable TTSEngineListener listener) {
        this.context = context;
        this.tts = new TTS(context, new TTS.InitListener() {  // tts initialization (to be improved, automatic package installation)
            @Override
            public void onInit() {
                //messages queued before the engine was ready were kept: read them now
                handler.post(() -> {
                    ready = true;
                    if (!pause && !speaking && !messageQueue.isEmpty()) speak();
                });
                if(TTSEngine.this.tts != null) {
                    TTSEngine.this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onDone(String utteranceId) {
                            if (listener != null) {
                                boolean isLast = messageQueue.isEmpty();
                                listener.onUtteranceFinished(getExecutingMessage(), isLast);
                                executingMessage = null;
                            }
                            speak();
                        }

                        @SuppressWarnings("deprecation")  // abstract in UtteranceProgressListener: must be overridden
                        @Override
                        public void onError(String utteranceId) {
                            stop();
                        }

                        @Override
                        public void onStart(String utteranceId) {

                        }

                        @Override
                        public void onStop(String utteranceId, boolean interrupted) {
                            super.onStop(utteranceId, interrupted);

                            /*
                             * Usually, this interruption is triggered by TalkBack TTS.
                             * In this case, we put our stopped message back in the TTS queue, to resume the normal
                             * execution after TalkBack is done speaking.
                             * The insertion back in the TTS queue, is done after a delay, because often, TalkBack says multiple things,
                             * and inserts each phrase in the TTS after it has been spoken (not all at once).
                             * If we don't do the delay, we risk to put the TTS phrase in the middle of the TTS queue,
                             * giving a buggy feeling to the user, which will hear TalkBack, then out phrase, then TalkBack again with the second part.
                             */

                            if (pause) return; // Ignore if we stopped it manually via the pause() button

                            if (interrupted) {
                                Log.i("tts_engine", "TTS interrupted by TalkBack/System");

                                // Unstick the queue
                                speaking = false;

                                // Put the interrupted message back at the VERY FRONT of our custom queue
                                if (executingMessage != null) {
                                    messageQueue.addFirst(executingMessage);
                                    executingMessage = null;
                                }

                                // Trigger speak() again after a delay (so TalkBack can queue all its hints).
                                // Because TalkBack is currently speaking, QUEUE_ADD will gracefully
                                // put this message in the TTS engine's queue directly behind TalkBack.
                                handler.removeCallbacksAndMessages(null);  // Clear any pending resumes to avoid duplicates
                                handler.postDelayed(() -> {
                                    tts.stop();
                                    speak();
                                }, DELAY_AFTER_TTS_INTERRUPTION_MS);
                            } else {
                                stop();
                            }
                        }
                    });
                }
            }

            @Override
            public void onError(int reason) {
                //tell the owner (the service sets isTTSError / mutes audio) instead of failing silently
                handler.post(() -> { if (listener != null) listener.onInitError(reason); });
            }
        });
        this.listener = listener;

    }


    public boolean speak(GuiMessage message, CustomLocale language){
        messageQueue.addLast(new Pair<>(message, language));
        if (!messageQueue.isEmpty() && !speaking && !pause) {
            return speak();
        }
        return false;
    }

    private boolean speak(){
        return speak(TextToSpeech.QUEUE_ADD);
    }

    private boolean speak(int queueMode){
        if (pause) return false;
        if (!ready) return false;   //before onInit every message would fail to speak and be dropped: keep it queued
        boolean wasSpeaking = speaking;
        speaking = true;
        //a loop, not recursion: a long queue of messages in a language the TTS cannot speak is skipped one by one
        //without growing the stack
        Pair<GuiMessage, CustomLocale> pair;
        while ((pair = messageQueue.pollFirst()) != null && tts != null) {
            if(listener != null) listener.onUtteranceStarting(pair.first, !wasSpeaking);
            executingMessage = pair;
            boolean ok = true;
            if (tts.getVoice() == null || !pair.second.equals(new CustomLocale(tts.getVoice().getLocale()))) {
                //LANG_MISSING_DATA / LANG_NOT_SUPPORTED: do not read the text in the previous voice
                ok = tts.setLanguage(pair.second, context) >= TextToSpeech.LANG_AVAILABLE;
            }
            if (ok) ok = tts.speak(pair.first.getMessage().getText(), queueMode, null, String.valueOf(pair.first.getMessageID())) == TextToSpeech.SUCCESS;
            if (ok) return true;
            //skip this message as if it had been read, so the queue and the mic restart go on
            Log.w("tts_engine", "cannot speak in " + pair.second.getCode());
            executingMessage = null;
            if (listener != null) listener.onUtteranceFinished(pair.first, messageQueue.isEmpty());
            if (pause) return false;   //the listener may have paused the engine
        }
        speaking = false;
        executingMessage = null;
        return false;
    }

    public void pause() {
        if(!pause) {
            Log.i("tts_engine", "tts engine paused");
            handler.removeCallbacksAndMessages(null);
            if(tts != null) tts.stop();
            executingMessage = null;
            //nothing is being read now: the first message after resume() must count as the first (onUtteranceStarting
            //first=true turns the mic off during TTS; it stayed false and the mic could hear the TTS)
            speaking = false;
            pause = true;
        }
    }

    public void resume() {
        if(pause){
            Log.i("tts_engine", "tts engine resumed");
            handler.removeCallbacksAndMessages(null);
            pause = false;
            speak();
        }
    }

    public boolean isPaused(){
        return pause;
    }

    public boolean isEmpty(){
        return messageQueue.isEmpty();
    }

    public void stop(){
        if(tts != null) tts.stop();
        boolean notify = listener != null && (executingMessage != null || !messageQueue.isEmpty());
        GuiMessage oldExecutingMessage = getExecutingMessage();
        executingMessage = null;
        messageQueue.clear();
        if(notify){
            listener.onUtteranceFinished(oldExecutingMessage, true);
        }
        speaking = false;
    }

    @Nullable
    public GuiMessage getExecutingMessage() {
        if(executingMessage != null) {
            return executingMessage.first;
        }
        return null;
    }

    @Nullable
    public TTS getTts() {
        return tts;
    }

    public void shutdown() {
        if(tts != null) tts.shutdown();
    }

    public boolean isActive() {
        if(tts != null) return tts.isActive();
        return false;
    }

    public static abstract class TTSEngineListener {
        abstract void onUtteranceStarting(GuiMessage message, boolean first);
        abstract void onUtteranceFinished(@Nullable GuiMessage message, boolean last);
        abstract void onInitError(int reason);
    }
}
