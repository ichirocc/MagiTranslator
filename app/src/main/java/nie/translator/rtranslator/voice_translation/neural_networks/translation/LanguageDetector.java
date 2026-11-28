package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.FileTools;
import nie.translator.rtranslator.tools.TextTools;

public class LanguageDetector {
    // Load the C++ library
    static {
        System.loadLibrary("fasttext-lib");
    }


    private static final String TAG = "LanguageDetector";
    private static final String MODEL_FILE_NAME = "fasttext.ftz";
    private static final int MIN_WORDS = 7;

    private long nativePtr = 0;
    private Context context;
    private LanguageResourcesManager languageResourcesManager;
    private static final Map<String, String> specialWordsLanguages = new HashMap<>();
    static {
        specialWordsLanguages.put("ciao", "ita");
    }


    /**
     * Initializes the detector. Call this from a background thread during app startup.
     */
    public void initialize(Context context, LanguageResourcesManager languageResourcesManager) throws IOException {
        //we eventually transfer the fasttext model file from the assets to the internal memory (because fasttext can open the model only via a path to internal or external memory)
        File outFileFastText = new File(context.getFilesDir(), "fasttext.ftz");
        if(!outFileFastText.exists()) {
            FileTools.copyAssetToInternalMemory(context, "fasttext.ftz");
        }

        File modelFile = new File(context.getFilesDir(), MODEL_FILE_NAME);

        this.context = context;
        this.languageResourcesManager = languageResourcesManager;
        this.nativePtr = initFastText();

        loadModel(nativePtr, modelFile.getAbsolutePath());
    }

    /**
     * Predicts the language of a given text string.
     */
    public void detectLanguage(String text, double confidenceThreshold, CustomLocale[] languages, @NonNull DetectLanguageListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (nativePtr == 0) {
                        Log.e(TAG, "Model not initialized.");
                        listener.onSuccess("und");
                        return;
                    }
                    String normalizedText = TextTools.normalizeTextForLid(text);
                    int wordsCount = TextTools.countWords(normalizedText);
                    if (wordsCount < MIN_WORDS) {
                        if(TextTools.countWords(normalizedText) == 1){
                            //we use specialWordsLanguages mapping
                            String langCode = identifySpecialWordLanguage(normalizedText);
                            if(!langCode.equals("und")){
                                for (CustomLocale lang: languages) {
                                    if(lang.getISO3Language().equals(langCode)){
                                        listener.onSuccess(langCode);
                                        return;
                                    }
                                }
                            }
                            //we use dictionaries for detection
                            for (CustomLocale language: languages) {
                                if(DictionaryTranslator.containsWord(normalizedText, language)){
                                    listener.onSuccess(language.getISO3Language());
                                    return;
                                }
                            }
                        }
                        // we use Lingua for detection
                        String langCode = languageResourcesManager.detectLanguageWithLingua(text);
                        if(!langCode.equals("und") && isSelected(langCode, languages)){
                            listener.onSuccess(langCode);
                            return;
                        }
                    }
                    //FastText for every length: it was skipped from 2 words up, so a long utterance (7+ words, where
                    //FastText is most reliable) always came back "und"
                    long time = System.currentTimeMillis();
                    String langCode = predictLanguage(nativePtr, text, confidenceThreshold);
                    android.util.Log.i("lid_performance", "FastText language identification done in: " + (System.currentTimeMillis() - time) + "ms");
                    //only one of the selected languages counts: a third language would be taken as the other side
                    listener.onSuccess(langCode != null && isSelected(langCode, languages) ? langCode : "und");

                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e(TAG, "Failed to predict language", e);
                    listener.onSuccess("und");
                }
            }
        }).start();
    }

    /** true if {@code code} names one of the selected languages (ISO 639-3 as the detectors return, or any code CustomLocale reads) */
    private static boolean isSelected(String code, CustomLocale[] languages) {
        if (languages == null || languages.length == 0) return true;   //no restriction given
        if ("und".equals(code)) return false;
        CustomLocale detected = null;
        try { detected = new CustomLocale(code); } catch (Exception ignored) {}
        for (CustomLocale lang : languages) {
            if (lang == null) continue;
            if (code.equals(lang.getISO3Language()) || code.equals(lang.getCode())) return true;
            if (detected != null && detected.equalsLanguage(lang)) return true;
        }
        return false;
    }

    /**
     * This method is used to overwrite the dictionaries result for very common words (that may be present in other languages dicts)
     * @return The language of the word
     */

    private String identifySpecialWordLanguage(String word){
        if(specialWordsLanguages.containsKey(word)){
            return specialWordsLanguages.get(word);
        }
        return "und";
    }

    public abstract static class DetectLanguageListener {
        public abstract void onSuccess(String languageCode);
    }

    public void close() {
        if (nativePtr != 0) {
            release(nativePtr);
            nativePtr = 0;
        }
    }


    private native long initFastText();
    private native void loadModel(long ptr, String path);
    private native String predictLanguage(long ptr, String text, double confidenceThreshold);
    private native void release(long ptr);
}
