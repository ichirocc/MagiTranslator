/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.konovalov.vad.silero.Vad;
import com.konovalov.vad.silero.VadSilero;
import com.konovalov.vad.silero.config.FrameSize;
import com.konovalov.vad.silero.config.Mode;
import com.konovalov.vad.silero.config.SampleRate;

import java.io.File;
import java.util.ArrayList;

import nie.translator.rtranslator.access.AccessActivity;
import nie.translator.rtranslator.bluetooth.BluetoothCommunicator;
import nie.translator.rtranslator.bluetooth.Peer;
import nie.translator.rtranslator.downloader.DownloadGroupInfo;
import nie.translator.rtranslator.downloader.DownloadInfo;
import nie.translator.rtranslator.downloader.DownloadManager;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.TTS;
import nie.translator.rtranslator.tools.gui.ThemeManager;
import nie.translator.rtranslator.voice_translation._conversation_mode._conversation.ConversationService;
import nie.translator.rtranslator.voice_translation._conversation_mode.communication.ConversationBluetoothCommunicator;
import nie.translator.rtranslator.voice_translation._conversation_mode.communication.recent_peer.RecentPeersDataManager;
import nie.translator.rtranslator.voice_translation._text_translation.TranslationFragment;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recognizer;
import nie.translator.rtranslator.voice_translation.neural_networks.voice.Recorder;


public class Global extends Application implements DefaultLifecycleObserver {
    public enum RTranslatorMode {
        TEXT_TRANSLATION_MODE,
        WALKIE_TALKIE_MODE,
        CONVERSATION_MODE
    }

    public enum LanguageNumber {
        FIRST,
        SECOND
    }
    public static final boolean ONLY_TEXT_TRANSLATION_MODE = false;
    public static final boolean USE_EXTERNAL_MEMORY_FOR_RESOURCES = false;

    public static final int REQUEST_CODE_PERMISSIONS_NOTIFICATIONS = 5;
    public static String[] REQUIRED_PERMISSIONS_NOTIFICATIONS;
    public static final int REQUEST_CODE_PERMISSIONS_PAIRING = 2;
    public static String[] REQUIRED_PERMISSIONS_PAIRING;
    public static final int REQUEST_CODE_PERMISSIONS_CONVERSATION = 3;
    public static final int REQUEST_CODE_PERMISSIONS_WALKIETALKIE = 4;
    public static final String[] REQUIRED_PERMISSIONS_VOICE = new String[]{
            Manifest.permission.RECORD_AUDIO,
    };
    private ArrayList<CustomLocale> languages = new ArrayList<>();
    private ArrayList<CustomLocale> translatorLanguages = new ArrayList<>();
    private ArrayList<Translator.MozillaLanguageInfo> mozillaLanguages = new ArrayList<>();
    private ArrayList<CustomLocale> mozillaInstalledLanguages = new ArrayList<>();
    private ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();
    private CustomLocale language;
    private CustomLocale firstLanguage;
    private CustomLocale secondLanguage;
    private CustomLocale firstTextLanguage;
    private CustomLocale secondTextLanguage;
    private RecentPeersDataManager recentPeersDataManager;
    private ConversationBluetoothCommunicator bluetoothCommunicator;
    @Nullable
    private Translator translator;
    @Nullable
    private Recognizer speechRecognizer;
    private String name = "";
    private int micSensitivity = -1;
    private int speechTimeout = -1;
    private int prevVoiceDuration = -1;
    private int beamSize = -1;
    private int amplitudeThreshold = Recorder.DEFAULT_AMPLITUDE_THRESHOLD;
    private boolean isForeground = false;
    @Nullable
    private AccessActivity accessActivity;
    private Handler mainHandler;
    private static Handler mHandler = new Handler(Looper.myLooper());
    private final Object lock = new Object();
    private boolean useTatoeba;
    private boolean useTranslationDictionaries;
    private boolean useMozillaForVoiceTranslation;
    private boolean whisperReducedRam;
    private int translationMode;
    private VadSilero vad;
    private boolean modelsLoaded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        recentPeersDataManager = new RecentPeersDataManager(this);
        nie.translator.rtranslator.tools.PrivacyLog.init(this);  //no conversation content in release logs
        ThemeManager.restore(this);  //this will apply the night mode chose in the settings
        //initializeBluetoothCommunicator();
        getMicSensitivity();
        createNotificationChannel();
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        useTatoeba = sharedPreferences.getBoolean("useTatoeba", false);
        useTranslationDictionaries = sharedPreferences.getBoolean("useTranslationDictionaries", false);
        useMozillaForVoiceTranslation = sharedPreferences.getBoolean("useMozillaForVoiceTranslation", false);
        whisperReducedRam = sharedPreferences.getBoolean("whisperReducedRam", getTotalRamSize() <= 7000);
        translationMode = sharedPreferences.getInt("selectedTranslationModel", Translator.MOZILLA);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            REQUIRED_PERMISSIONS_PAIRING = new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
            };
        }else{
            REQUIRED_PERMISSIONS_PAIRING = new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            REQUIRED_PERMISSIONS_NOTIFICATIONS = new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }else{
            REQUIRED_PERMISSIONS_NOTIFICATIONS = new String[0];
        }
    }

    public void initializeTranslator(Translator.GeneralListener initListener){
        if(translator == null) {
            translator = new Translator(this, getTranslationMode(), isUseMozillaForVoiceTranslation(), isUseTatoeba(), isUseTranslationDictionaries(), initListener);
        }else{
            initListener.onSuccess();
        }
    }

    public void initializeSpeechRecognizer(NeuralNetworkApi.InitListener initListener){
        if(speechRecognizer == null) {
            vad = Vad.builder()
                    .setContext(this)
                    .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                    .setFrameSize(FrameSize.FRAME_SIZE_512)
                    .setMode(Mode.NORMAL)
                    .setSilenceDurationMs(300)
                    .setSpeechDurationMs(50)
                    .build();
            speechRecognizer = new Recognizer(this, true, initListener);
        }else{
            initListener.onInitializationFinished();
        }
    }

    public void initializeBluetoothCommunicator(){
        if(bluetoothCommunicator == null){
            bluetoothCommunicator = new ConversationBluetoothCommunicator(this, getName(), BluetoothCommunicator.STRATEGY_P2P_WITH_RECONNECTION);
        }
    }

    public void destroyBluetoothCommunicator(@Nullable BluetoothCommunicator.DestroyCallback listener){
        if(bluetoothCommunicator != null){
            bluetoothCommunicator.destroy(new BluetoothCommunicator.DestroyCallback() {
                @Override
                public void onDestroyed() {
                    if(listener != null) listener.onDestroyed();
                    bluetoothCommunicator = null;
                }
            });
        }else{
            if(listener != null) listener.onDestroyed();
        }
    }

    @Nullable
    public ConversationBluetoothCommunicator getBluetoothCommunicator() {
        return bluetoothCommunicator;
    }

    public VadSilero getVad() {
        return vad;
    }

    public boolean areModelsLoaded() {
        return modelsLoaded;
    }

    public void setModelsLoaded(boolean areModelsLoaded) {
        this.modelsLoaded = areModelsLoaded;
    }

    public void getLanguagesAndCheckTTS(final boolean recycleResult, boolean ignoreTTSError, final GetLocalesListListener responseListener) {
        if (recycleResult && !languages.isEmpty()) {
            responseListener.onSuccess(languages);
        } else {
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {    //we load TTS languages to catch eventual TTS errors
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    responseListener.onSuccess(getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, recycleResult));
                }

                @Override
                public void onError(int reason) {
                    if (ignoreTTSError) {
                        //SPEC 12: missing/broken TTS must never hide languages; translation and display still work
                        responseListener.onSuccess(getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, recycleResult));
                    } else {
                        responseListener.onFailure(new int[]{reason}, 0);
                    }
                }
            });
        }
    }

    /**
     * SPEC 12: shows once (per install) a non-blocking notice when the output language is Nepali
     * and this device has no Nepali TTS voice. Translation is still shown; it is simply not spoken.
     */
    public void notifyTtsUnavailableOnce(@Nullable CustomLocale outputLanguage, @Nullable ArrayList<CustomLocale> ttsLanguages) {
        if (outputLanguage == null || !"ne".equals(outputLanguage.getLanguage())) return;
        if (ttsLanguages != null && CustomLocale.containsLanguage(ttsLanguages, outputLanguage)) return;
        final SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        if (sharedPreferences.getBoolean("neTtsUnavailableNoticeShown", false)) return;
        sharedPreferences.edit().putBoolean("neTtsUnavailableNoticeShown", true).apply();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(Global.this, R.string.toast_ne_tts_unavailable, android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    public ArrayList<CustomLocale> getLanguages(RTranslatorMode rtranslatorMode, final boolean recycleResult) {
        ArrayList<CustomLocale> translatorLanguages = getTranslatorLanguages(rtranslatorMode, recycleResult);
        ArrayList<CustomLocale> speechRecognizerLanguages = Recognizer.getSupportedLanguages(Global.this);
        //we return only the languages compatible with the speech recognizer and the translator (without loading TTS languages)
        final ArrayList<CustomLocale> compatibleLanguages = new ArrayList<>();
        for (CustomLocale translatorLanguage : translatorLanguages) {
            //SPEC 12: the focus languages (ja/ne/vi) are always kept, independent of ASR/TTS quality filters
            if (CustomLocale.containsLanguage(speechRecognizerLanguages, translatorLanguage)
                    || java.util.Arrays.asList(Translator.FOCUS_LANGUAGES).contains(translatorLanguage.getLanguage())) {
                compatibleLanguages.add(translatorLanguage);
            }
        }
        languages = compatibleLanguages;
        return compatibleLanguages;
    }

    public ArrayList<CustomLocale> getTranslatorLanguages(RTranslatorMode rtranslatorMode, final boolean recycleResult) {
        //MagiTranslator: the language pair decides the model inside Translator, so the list is always ja/ne/vi
        ArrayList<CustomLocale> focus = new ArrayList<>();
        for (String code : Translator.FOCUS_LANGUAGES) focus.add(CustomLocale.getInstance(code));
        if (translatorLanguages.isEmpty()) translatorLanguages = focus;
        if (true) return focus;
        if (recycleResult) {
            if(isUseMozillaForVoiceTranslation() && (rtranslatorMode == RTranslatorMode.CONVERSATION_MODE || rtranslatorMode == RTranslatorMode.WALKIE_TALKIE_MODE)) {
                return getMozillaLanguages(true);
            }else{
                if(!translatorLanguages.isEmpty()) return translatorLanguages;
            }
        }

        // recycleResult is false
        SharedPreferences sharedPreferences = getSharedPreferences("default", Context.MODE_PRIVATE);
        int mode;
        if(translator != null){
            mode = translator.getMode();
        }else{
            mode = sharedPreferences.getInt("selectedTranslationModel", Translator.MOZILLA);
        }
        boolean qualityLow = sharedPreferences.getBoolean("languagesNNQualityLow", false);
        ArrayList<CustomLocale> languages = Translator.getSupportedLanguages(Global.this, qualityLow, mode);  //in the case of mozilla, this method will return all the supported languages, not only the installed ones, so for mozilla we will use the getMozillaLanguages method
        ArrayList<CustomLocale> mozillaLanguages = getMozillaLanguages(false);
        if(mode == Translator.MOZILLA) {
            translatorLanguages = mozillaLanguages;
            return mozillaLanguages;
        }else{
            translatorLanguages = languages;
            if(isUseMozillaForVoiceTranslation() && (rtranslatorMode == RTranslatorMode.CONVERSATION_MODE || rtranslatorMode == RTranslatorMode.WALKIE_TALKIE_MODE)) {
                return mozillaLanguages;
            }else{
                return languages;
            }
        }
    }

    /**
     * getMozillaLanguages
     * @param recycleResult decides if recycle the result or not
     * @return the list of installed Mozilla languages
     */
    private ArrayList<CustomLocale> getMozillaLanguages(final boolean recycleResult){
        if (recycleResult && !mozillaInstalledLanguages.isEmpty()) {
            return mozillaInstalledLanguages;
        } else {
            ArrayList<DownloadGroupInfo> downloads = DownloadManager.getSavedDownloadStatus(this);
            ArrayList<MozillaLanguageDownloadInfo> mozillaLanguageDownloadInfos = getMozillaLanguagesDownloadInfo(recycleResult);
            ArrayList<CustomLocale> mozillaInstalledLanguages = new ArrayList<>();
            for (Global.MozillaLanguageDownloadInfo langDownloadInfo : mozillaLanguageDownloadInfos) {
                int index = downloads.indexOf(langDownloadInfo.downloadGroupInfo);
                if (index != -1 && downloads.get(index).isAllDownloadCompleted()) {
                    mozillaInstalledLanguages.add(langDownloadInfo.lang);
                }
            }
            mozillaInstalledLanguages.add(new CustomLocale("en"));  //English is always present, but it isn't present in the getMozillaLanguagesDownloadInfo list, so we add it manually
            this.mozillaInstalledLanguages = mozillaInstalledLanguages;
            return mozillaInstalledLanguages;
        }
    }

    /**
     * getMozillaLanguages
     * @param recycleResult decides if recycle the result or not
     * @return the list of all supported Mozilla languages (installed and not) with also the size of each language model
     */
    public ArrayList<Translator.MozillaLanguageInfo> getMozillaLanguagesInfo(final boolean recycleResult) {
        if (recycleResult && !mozillaLanguages.isEmpty()) {
            return mozillaLanguages;
        } else {
            ArrayList<Translator.MozillaLanguageInfo> languages = Translator.getMozillaLanguagesInfo(this);
            mozillaLanguages = languages;
            return languages;
        }
    }

    public void getTTSLanguages(final boolean recycleResult, final GetLocalesListListener responseListener){
        if(recycleResult && !ttsLanguages.isEmpty()){
            responseListener.onSuccess(ttsLanguages);
        }else{
            TTS.getSupportedLanguages(this, new TTS.SupportedLanguagesListener() {    //we load TTS languages to catch eventual TTS errors
                @Override
                public void onLanguagesListAvailable(ArrayList<CustomLocale> ttsLanguages) {
                    Global.this.ttsLanguages = ttsLanguages;
                    responseListener.onSuccess(ttsLanguages);
                }

                @Override
                public void onError(int reason) {
                    //forget the last good list: it described a TTS engine that does not answer now
                    Global.this.ttsLanguages = new ArrayList<>();
                    responseListener.onSuccess(Global.this.ttsLanguages);
                }
            });
        }
    }


    public ArrayList<MozillaLanguageDownloadInfo> getMozillaLanguagesDownloadInfo(final boolean recycleResult){
        String downloadFolder = getFilesDir().getAbsolutePath()+"/Translation/Mozilla/";
        ArrayList<MozillaLanguageDownloadInfo> languageDownloadInfos = new ArrayList<>();
        for(Translator.MozillaLanguageInfo langInfo : getMozillaLanguagesInfo(recycleResult)){
            //MagiTranslator: only the focus languages are mirrored in our own release (ModelSources)
            if (!java.util.Arrays.asList(Translator.FOCUS_LANGUAGES).contains(langInfo.lang.getLanguage())) continue;
            String fileName = "Mozilla_"+langInfo.lang.getLanguage()+".zip";
            languageDownloadInfos.add(new MozillaLanguageDownloadInfo(
                    langInfo.lang,
                    new DownloadGroupInfo(new DownloadInfo[]{new DownloadInfo(fileName, nie.translator.rtranslator.downloader.ModelSources.url(fileName), downloadFolder, langInfo.sizeKb, false, true)})
            ));
        }
        return languageDownloadInfos;
    }

    public static class MozillaLanguageDownloadInfo {
        public CustomLocale lang;
        public DownloadGroupInfo downloadGroupInfo;

        public MozillaLanguageDownloadInfo(CustomLocale lang, DownloadGroupInfo downloadGroupInfo) {
            this.lang = lang;
            this.downloadGroupInfo = downloadGroupInfo;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if(obj instanceof CustomLocale){
                return lang.equals(obj);
            }
            return super.equals(obj);
        }
    }

    public DownloadGroupInfo getHyMtDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "HY-MT.zip",
                        nie.translator.rtranslator.downloader.ModelSources.url("HY-MT.zip"),
                        downloadFolder + "/Translation/",
                        1790000,
                        true,
                        true)
        });
    }

    public DownloadGroupInfo getMadladDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "Madlad.zip",
                        nie.translator.rtranslator.downloader.ModelSources.url("Madlad.zip"),
                        downloadFolder + "/Translation/",
                        1380000,
                        true,
                        true)

        });
    }

    /** PLaMo 3 Translate (GGUF), downloaded from the publisher (never mirrored, see ModelSources.directUrl). */
    public DownloadGroupInfo getPlamoDownloadInfo(nie.translator.rtranslator.plamo.PlamoModels.Variant variant){
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        variant.fileName,
                        nie.translator.rtranslator.downloader.ModelSources.directUrl(variant.fileName),
                        nie.translator.rtranslator.plamo.PlamoModels.dir(getFilesDir()).getAbsolutePath() + "/",
                        variant.sizeBytes / 1024,
                        true,
                        false)
        });
    }

    public DownloadGroupInfo getTatoebaDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "Tatoeba.zip",
                        nie.translator.rtranslator.downloader.ModelSources.url("Tatoeba.zip"),
                        downloadFolder + "/Translation/",
                        596000,
                        false,
                        true)

        });
    }

    public DownloadGroupInfo getNllbDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        return new DownloadGroupInfo(new DownloadInfo[]{
                new DownloadInfo(
                        "NLLB_cache_initializer.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_cache_initializer.onnx"),
                        downloadFolder,
                        24000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_decoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_decoder.onnx"),
                        downloadFolder,
                        171000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_embed_and_lm_head.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_embed_and_lm_head.onnx"),
                        downloadFolder,
                        500000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_encoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_encoder.onnx"),
                        downloadFolder,
                        254000,
                        true,
                        false
                ),
        });
    }

    public DownloadGroupInfo getInitialDownloadInfo(){
        String downloadFolder = getFilesDir().getAbsolutePath();
        return new DownloadGroupInfo(new DownloadInfo[]{
                /*new DownloadInfo(
                        "NLLB_cache_initializer.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_cache_initializer.onnx"),
                        downloadFolder,
                        24000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_decoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_decoder.onnx"),
                        downloadFolder,
                        171000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_embed_and_lm_head.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_embed_and_lm_head.onnx"),
                        downloadFolder,
                        500000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "NLLB_encoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("NLLB_encoder.onnx"),
                        downloadFolder,
                        254000,
                        true,
                        false
                ),*/
                new DownloadInfo(
                        "Whisper_cache_initializer.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_cache_initializer.onnx"),
                        downloadFolder,
                        14000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_cache_initializer_batch.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_cache_initializer_batch.onnx"),
                        downloadFolder,
                        14000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_decoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_decoder.onnx"),
                        downloadFolder,
                        173000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_detokenizer.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_detokenizer.onnx"),
                        downloadFolder,
                        461,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_encoder.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_encoder.onnx"),
                        downloadFolder,
                        88000,
                        true,
                        false
                ),
                new DownloadInfo(
                        "Whisper_initializer.onnx",
                        nie.translator.rtranslator.downloader.ModelSources.url("Whisper_initializer.onnx"),
                        downloadFolder,
                        69,
                        true,
                        false
                ),
                new DownloadInfo(
                        "TranslationDictionaries.zip",
                        nie.translator.rtranslator.downloader.ModelSources.url("TranslationDictionaries.zip"),
                        downloadFolder + "/Translation/",
                        108000,
                        false,
                        true
                ),
        });
    }


    @Nullable
    public Translator getTranslator() {
        return translator;
    }

    public void deleteTranslator(){
        translator = null;
    }

    @Nullable
    public Recognizer getSpeechRecognizer() {
        return speechRecognizer;
    }

    public void deleteSpeechRecognizer(){
        speechRecognizer = null;
    }

    public boolean isForeground() {
        return isForeground;
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStop(owner);
        //App in background
        isForeground = false;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        DefaultLifecycleObserver.super.onStart(owner);
        // App in foreground
        isForeground = true;
    }

    @Nullable
    public AccessActivity getRunningAccessActivity() {
        return accessActivity;
    }

    public void setAccessActivity(@Nullable AccessActivity accessActivity) {
        this.accessActivity = accessActivity;
    }

    public interface GetLocalesListListener {
        void onSuccess(ArrayList<CustomLocale> result);

        void onFailure(int[] reasons, long value);
    }

    public CustomLocale getLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.CONVERSATION_MODE, true);
        CustomLocale predefinedLanguage = CustomLocale.getInstance("ja");  //MagiTranslator: Japanese first
        CustomLocale language = null;
        if (recycleResult && Global.this.language != null) {
            language = Global.this.language;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("language", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.language = language;
        return language;
    }

    public CustomLocale getFirstLanguage(final boolean recycleResult) {
        final ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.WALKIE_TALKIE_MODE, true);
        CustomLocale predefinedLanguage = getLanguage(true);
        CustomLocale language = null;
        if (recycleResult && Global.this.firstLanguage != null) {
            language = Global.this.firstLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("firstLanguage", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else if(!languages.isEmpty()) {
                language = languages.get(0);  //we pick the first language of the list
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.firstLanguage = language;
        return language;
    }

    public CustomLocale getSecondLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getLanguages(RTranslatorMode.WALKIE_TALKIE_MODE, true);
        CustomLocale predefinedLanguage = CustomLocale.getDefault();
        CustomLocale language = null;
        if (recycleResult && Global.this.secondLanguage != null) {
            language = Global.this.secondLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("secondLanguage", null);
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            language = defaultSecondLanguage(languages, getFirstLanguage(true));
        }

        Global.this.secondLanguage = language;
        return language;
    }

    /**
     * MagiTranslator: default partner language is Nepali, then Vietnamese, then Japanese, never the same as
     * the first language (a Nepali-speaking user with first language ne used to get the pair ne -> ne).
     */
    private CustomLocale defaultSecondLanguage(ArrayList<CustomLocale> languages, @Nullable CustomLocale first) {
        for (String code : new String[]{"ne", "vi", "ja"}) {
            if (first != null && code.equals(first.getCode())) continue;
            CustomLocale c = CustomLocale.getInstance(code);
            int i = CustomLocale.search(languages, c);
            if (i != -1) return languages.get(i);
        }
        for (CustomLocale l : languages) if (first == null || !l.getCode().equals(first.getCode())) return l;
        return languages.isEmpty() ? new CustomLocale("en") : languages.get(0);
    }

    public CustomLocale getFirstTextLanguage(final boolean recycleResult) {
        final ArrayList<CustomLocale> languages = getTranslatorLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, true);
        CustomLocale predefinedLanguage = getLanguage(true);
        CustomLocale language = null;
        if (recycleResult && Global.this.firstTextLanguage != null) {
            language = Global.this.firstTextLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("firstTextLanguage", predefinedLanguage.getCode());
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            int index2 = CustomLocale.search(languages, predefinedLanguage);
            if (index2 != -1) {
                language = predefinedLanguage;
            } else if(!languages.isEmpty()) {
                language = languages.get(0);  //we pick the first language of the list
            } else {
                language = new CustomLocale("en");
            }
        }

        Global.this.firstTextLanguage = language;
        return language;
    }

    public CustomLocale getSecondTextLanguage(final boolean recycleResult) {
        ArrayList<CustomLocale> languages = getTranslatorLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, true);
        CustomLocale language = null;
        if (recycleResult && Global.this.secondTextLanguage != null) {
            language = Global.this.secondTextLanguage;
        } else {
            SharedPreferences sharedPreferences = Global.this.getSharedPreferences("default", Context.MODE_PRIVATE);
            String code = sharedPreferences.getString("secondTextLanguage", null);
            if (code != null) {
                language = CustomLocale.getInstance(code);
            }
        }

        int index = CustomLocale.search(languages, language);
        if (index != -1) {
            language = languages.get(index);
        } else {
            language = defaultSecondLanguage(languages, getFirstTextLanguage(true));
        }

        Global.this.secondTextLanguage = language;
        return language;
    }

    public interface GetLocaleListener {
        void onSuccess(CustomLocale result);

        void onFailure(int[] reasons, long value);
    }

    public interface GetTwoLocaleListener {
        void onSuccess(CustomLocale language1, CustomLocale language2);

        void onFailure(int[] reasons, long value);
    }

    public void setLanguage(CustomLocale language) {
        this.language = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("language", language.getCode());
        editor.apply();
        if (translator != null) {
            translator.resetConversationMemory();   //new language pair: drop jane term memory
        }
        if(ConversationService.isRunning && translator != null){
            translator.loadTgtLangResourcesForConversation(language, null);
            if(getBluetoothCommunicator() != null){
                getBluetoothCommunicator().sendLanguage(language);
            }
        }
    }

    public void setFirstLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.firstLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstLanguage", language.getCode());
        editor.apply();
        if (translator != null) {
            translator.resetConversationMemory();   //new language pair: drop jane term memory
        }
        loadLanguagesResources(language, getSecondLanguage(true), RTranslatorMode.WALKIE_TALKIE_MODE, listener);
    }

    public void setSecondLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.secondLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondLanguage", language.getCode());
        editor.apply();
        if (translator != null) {
            translator.resetConversationMemory();   //new language pair: drop jane term memory
        }
        loadLanguagesResources(getFirstLanguage(true), language, RTranslatorMode.WALKIE_TALKIE_MODE, listener);
    }

    public void setFirstTextLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.firstTextLanguage = language;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstTextLanguage", language.getCode());
        editor.apply();
        if (translator != null) {
            translator.resetConversationMemory();   //new language pair: drop jane term memory
        }
        loadLanguagesResources(language, getSecondTextLanguage(true), RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }

    public void setSecondTextLanguage(CustomLocale language, @Nullable Translator.GeneralListener listener) {
        this.secondTextLanguage = language;
        CustomLocale firstLanguage = getFirstTextLanguage(true);
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("secondTextLanguage", language.getCode());
        editor.apply();
        if (translator != null) {
            translator.resetConversationMemory();   //new language pair: drop jane term memory
        }
        loadLanguagesResources(getFirstTextLanguage(true), language, RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }

    public void switchTextLanguages(@Nullable Translator.GeneralListener listener) {
        CustomLocale firstLanguage = getFirstTextLanguage(true);
        CustomLocale secondLanguage = getSecondTextLanguage(true);
        this.firstTextLanguage = secondLanguage;
        this.secondTextLanguage = firstLanguage;
        SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("firstTextLanguage", this.firstTextLanguage.getCode());
        editor.putString("secondTextLanguage", this.secondTextLanguage.getCode());
        editor.apply();
        loadLanguagesResources(secondLanguage, firstLanguage, RTranslatorMode.TEXT_TRANSLATION_MODE, listener);
    }


    public int getAmplitudeThreshold() {
        return amplitudeThreshold;
    }


    public int getMicSensitivity() {
        if (micSensitivity == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            micSensitivity = sharedPreferences.getInt("micSensibility", 50);
            setAmplitudeThreshold(micSensitivity);
        }
        return micSensitivity;
    }

    public void setMicSensitivity(int value) {
        micSensitivity = value;
        setAmplitudeThreshold(micSensitivity);
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("micSensibility", value);
        editor.apply();
    }

    public int getSpeechTimeout() {
        if (speechTimeout == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            speechTimeout = sharedPreferences.getInt("speechTimeout", Recorder.DEFAULT_SPEECH_TIMEOUT_MILLIS);
        }
        return speechTimeout;
    }

    public void setSpeechTimeout(int value) {
        speechTimeout = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("speechTimeout", value);
        editor.apply();
    }

    public int getPrevVoiceDuration() {
        if (prevVoiceDuration == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            prevVoiceDuration = sharedPreferences.getInt("prevVoiceDuration", Recorder.DEFAULT_PREV_VOICE_DURATION);
        }
        return prevVoiceDuration;
    }

    public void setPrevVoiceDuration(int value) {
        prevVoiceDuration = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("prevVoiceDuration", value);
        editor.apply();
    }

    private void setAmplitudeThreshold(int micSensitivity) {
        float amplitudePercentage = 1f - (micSensitivity / 100f);
        if (amplitudePercentage < 0.5f) {
            amplitudeThreshold = Math.round(Recorder.MIN_AMPLITUDE_THRESHOLD + ((Recorder.DEFAULT_AMPLITUDE_THRESHOLD - Recorder.MIN_AMPLITUDE_THRESHOLD) * (amplitudePercentage * 2)));
        } else {
            amplitudeThreshold = Math.round(Recorder.DEFAULT_AMPLITUDE_THRESHOLD + ((Recorder.MAX_AMPLITUDE_THRESHOLD - Recorder.DEFAULT_AMPLITUDE_THRESHOLD) * ((amplitudePercentage - 0.5F) * 2)));
        }
    }

    public int getBeamSize() {
        if (beamSize == -1) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            beamSize = sharedPreferences.getInt("beamSize", TranslationFragment.DEFAULT_BEAM_SIZE);
        }
        return beamSize;
    }

    public void setBeamSize(int value) {
        beamSize = value;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt("beamSize", value);
        editor.apply();
    }

    public boolean isUseTatoeba(){
        return useTatoeba;
    }

    public boolean isUseTranslationDictionaries(){
        return useTranslationDictionaries;
    }

    public boolean isUseMozillaForVoiceTranslation() {
        return useMozillaForVoiceTranslation;
    }

    public boolean isWhisperReducedRam() {
        return whisperReducedRam;
    }

    public void setWhisperReducedRam(boolean whisperReducedRam, @Nullable Translator.GeneralListener listener) {
        boolean changed = this.whisperReducedRam != whisperReducedRam;
        if(changed){
            this.whisperReducedRam = whisperReducedRam;
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("whisperReducedRam", whisperReducedRam);
            editor.apply();
            // restart of encoder session with the new option
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if(speechRecognizer != null) speechRecognizer.initializeEncoderSession();
                        mainHandler.post(() -> {
                            if(listener != null) listener.onSuccess();
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        mainHandler.post(() -> {
                            if (listener != null) listener.onFailure(new int[]{ErrorCodes.ERROR}, 0);
                        });
                    }
                }
            }).start();
        }else{
            if(listener != null) listener.onSuccess();
        }
    }

    public int getTranslationMode() {
        return translationMode;
    }

    public void setTranslationStatus(int translationMode, boolean useMozillaForVoiceTranslation, boolean useTatoeba, boolean useTranslationDicts, @NonNull Translator.GeneralListener listener) {
        boolean changed = this.translationMode != translationMode
                || this.useMozillaForVoiceTranslation != useMozillaForVoiceTranslation
                || this.useTatoeba != useTatoeba
                || this.useTranslationDictionaries != useTranslationDicts;
        if(changed) {
            this.translationMode = translationMode;
            this.useMozillaForVoiceTranslation = useMozillaForVoiceTranslation;
            this.useTatoeba = useTatoeba;
            this.useTranslationDictionaries = useTranslationDicts;
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("selectedTranslationModel", translationMode);
            editor.putBoolean("useMozillaForVoiceTranslation", useMozillaForVoiceTranslation);
            editor.putBoolean("useTatoeba", useTatoeba);
            editor.putBoolean("useTranslationDictionaries", useTranslationDicts);
            editor.apply();
            // update of the translator status (if the translator is already initialized)
            if (translator != null) {
                translator.setTranslationStatus(translationMode, useMozillaForVoiceTranslation, useTatoeba, useTranslationDicts, listener);
            }else{
                listener.onSuccess();
            }
        }else{
            listener.onSuccess();
        }
    }

    /**
     * This method will update the languages selected for all rtranslator modes (text, walkieTalkie, conversation),
     * usually it is called after a model change for some modes, because the selected languages could be not compatible anymore with the new model.
     * In that case, this method will select a compatible language for the selected model in that rtranslator mode.
     * This method will also update the lists of languages available to each rtranslator mode based on the new selected models.
     * But it won't update the resources associated with the new languages.
     */
    public void updateLanguages() {
        getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, false);  //this will update the recognizer langs, the translator langs and also the mozilla langs (for all the RTranslator modes)
        getFirstTextLanguage(false);
        getSecondTextLanguage(false);
        getFirstLanguage(false);
        getSecondLanguage(false);
        getLanguage(false);
    }

    /**
     * This method does the same thing as updateLanguages() but it also updates the preferences and resources of the new languages.
     * @param listener
     */
    public void updateLanguagesAndResources(Translator.GeneralListener listener){
        getLanguages(RTranslatorMode.TEXT_TRANSLATION_MODE, false);  //this will update the recognizer langs, the translator langs and also the mozilla langs (for all the RTranslator modes)
        setFirstTextLanguage(getFirstTextLanguage(false), new Translator.GeneralListener() {
            @Override
            public void onSuccess() {
                setSecondTextLanguage(getSecondTextLanguage(false), new Translator.GeneralListener() {
                    @Override
                    public void onSuccess() {
                        setFirstLanguage(getFirstLanguage(false), new Translator.GeneralListener() {
                            @Override
                            public void onSuccess() {
                                setSecondLanguage(getSecondLanguage(false), new Translator.GeneralListener() {
                                    @Override
                                    public void onSuccess() {
                                        setLanguage(getLanguage(false));
                                        mainHandler.post(() -> listener.onSuccess());
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    public String getName() {
        if (name.length() == 0) {
            final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
            name = sharedPreferences.getString("name", "user");
        }
        return name;
    }

    public void setName(String savedName) {
        name = savedName;
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("name", savedName);
        editor.apply();
        if(getBluetoothCommunicator() != null) {
            getBluetoothCommunicator().setName(savedName);  //we update the name also for communicator
        }
    }

    public Peer getMyPeer() {
        return new Peer(null, getName(), false);
    }

    public abstract static class MyPeerListener {
        public abstract void onSuccess(Peer myPeer);

        public void onFailure(int[] reasons, long value) {
        }
    }

    public void getMyID(final MyIDListener responseListener) {
        responseListener.onSuccess(Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID));
    }

    public abstract static class MyIDListener {
        public abstract void onSuccess(String id);

        public void onFailure(int[] reasons, long value) {
        }
    }

    public RecentPeersDataManager getRecentPeersDataManager() {
        return recentPeersDataManager;
    }

    public abstract static class ResponseListener {
        public void onSuccess() {

        }

        public void onFailure(int[] reasons, long value) {
        }
    }

    public boolean isFirstStart() {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean("firstStart", true);
    }

    public void setFirstStart(boolean firstStart) {
        final SharedPreferences sharedPreferences = this.getSharedPreferences("default", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("firstStart", firstStart);
        editor.apply();
    }


    private void createNotificationChannel(){
        String channelID = "service_background_notification";
        String channelName = getResources().getString(R.string.notification_channel_name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(channelID, channelName, NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(notificationChannel);
        }
    }

    /**
     * Returns the total RAM size of the device in MB
     */
    public int getTotalRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long totalMemory = memInfo.totalMem / 1000000L;
        android.util.Log.i("memory", "Total memory: " + totalMemory);
        return (int) totalMemory;
    }

    /**
     * Returns the available RAM size of the device in MB
     */
    public int getAvailableRamSize(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long availableMemory = memInfo.availMem;
        android.util.Log.i("memory", "Total memory: " + availableMemory);
        return (int) (availableMemory / 1000000);
    }

    /**
     * Returns (in MB) the absolute maximum native RAM allocatable by forcing the OS
     * to kill all other killable processes.
     */
    public int getMaxAllocatableRAM() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) return 0;

        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        // Total physical RAM available to the OS (already excludes GPU-reserved RAM)
        long totalRam = memoryInfo.totalMem;

        // Android doesn't have an API that shows the max amount of RAM available for native allocations before a crash occurs,
        // so the best way (also hinted here https://developer.android.com/games/optimize/memory-allocation#conservative-memory-budgets)
        // is to use a percentage of the total RAM between 50% and 25% as a hard limit of native RAM usage for a single app.
        double maxPercentage = 0.45;

        // The absolute theoretical maximum you can allocate before the OS targets YOU
        long maxAllocatable = (long) (totalRam - (totalRam*maxPercentage)) / 1000000L;

        // Return the result, ensuring we don't return a negative number on heavily constrained devices
        return (int) Math.max(0, maxAllocatable);
    }

    public int getRamThreshold(){
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        long threshold = memInfo.threshold;
        return (int) (threshold / 1000000);
    }

    /**
     * Returns the available internal memory space in MB
     */
    public long getAvailableInternalMemorySize() {
        File internalFilesDir = this.getFilesDir();
        if(internalFilesDir != null) {
            long freeMBInternal = new File(internalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBInternal;
        }
        return -1;
    }

    /**
     * Returns the available external memory space in MB
     */
    public long getAvailableExternalMemorySize() {
        File externalFilesDir = this.getExternalFilesDir(null);
        if(externalFilesDir != null) {
            long freeMBExternal = new File(externalFilesDir.getAbsoluteFile().toString()).getFreeSpace() / 1000000L;
            return freeMBExternal;
        }
        return -1;
    }



    private void loadLanguagesResources(CustomLocale firstLanguage, CustomLocale secondLanguage, RTranslatorMode mode, Translator.GeneralListener listener){
        if(translator != null) {
            translator.loadLanguageResources(firstLanguage, secondLanguage, mode, listener);
        }else{
            listener.onSuccess();  //if the translator is not initialized yet we do not load the language resources
        }
    }

    // ===== MagiTranslator: memory pressure (spec v2 part 11) =====
    // Releases models that are not in use; they are reloaded lazily on the next translation / utterance.
    // Nothing is released while a translation or a recognition is running (both checks never wait).
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isForeground) {
            releaseIdleModels("trim " + level);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        releaseIdleModels("low memory");
    }

    private void releaseIdleModels(String reason) {
        boolean t = translator != null && translator.releaseIdleModel();
        boolean r = speechRecognizer != null && speechRecognizer.releaseIdleNeModel();
        android.util.Log.i("memory", reason + ": translation model released=" + t + ", Nepali Whisper release queued=" + r);
    }
}

