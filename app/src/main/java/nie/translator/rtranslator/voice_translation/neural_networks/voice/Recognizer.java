/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.voice_translation.neural_networks.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import nie.translator.rtranslator.Global;
import nie.translator.rtranslator.R;
import androidx.annotation.Nullable;
import nie.translator.rtranslator.tools.CustomLocale;
import nie.translator.rtranslator.tools.ErrorCodes;
import nie.translator.rtranslator.tools.nn.TensorUtils;
import nie.translator.rtranslator.tools.nn.Utils;
import nie.translator.rtranslator.voice_translation.neural_networks.NeuralNetworkApi;


public class Recognizer extends NeuralNetworkApi {
    private static final int MAX_TOKENS_PER_SECOND = 30;
    private static final int MAX_TOKENS = 445;   //if we generate more than this quantity of tokens for a transcription we have an error
    public static final String UNDEFINED_TEXT = "[(und)]";
    private ArrayList<RecognizerListener> callbacks = new ArrayList<>();
    private ArrayList<RecognizerMultiListener> multiCallbacks = new ArrayList<>();
    private boolean recognizing = false;
    private ArrayDeque<DataContainer> dataToRecognize = new ArrayDeque<>();
    private final Object lock = new Object();

    private static final String[] LANGUAGES = {
            "en",
            "zh",
            "de",
            "es",
            "ru",
            "ko",
            "fr",
            "ja",
            "pt",
            "tr",
            "pl",
            "ca",
            "nl",
            "ar",
            "sv",
            "it",
            "id",
            "hi",
            "fi",
            "vi",
            "he",
            "uk",
            "el",
            "ms",
            "cs",
            "ro",
            "da",
            "hu",
            "ta",
            "no",
            "th",
            "ur",
            "hr",
            "bg",
            "lt",
            "la",
            "mi",
            "ml",
            "cy",
            "sk",
            "te",
            "fa",
            "lv",
            "bn",
            "sr",
            "az",
            "sl",
            "kn",
            "et",
            "mk",
            "br",
            "eu",
            "is",
            "hy",
            "ne",
            "mn",
            "bs",
            "kk",
            "sq",
            "sw",
            "gl",
            "mr",
            "pa",
            "si",
            "km",
            "sn",
            "yo",
            "so",
            "af",
            "oc",
            "ka",
            "be",
            "tg",
            "sd",
            "gu",
            "am",
            "yi",
            "lo",
            "uz",
            "fo",
            "ht",
            "ps",
            "tk",
            "nn",
            "mt",
            "sa",
            "lb",
            "my",
            "bo",
            "tl",
            "mg",
            "as",
            "tt",
            "haw",
            "ln",
            "ha",
            "ba",
            "jw",
            "su",
            "yue"
    };

    private final int START_TOKEN_ID = 50258;
    private final int TRANSCRIBE_TOKEN_ID = 50359;
    private final int NO_TIMESTAMPS_TOKEN_ID = 50363;

    private OrtSession session;
    private OrtSession initSession;
    private OrtSession encoderSession;
    private OrtSession cacheInitSession;
    private OrtSession cacheInitBatchSession;
    private OrtSession decoderSession;
    private WhisperDetokenizer detokenizer;   //token ids -> text in Java (no onnxruntime-extensions: its .so files are not 16 KB aligned)
    // MagiTranslator: optional Nepali fine-tuned Whisper-small (files/Whisper_ne/*). Same vocabulary and log-mel,
    // so the initializer and detokenizer sessions are shared with the base model.
    private OrtSession neEncoderSession, neCacheInitSession, neCacheInitBatchSession, neDecoderSession;
    private boolean neModelTried = false;
    private OrtEnvironment onnxEnv;


    public Recognizer(Global global, final boolean returnResultOnlyAtTheEnd, final NeuralNetworkApi.InitListener initListener) {
        this.global = global;
        //onnxEnv = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE);
        onnxEnv = OrtEnvironment.getEnvironment();

        String modelInitPath = global.getFilesDir().getPath() + "/Whisper_initializer.onnx";
        String decoderPath = global.getFilesDir().getPath() + "/Whisper_decoder.onnx";
        String cacheInitPath = global.getFilesDir().getPath() + "/Whisper_cache_initializer.onnx";
        String cacheInitBatchPath = global.getFilesDir().getPath() + "/Whisper_cache_initializer_batch.onnx";
        String detokenizerPath = global.getFilesDir().getPath() + "/Whisper_detokenizer.onnx";

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OrtSession.SessionOptions initSessionOptions = new OrtSession.SessionOptions();
                    initSessionOptions.setCPUArenaAllocator(false);
                    initSessionOptions.setMemoryPatternOptimization(false);
                    initSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    initSession = onnxEnv.createSession(modelInitPath, initSessionOptions);

                    initializeEncoderSession();

                    OrtSession.SessionOptions cacheSessionOptions = new OrtSession.SessionOptions();
                    cacheSessionOptions.setCPUArenaAllocator(false);
                    cacheSessionOptions.setMemoryPatternOptimization(false);
                    cacheSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    cacheInitSession = onnxEnv.createSession(cacheInitPath, cacheSessionOptions);
                    cacheInitBatchSession = onnxEnv.createSession(cacheInitBatchPath, cacheSessionOptions);

                    OrtSession.SessionOptions decoderSessionOptions = new OrtSession.SessionOptions();
                    decoderSessionOptions.setCPUArenaAllocator(false);
                    decoderSessionOptions.setMemoryPatternOptimization(false);
                    decoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
                    decoderSession = onnxEnv.createSession(decoderPath, decoderSessionOptions);

detokenizer = WhisperDetokenizer.load(new java.io.File(detokenizerPath));   //vocabulary read from the downloaded Whisper_detokenizer.onnx

                    initListener.onInitializationFinished();
                } catch (Exception e) {
                    e.printStackTrace();
                    initListener.onError(new int[]{ErrorCodes.ERROR_LOADING_MODEL},0);
                }
            }
        }).start();
    }

    public void initializeEncoderSession() throws OrtException {
        // build the new session first: if that fails the old one stays usable
        String encoderPath = global.getFilesDir().getPath() + "/Whisper_encoder.onnx";
        OrtSession fresh;
        try (OrtSession.SessionOptions encoderSessionOptions = new OrtSession.SessionOptions()) {
            if(global.isWhisperReducedRam()){
                encoderSessionOptions.setCPUArenaAllocator(false);
                encoderSessionOptions.setMemoryPatternOptimization(false);
            }else {
                encoderSessionOptions.setCPUArenaAllocator(true);
                encoderSessionOptions.setMemoryPatternOptimization(true);
            }
            encoderSessionOptions.setSymbolicDimensionValue("batch_size", 1);
            encoderSessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            fresh = onnxEnv.createSession(encoderPath, encoderSessionOptions);
        }
        // swap under the recognition lock (recognize() runs entirely inside it), then close the old one
        OrtSession old;
        synchronized (lock) {
            old = encoderSession;
            encoderSession = fresh;
        }
        if (old != null) old.close();
    }

    /**
     * Recognizes the speech audio. This method should be called every time a chunk of byte buffer
     * is returned from onVoice.
     *
     * @param data The audio data.
     */
    // one thread takes the chunks in the order they arrive: a new thread per chunk could take the lock out of order
    // (and costs a thread per utterance); recognition itself still runs under lock as before
    private final java.util.concurrent.ExecutorService enqueueExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "recognizer"));

    // bumped by stop() on the caller's thread: chunks handed in before it are dropped even if still queued in the executor
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

    public void recognize(final float[] data, int beamSize, final String languageCode) {
        final int gen = generation.get();
        if (enqueueExecutor.isShutdown()) return;   //destroyed: a late chunk from an old reference is dropped
        enqueueExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != generation.get()) return;
                synchronized (lock) {
                    Log.e("recognizer","recognizingCalled");
                    if (data != null) {
                        if ("ne".equals(languageCode)) {
                            //MagiTranslator: decode Nepali twice in one batch (constrained + free) and keep the best
                            DataContainer dual = new DataContainer(data, beamSize, languageCode, languageCode);
                            dual.neDual = true;
                            dataToRecognize.addLast(dual);
                        } else {
                            dataToRecognize.addLast(new DataContainer(data, beamSize, languageCode));
                        }
                        if (dataToRecognize.size() >= 1 && !recognizing) {
                            recognize();
                        }
                    }
                }
            }
        });
    }

    public void recognize(final float[] data, int beamSize, final String languageCode1, final String languageCode2) {
        final int gen = generation.get();
        if (enqueueExecutor.isShutdown()) return;   //destroyed: a late chunk from an old reference is dropped
        enqueueExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != generation.get()) return;
                synchronized (lock) {
                    Log.e("recognizer","recognizingCalled");
                    if (data != null) {
                        dataToRecognize.addLast(new DataContainer(data, beamSize, languageCode1, languageCode2));
                        if (dataToRecognize.size() >= 1 && !recognizing) {
                            recognize();
                        }
                    }
                }
            }
        });
    }

    private void recognize() {
        recognizing = true;
        DataContainer data = dataToRecognize.pollFirst();
        final boolean useNeModel = data != null && data.neDual && ensureNeModel();
        final OrtSession encSession = useNeModel ? neEncoderSession : encoderSession;
        final OrtSession cInitSession = useNeModel ? neCacheInitSession : cacheInitSession;
        final OrtSession cInitBatchSession = useNeModel ? neCacheInitBatchSession : cacheInitBatchSession;
        final OrtSession decSession = useNeModel ? neDecoderSession : decoderSession;
        if (initSession != null && encoderSession != null && cacheInitSession != null && decoderSession != null && detokenizer != null) {
            if (data != null) {
                //we convert data in an audioTensor and start the transcription
                try {
                    FloatBuffer floatAudioDataBuffer = FloatBuffer.wrap(data.data);
                    OnnxTensor audioTensor = OnnxTensor.createTensor(onnxEnv, floatAudioDataBuffer, TensorUtils.tensorShape(1L, (long) data.data.length));

                    // if we generate more than this number of tokens it means that we have an infinite loop due to the fact that the sound cannot be transcribed with the language selected
                    int maxTokens = (data.data.length / Recorder.SAMPLE_RATE_CANDIDATES[0]) * MAX_TOKENS_PER_SECOND;
                    if(maxTokens > MAX_TOKENS){
                        maxTokens = MAX_TOKENS;
                    }
                    boolean execution1HitMaxLength = false;
                    boolean execution2HitMaxLength = false;

                    //execution of pre ops
                    long time = System.currentTimeMillis();
                    long startTimeInMs = SystemClock.elapsedRealtime();
                    Map<String, OnnxTensor> initInputs = new LinkedHashMap<>();
                    initInputs.put("audio_pcm", audioTensor);
                    OrtSession.Result outputsInit = initSession.run(initInputs);
                    OnnxTensor outputInit = (OnnxTensor) outputsInit.get(0);
                    android.util.Log.i("performance", "pre ops done in: " + (System.currentTimeMillis()-time) + "ms");
                    audioTensor.close();

                    //execution of the encoder
                    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                    inputs.put("input_features", outputInit);
                    OrtSession.Result outputs = encSession.run(inputs);
                    OnnxTensor outputEncoder = (OnnxTensor) outputs.get(0);
                    android.util.Log.i("performance", "Encoder done in: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                    //execution of decoder
                    final int eos = 50257;
                    ArrayList<Integer> completeOutput = new ArrayList<Integer>();
                    ArrayList<Integer> completeOutput2 = new ArrayList<Integer>();
                    double outputProbability1 = 0;
                    double outputProbability2 = 0;
                    boolean finished1 = false;
                    boolean finished2 = false;
                    long initialTime;

                    OnnxTensor inputIDsTensor = null;
                    OnnxTensor decoderOutput = null;
                    Map<String, OnnxTensor> decoderInput = new HashMap<String, OnnxTensor>();
                    float[][][] value = null;
                    float[] outputValues = null;
                    float[] outputValues2 = null;
                    int batchSize = 1;

                    //with the fine-tuned Nepali model both rows would decode with identical settings (no mask, no
                    //prompt), so the second row is pure waste: decode once
                    if(data.languageCode2 != null && !useNeModel){
                        batchSize = 2;
                    }

                    //We prepare the cache initializer input and execute it
                    Map<String, OnnxTensor> initInput = new HashMap<String, OnnxTensor>();
                    OrtSession.Result initResult = null;
                    if(batchSize == 1) {
                        initInput.put("encoder_hidden_states", outputEncoder);
                        time = System.currentTimeMillis();
                        initResult = cInitSession.run(initInput);
                        android.util.Log.i("performance", "Cache initialization done in: " + (System.currentTimeMillis() - time) + "ms");
                    }else{
                        long timeInner = System.currentTimeMillis();
                        float[][] outputEncoderValue = ((float[][][]) outputEncoder.getValue())[0];
                        android.util.Log.i("performance", "Encoder batch extract done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        timeInner = System.currentTimeMillis();
                        float[][][] outputEncoderFlatBatched = TensorUtils.batchTensor(outputEncoderValue, 2);
                        android.util.Log.i("performance", "Encoder batch batching done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        timeInner = System.currentTimeMillis();
                        OnnxTensor outputEncoderBatched = TensorUtils.createFloatTensor(onnxEnv, outputEncoderFlatBatched, new long[]{2, outputEncoderValue.length, outputEncoderValue[0].length}, new long[]{0});
                        android.util.Log.i("performance", "Encoder batch creation done in: " + (System.currentTimeMillis()-timeInner) + "ms");
                        initInput.put("encoder_hidden_states", outputEncoderBatched);
                        time = System.currentTimeMillis();
                        initResult = cInitBatchSession.run(initInput);
                        outputEncoderBatched.close();
                        android.util.Log.i("performance", "Cache initialization done in: " + (System.currentTimeMillis() - time) + "ms");
                    }

                    //We start the iterative execution of the decoder
                    OrtSession.Result result = null;
                    OrtSession.Result oldResult = null;
                    int max = -1;
                    int max2 = eos;
                    boolean isFirstIteration = true;  //It is used to avoid closing initialResult
                    int j = 1;
                    //first we run the decoder with the first 3 fixed values (START_TOKEN_ID, languageID, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID)
                    int languageID = getLanguageID(data.languageCode);
                    int languageID2 = -1;
                    if(batchSize == 2){
                        languageID2 = getLanguageID(data.languageCode2);
                    }
                    int[] decoderInitialInputIDs = {START_TOKEN_ID, languageID, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID};
                    int[] decoderInitialInputIDs2 = {START_TOKEN_ID, languageID2, TRANSCRIBE_TOKEN_ID, NO_TIMESTAMPS_TOKEN_ID};
                    //the fine-tuned Nepali model is measured best without prompt and mask (tools/whisper_ne/RESULTS.md:
                    //WER 50.9% free vs 76.5% mask+prompt; the mask blocks the byte-level BPE pieces Devanagari needs)
                    if (data.neDual && !useNeModel) {
                        //Peng et al. 2023: a previous-text prompt steers script/vocabulary (Nepali "-छु" form vs Hindi)
                        decoderInitialInputIDs = buildPromptedPrefix(decoderInitialInputIDs);
                        decoderInitialInputIDs2 = buildPromptedPrefix(decoderInitialInputIDs2);
                    }
                    final int prefixLen = decoderInitialInputIDs.length;   //4 without prompt
                    final int firstRecordedStep = prefixLen - 3;   //keeps the original layout: content starts at index 3
                    double noSpeechProb1 = 0;
                    double noSpeechProb2 = 0;
                    
                    while (!(max == eos && max2 == eos)) {
                        initialTime = System.currentTimeMillis();
                        time = System.currentTimeMillis();
                        if (j <= prefixLen) {
                            if(batchSize == 1) {
                                inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{decoderInitialInputIDs[j-1]});
                            }else{
                                inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{decoderInitialInputIDs[j-1], decoderInitialInputIDs2[j-1]}, new long[]{2,1});
                            }
                        }
                        //We prepare the decoder input
                        decoderInput = new HashMap<String, OnnxTensor>();
                        decoderInput.put("input_ids", inputIDsTensor);
                        OnnxTensor decoderPastTensor = null;
                        if (isFirstIteration) {
                            long[] shape = {batchSize, 12, 0, 64};
                            decoderPastTensor = TensorUtils.createFloatTensorWithSingleValue(onnxEnv, 0, shape);
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".decoder.value", decoderPastTensor);
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                            isFirstIteration = false;
                        } else {
                            for (int i = 0; i < 12; i++) {
                                decoderInput.put("past_key_values." + i + ".decoder.key", (OnnxTensor) result.get("present." + i + ".decoder.key").get());
                                decoderInput.put("past_key_values." + i + ".decoder.value", (OnnxTensor) result.get("present." + i + ".decoder.value").get());
                                decoderInput.put("past_key_values." + i + ".encoder.key", (OnnxTensor) initResult.get("present." + i + ".encoder.key").get());
                                decoderInput.put("past_key_values." + i + ".encoder.value", (OnnxTensor) initResult.get("present." + i + ".encoder.value").get());
                            }
                        }
                        oldResult = result;
                        android.util.Log.i("performance", "pre-execution of " + j + "th word done in: " + (System.currentTimeMillis() - time) + "ms");
                        time = System.currentTimeMillis();
                        //execution of decoder (with cache)
                        result = decSession.run(decoderInput);
                        android.util.Log.i("performance", "execution of " + j + "th word done in: " + (System.currentTimeMillis() - time) + "ms");
                        time = System.currentTimeMillis();

                        if (oldResult != null) {
                            oldResult.close(); //serve a rilasciare la memoria occupata dal risultato (altrimenti di accumula e aumenta molto)
                            android.util.Log.i("performance", "release RAM of " + j + "th word done in: " + (System.currentTimeMillis() - time) + "ms");
                        }
                        inputIDsTensor.close();
                        if(decoderPastTensor != null) decoderPastTensor.close();
                        //we extract the logits and the highest value
                        decoderOutput = (OnnxTensor) result.get("logits").get();
                        value = (float[][][]) decoderOutput.getValue();
                        outputValues = value[0][0];
                        if (j == firstRecordedStep) {
                            //Whisper / "Careless Whisper" (2024): no-speech probability at the SOT position
                            noSpeechProb1 = Utils.softmax(outputValues[NO_SPEECH_TOKEN_ID], outputValues);
                            if (batchSize == 2) noSpeechProb2 = Utils.softmax(value[1][0][NO_SPEECH_TOKEN_ID], value[1][0]);
                        }
                        if(!finished1) {
                            max = Utils.getIndexOfLargest(j >= prefixLen ? constrainedLogits(outputValues, useNeModel ? null : data.languageCode, completeOutput) : outputValues);  // scoring keeps the raw logits
                            if (j >= firstRecordedStep) completeOutput.add(max);
                        }
                        if(batchSize == 2){
                            outputValues2 = value[1][0];
                            if(!finished2) {
                                max2 = Utils.getIndexOfLargest(j >= prefixLen ? constrainedLogits(outputValues2, data.neDual ? null : data.languageCode2, completeOutput2) : outputValues2);  // scoring keeps the raw logits
                                if (j >= firstRecordedStep) completeOutput2.add(max2);
                            }
                        }
                        //We prepare the inputs for the next iteration
                        if(batchSize == 1) {
                            inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{max});
                        }else{
                            inputIDsTensor = TensorUtils.convertIntArrayToTensor(onnxEnv, new int[]{max, max2}, new long[]{2,1});
                        }

                        if(batchSize == 2 && j >= firstRecordedStep){
                            //we calculate and update the probabilities of the two output sentences
                            double softmax = Utils.softmax(outputValues[max], outputValues);
                            if(!finished1) {
                                outputProbability1 = outputProbability1 + Math.log(softmax);
                            }
                            if(!finished2) {
                                outputProbability2 = outputProbability2 + Math.log(Utils.softmax(outputValues2[max2], outputValues2));
                            }
                        }

                        if(j >= maxTokens + (prefixLen - 4)) {
                            if (!finished1) {
                                execution1HitMaxLength = true;
                                max = eos;
                            }
                            if (!finished2) {
                                execution2HitMaxLength = true;
                                max2 = eos;
                            }
                        }

                        if (j < prefixLen) {
                            //predictions made while feeding the forced prefix/prompt must not end the decoding
                            if (max == eos) max = -1;
                            if (batchSize == 2 && max2 == eos) max2 = -1;
                        }
                        if(max == eos){
                            finished1 = true;
                        }
                        if(max2 == eos){
                            finished2 = true;
                        }
                        android.util.Log.i("performance", "post-execution of" + j + "th word done in: " + (System.currentTimeMillis() - time) + "ms");
                        android.util.Log.i("performance", "Generation of" + j + "th word done in: " + (System.currentTimeMillis() - initialTime) + "ms");

                        j++;
                    }

                    if(batchSize == 1 && data.neDual && completeOutput.size() > 0) {
                        outputProbability1 = outputProbability1 / completeOutput.size();   //same scale as the dual path's silence rule
                    }
                    if(batchSize == 2) {
                        //we normalize the scores based on the number of tokens of the respective sequence
                        outputProbability1 = outputProbability1 / completeOutput.size();
                        outputProbability2 = outputProbability2 / completeOutput2.size();
                    }

                    //execution of the detokenizer
                    if(batchSize == 1) {
                        String finalText = UNDEFINED_TEXT;
                        if(!execution1HitMaxLength) {
                            int[] sequences = completeOutput.stream().mapToInt(i -> i).toArray();
                            finalText = detokenizer.decode(sequences);
                        }
                        android.util.Log.i("result", "result: " + nie.translator.rtranslator.tools.PrivacyLog.text(correctText(finalText)));
                        android.util.Log.i("score", "score: " + outputProbability1);

                        outputs.close();
                        if (data.neDual) {
                            //single-row Nepali decoding (fine-tuned model): same silence rule as the dual path
                            boolean silent = noSpeechProb1 > NO_SPEECH_THRESHOLD && outputProbability1 < LOGPROB_THRESHOLD;
                            String neText = correctText(finalText);
                            //broken bytes (U+FFFD): report nothing rather than garbage
                            if (!silent && nepaliHardScore(neText, execution1HitMaxLength) >= BROKEN_TEXT_PENALTY) neText = UNDEFINED_TEXT;
                            emitNeResult(data, silent ? UNDEFINED_TEXT : neText, outputProbability1);
                        } else {
                            notifyResult(correctText(finalText), data.languageCode, outputProbability1, true);
                        }

                    }else{
                        String firstText = UNDEFINED_TEXT;
                        if(!execution1HitMaxLength) {
                            int[] sequence1 = completeOutput.stream().mapToInt(i -> i).toArray();
                            firstText = detokenizer.decode(sequence1);
                        }

                        String secondText = UNDEFINED_TEXT;
                        if(!execution2HitMaxLength) {
                            int[] sequence2 = completeOutput2.stream().mapToInt(i -> i).toArray();
                            secondText = detokenizer.decode(sequence2);
                        }

                        android.util.Log.i("result", "result 1: " + nie.translator.rtranslator.tools.PrivacyLog.text(correctText(firstText)));
                        android.util.Log.i("result", "result 2: " + nie.translator.rtranslator.tools.PrivacyLog.text(correctText(secondText)));
                        android.util.Log.i("result", "score 1: " + outputProbability1);
                        android.util.Log.i("result", "score 2: " + outputProbability2);

                        if (data.neDual) {
                            //keep-best with lexicographic acceptance (hard = loop/foreign script, then score)
                            String constrained = correctText(firstText);
                            String free = correctText(secondText);
                            long hard1 = nepaliHardScore(constrained, execution1HitMaxLength);
                            long hard2 = nepaliHardScore(free, execution2HitMaxLength);
                            //Whisper's silence rule: no_speech > 0.6 and avg logprob < -1 -> hallucination on silence
                            boolean silent = Math.max(noSpeechProb1, noSpeechProb2) > NO_SPEECH_THRESHOLD
                                    && Math.max(outputProbability1, outputProbability2) < LOGPROB_THRESHOLD;
                            if (silent) {
                                android.util.Log.i("result", "ne keep-best: dropped as silence, no_speech=" + noSpeechProb1);
                                outputs.close();
                                emitNeResult(data, UNDEFINED_TEXT, outputProbability1);
                            } else {
                            boolean pickFree = hard2 < hard1 || (hard2 == hard1 && outputProbability2 > outputProbability1);
                            android.util.Log.i("result", "ne keep-best: constrained hard=" + hard1 + " free hard=" + hard2 + " -> " + (pickFree ? "free" : "constrained"));
                            outputs.close();
                            emitNeResult(data, pickFree ? free : constrained, pickFree ? outputProbability2 : outputProbability1);
                            }
                        } else {
                            String t1 = correctText(firstText), t2 = correctText(secondText);
                            //Whisper's silence rule, as for Nepali: no_speech > 0.6 and avg logprob < -1 means the text is
                            //a hallucination on silence / noise (on the phone: 「ご視聴ありがとうございました」)
                            if (Math.max(noSpeechProb1, noSpeechProb2) > NO_SPEECH_THRESHOLD
                                    && Math.max(outputProbability1, outputProbability2) < LOGPROB_THRESHOLD) {
                                android.util.Log.i("result", "dropped as silence, no_speech=" + Math.max(noSpeechProb1, noSpeechProb2));
                                t1 = UNDEFINED_TEXT;
                                t2 = UNDEFINED_TEXT;
                            }
                            boolean neFirst = "ne".equals(data.languageCode), neSecond = "ne".equals(data.languageCode2);
                            boolean neWins = (neFirst && outputProbability1 >= outputProbability2) || (neSecond && outputProbability2 > outputProbability1);
                            if (neWins && ensureNeModel()) {
                                // the base model decided "this is Nepali": transcribe again with the Nepali model and
                                // report the result in place of the base-model Nepali text (scores kept, so ne still wins)
                                DataContainer again = new DataContainer(data.data, data.beamSize, "ne", "ne");
                                again.neDual = true;
                                again.reportAsMulti = true;
                                again.neFirst = neFirst;
                                again.neScore = neFirst ? outputProbability1 : outputProbability2;
                                again.otherCode = neFirst ? data.languageCode2 : data.languageCode;
                                again.otherText = neFirst ? t2 : t1;
                                again.otherScore = neFirst ? outputProbability2 : outputProbability1;
                                dataToRecognize.addFirst(again);
                                android.util.Log.i("result", "language detection chose ne: re-transcribing with the Nepali model");
                            } else {
                                notifyMultiResult(t1, data.languageCode, outputProbability1, t2, data.languageCode2, outputProbability2);
                            }
                        }
                    }
                    //closing all results
                    outputs.close();
                    outputInit.close();
                    initResult.close();

                    android.util.Log.i("performance", "SPEECH RECOGNITION DONE IN: " + (SystemClock.elapsedRealtime() - startTimeInMs) + "ms");

                } catch (Exception e) {
                    e.printStackTrace();
                    notifyError(new int[]{ErrorCodes.ERROR_EXECUTING_MODEL}, 0);
                }
            }
        }
        if (!dataToRecognize.isEmpty()){
            recognize();
        }else {
            recognizing = false;
        }
    }

    // ===== MagiTranslator: Nepali decoding constraints =====
    private static final int EOS_TOKEN_ID = 50257;
    private static final int START_OF_PREV_TOKEN_ID = 50361;
    private static final int NO_SPEECH_TOKEN_ID = 50362;
    private static final double NO_SPEECH_THRESHOLD = 0.6;
    private static final double LOGPROB_THRESHOLD = -1.0;
    // " म नेपाली बोल्छु।" ("I speak Nepali") tokenized with the Whisper multilingual tokenizer.
    // Avoid phrases like "धन्यवाद" that Whisper hallucinates on silence.
    private static final int[] NE_PROMPT_IDS = {48449, 8485, 101, 21981, 3941, 103, 17937, 46758, 31881, 8485, 105, 33926, 46758, 27099, 3941, 249, 8703, 223, 8703, 97};

    private static int[] buildPromptedPrefix(int[] basePrefix) {
        int[] out = new int[1 + NE_PROMPT_IDS.length + basePrefix.length];
        out[0] = START_OF_PREV_TOKEN_ID;
        System.arraycopy(NE_PROMPT_IDS, 0, out, 1, NE_PROMPT_IDS.length);
        System.arraycopy(basePrefix, 0, out, 1 + NE_PROMPT_IDS.length, basePrefix.length);
        return out;
    }
    private static final float NE_REPETITION_PENALTY = 1.2f;
    private static final int NE_NO_REPEAT_NGRAM = 3;
    @Nullable
    private boolean[] neAllowedTokens;

    private boolean[] getNeAllowedTokens() {
        if (neAllowedTokens == null) {
            try (java.io.InputStream in = global.getResources().openRawResource(R.raw.whisper_ne_allowed_tokens)) {
                byte[] bits = new byte[in.available()];
                int read = 0;
                while (read < bits.length) {
                    int r = in.read(bits, read, bits.length - read);
                    if (r < 0) break;
                    read += r;
                }
                boolean[] allowed = new boolean[bits.length * 8];
                for (int i = 0; i < allowed.length; i++) {
                    allowed[i] = (bits[i >> 3] & (1 << (i & 7))) != 0;
                }
                neAllowedTokens = allowed;
            } catch (Exception e) {
                e.printStackTrace();
                neAllowedTokens = new boolean[0];
            }
        }
        return neAllowedTokens;
    }

    /**
     * Whisper-small often drifts into Latin/Hindi-romanized text or loops on Nepali audio.
     * For Nepali we (1) allow only Devanagari / punctuation byte tokens, (2) suppress special tokens,
     * (3) apply a repetition penalty and (4) block repeated 3-grams.
     */
    /**
     * Returns the logits used only to pick the next token. The raw logits stay untouched for the
     * probability score, otherwise renormalising over the Devanagari subset would inflate the Nepali
     * score and break the two-language auto detection (e.g. ja vs ne).
     */
    private float[] constrainedLogits(float[] raw, @Nullable String languageCode, ArrayList<Integer> generated) {
        if (!"ne".equals(languageCode)) return raw;
        boolean[] allowed = getNeAllowedTokens();
        if (allowed.length == 0) return raw;
        float[] logits = raw.clone();
        for (int t = 0; t < logits.length; t++) {
            boolean ok = (t < allowed.length && allowed[t]) || t == EOS_TOKEN_ID;
            if (!ok) logits[t] = Float.NEGATIVE_INFINITY;
        }
        int start = Math.min(3, generated.size());  // outputs of the 3 forced prefix steps are not content
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int k = start; k < generated.size(); k++) {
            int t = generated.get(k);
            if (t >= 0 && t < logits.length && t != EOS_TOKEN_ID && seen.add(t)) {
                logits[t] = logits[t] > 0 ? logits[t] / NE_REPETITION_PENALTY : logits[t] * NE_REPETITION_PENALTY;
            }
        }
        int n = NE_NO_REPEAT_NGRAM;
        int len = generated.size();
        if (len - start >= n - 1) {
            for (int k = start; k + n - 1 < len; k++) {
                boolean match = true;
                for (int m = 0; m < n - 1; m++) {
                    if (!generated.get(k + m).equals(generated.get(len - (n - 1) + m))) { match = false; break; }
                }
                if (match) {
                    int banned = generated.get(k + n - 1);
                    if (banned >= 0 && banned < logits.length && banned != EOS_TOKEN_ID) logits[banned] = Float.NEGATIVE_INFINITY;
                }
            }
        }
        return logits;
    }

    private void emitNeResult(DataContainer data, String text, double score) {
        if (data.reportAsMulti) {
            if (data.neFirst) notifyMultiResult(text, "ne", data.neScore, data.otherText, data.otherCode, data.otherScore);
            else notifyMultiResult(data.otherText, data.otherCode, data.otherScore, text, "ne", data.neScore);
        } else {
            notifyResult(text, data.languageCode, score, true);
        }
    }

    /** Lazily loads the Nepali Whisper (files/Whisper_ne/). Returns false (base model is used) when absent or broken. */
    private boolean ensureNeModel() {
        if (neDecoderSession != null) return true;
        if (neModelTried) return false;
        neModelTried = true;
        if (!nie.translator.rtranslator.tools.MemoryGuard.hasRoomFor(global,
                nie.translator.rtranslator.tools.MemoryGuard.sizeOf(new java.io.File(global.getFilesDir().getPath() + "/Whisper_ne/")))) {
            neModelTried = false;   //try again later, the base model is used meanwhile
            android.util.Log.w("recognizer", "not enough free memory for the Nepali Whisper, using the base model");
            return false;
        }
        String dir = global.getFilesDir().getPath() + "/Whisper_ne/";
        String[] names = {"Whisper_encoder.onnx", "Whisper_cache_initializer.onnx", "Whisper_cache_initializer_batch.onnx", "Whisper_decoder.onnx"};
        for (String n : names) {
            if (!new java.io.File(dir + n).exists()) {
                neModelTried = false;   //not installed (yet): look again next time, a later download is then used
                return false;
            }
        }
        OrtSession e = null, c = null, cb = null;
        try (OrtSession.SessionOptions enc = new OrtSession.SessionOptions();
             OrtSession.SessionOptions other = new OrtSession.SessionOptions()) {   //options are copied into the sessions
            enc.setCPUArenaAllocator(true);
            enc.setMemoryPatternOptimization(true);
            enc.setSymbolicDimensionValue("batch_size", 1);
            enc.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            other.setCPUArenaAllocator(false);
            other.setMemoryPatternOptimization(false);
            other.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            e = onnxEnv.createSession(dir + names[0], enc);
            c = onnxEnv.createSession(dir + names[1], other);
            cb = onnxEnv.createSession(dir + names[2], other);
            OrtSession d = onnxEnv.createSession(dir + names[3], other);
            neEncoderSession = e; neCacheInitSession = c; neCacheInitBatchSession = cb; neDecoderSession = d;
            android.util.Log.i("recognizer", "Nepali Whisper loaded");
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            //a broken file: free the sessions already created (native memory), the base model is used from now on
            for (OrtSession partial : new OrtSession[]{e, c, cb}) {
                if (partial != null) try { partial.close(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /** U+FFFD in a hypothesis means invalid UTF-8 from byte-level BPE pieces: never an acceptable transcript. */
    private static final long BROKEN_TEXT_PENALTY = 10_000L;

    /** Hard violations of a Nepali hypothesis: looping, empty output, or letters outside Devanagari. */
    private static long nepaliHardScore(String text, boolean hitMaxLength) {
        if (hitMaxLength || text == null || text.isEmpty() || text.equals(UNDEFINED_TEXT)) return 1_000_000L;
        long foreign = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && (c < '\u0900' || c > '\u097F')) foreign++;
            if (c == '\uFFFD') foreign += BROKEN_TEXT_PENALTY;   //broken byte sequence: worse than any foreign letter
        }
        return foreign;
    }

    private String correctText(String text){
        String correctedText = text;

        //sometimes, even if timestamps are deactivated, Whisper insert those anyway (es. <|0.00|>), so we remove eventual timestamps
        String regex = "<\\|[^>]*\\|> ";    //with this regex we remove all substrings of the form "<|something|> "
        correctedText = correctedText.replaceAll(regex, "");

        //we remove eventual white space from both ends of the text
        correctedText = correctedText.trim();

        if(correctedText.length() >= 2) {
            //if the correctedText start with a lower case letter we make it upper case
            char firstChar = correctedText.charAt(0);
            if (Character.isLowerCase(firstChar)) {
                StringBuilder sb = new StringBuilder(correctedText);
                sb.setCharAt(0, Character.toUpperCase(firstChar));
                correctedText = sb.toString();
            }
            //if the correctedText contains a "..." we remove it
            correctedText = correctedText.replace("...", "");
        }
        if (!correctedText.equals(UNDEFINED_TEXT)) {
            correctedText = stripSubtitleHallucinations(correctedText);
            if (correctedText.isEmpty()) correctedText = UNDEFINED_TEXT;   //only a hallucinated phrase: nothing was said
        }
        return correctedText;
    }

    // Phrases Whisper learnt from video subtitles and writes on silence, noise or the end of speech
    // ("Careless Whisper", 2024; reported for Japanese Whisper output). Removed only at the END of the text,
    // where they are appended; a sentence that merely contains such words in the middle is left as it is.
    private static final java.util.regex.Pattern SUBTITLE_TAIL = java.util.regex.Pattern.compile(
            "(?:(?:^|(?<=[\\s、。,.!！]))\\s*(?:"   //at a sentence boundary; the preceding "。" is kept
                    + "(?:最後まで)?ご視聴(?:いただき|頂き)?(?:本当に|誠に)?ありがとうございました"
                    + "|(?:高評価|グッドボタン)(?:と|、)?チャンネル登録[^。！!]*"
                    + "|チャンネル登録(?:を|も)?(?:よろしく(?:お願いします|お願いいたします)?)?"
                    + "|次回もお楽しみに"
                    + "|(?:字幕|翻訳)(?:作成|提供|制作)?[:：][^。！!]*"
                    + "|Thanks? (?:you )?for watching"
                    + ")[。！!.]*\\s*)+$");

    /** Removes Whisper's subtitle hallucinations from the end of a recognised text (may return ""). */
    static String stripSubtitleHallucinations(String text) {
        return SUBTITLE_TAIL.matcher(text).replaceAll("").trim();
    }

    // this method returns only the languages of Whisper-small that have a minimum quality (wer <= 37%)
    // LANGUAGES instead contains all languages supported by Whisper and it is needed for generating the language ID
    public static ArrayList<CustomLocale> getSupportedLanguages(Context context) {
        ArrayList<CustomLocale> languages = new ArrayList<>();
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        boolean qualityLow = sharedPreferences.getBoolean("languagesNNQualityLow", false);
        if(!qualityLow) {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
                Document document = documentBuilder.parse(context.getResources().openRawResource(R.raw.whisper_supported_languages));
                NodeList list = document.getElementsByTagName("code");
                for (int i = 0; i < list.getLength(); i++) {
                    languages.add(CustomLocale.getInstance(list.item(i).getTextContent()));
                }
            } catch (IOException | SAXException | ParserConfigurationException e) {
                e.printStackTrace();
            }
        }else{
            for (String language : LANGUAGES) {
                languages.add(CustomLocale.getInstance(language));
            }
        }
        return languages;
    }

    /**
     * Drops the chunks waiting for recognition. The queue is only touched under {@code lock}, which recognition holds
     * for a whole utterance, so the clear runs on the recognizer's own thread (callers are on the main thread): chunks
     * handed in before stop() are dropped, chunks handed in after it are kept. The utterance in progress finishes.
     */
    public void stop(){
        generation.incrementAndGet();   // chunks still waiting in the executor are skipped
        if (enqueueExecutor.isShutdown()) return;
        enqueueExecutor.execute(() -> {
            synchronized (lock) {
                dataToRecognize.clear();
            }
        });
    }

    /**
     * Releases the sessions and the recognizer thread (Global.deleteSpeechRecognizer, e.g. after a failed load, before
     * the next load creates a new Recognizer). Queued chunks are dropped; an utterance in progress finishes first,
     * because the close runs on the recognizer's own thread under {@code lock}.
     */
    public void destroy() {
        generation.incrementAndGet();
        try {
            enqueueExecutor.execute(() -> {
                synchronized (lock) {
                    dataToRecognize.clear();
                    for (OrtSession s : new OrtSession[]{session, initSession, encoderSession, cacheInitSession, cacheInitBatchSession,
                            decoderSession, neEncoderSession, neCacheInitSession, neCacheInitBatchSession, neDecoderSession}) {
                        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
                    }
                    session = initSession = encoderSession = cacheInitSession = cacheInitBatchSession = decoderSession = null;
                    neEncoderSession = neCacheInitSession = neCacheInitBatchSession = neDecoderSession = null;
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException alreadyDestroyed) {
            return;
        }
        enqueueExecutor.shutdown();   //the thread ends after the close above
    }

    public int getLanguageID(String language){
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(language)) {
                return START_TOKEN_ID + i + 1;
            }
        }
        Log.e("error", "Error Converting Language code " + language + " to Whisper code");
        return -1;
    }

    public void addCallback(final RecognizerListener callback) {
        callbacks.add(callback);
    }

    public void removeCallback(RecognizerListener callback) {
        callbacks.remove(callback);
    }

    public void addMultiCallback(final RecognizerMultiListener callback) {
        multiCallbacks.add(callback);
    }

    public void removeMultiCallback(RecognizerMultiListener callback) {
        multiCallbacks.remove(callback);
    }

    private void notifyResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onSpeechRecognizedResult(text, languageCode, confidenceScore, isFinal);
        }
    }

    private void notifyMultiResult(String text1, String languageCode1, double confidenceScore1, String text2, String languageCode2, double confidenceScore2) {
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onSpeechRecognizedResult(text1, languageCode1, confidenceScore1, text2, languageCode2, confidenceScore2);
        }
    }

    private void notifyError(int[] reasons, long value) {
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onError(reasons, value);
        }
        for (int i = 0; i < multiCallbacks.size(); i++) {
            multiCallbacks.get(i).onError(reasons, value);
        }
    }


    private static class DataContainer{
        private float[] data;
        private String languageCode;
        private String languageCode2;
        private boolean neDual = false;
        // set when a 2-language detection chose Nepali and the audio is re-transcribed by the Nepali model
        private boolean reportAsMulti = false;
        private boolean neFirst = true;
        private double neScore = 0;
        private String otherCode;
        private String otherText;
        private double otherScore = 0;
        private int beamSize;

        private DataContainer(float[] data, int beamSize, String languageCode){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
        }

        private DataContainer(float[] data, int beamSize, String languageCode, String languageCode2){
            this.data = data;
            this.beamSize = beamSize;
            this.languageCode = languageCode;
            this.languageCode2 = languageCode2;
        }
    }

    // ===== MagiTranslator: memory pressure (spec v2 part 11) =====
    /**
     * Closes the optional Nepali Whisper sessions when nothing is being recognised; ensureNeModel() reloads them
     * on the next Nepali utterance. The base model stays loaded (it serves every language). Never waits.
     */
    /**
     * Asks for the Nepali Whisper sessions to be released when the recognizer is idle. Called from onTrimMemory /
     * onLowMemory on the main thread: recognition runs while holding {@code lock}, so waiting for the lock here
     * blocked the main thread for a whole utterance. The release now runs on the recognizer's own queue (after the
     * utterance in progress) and this returns at once: true when the Nepali model is loaded and a release was queued.
     */
    public boolean releaseIdleNeModel() {
        if (neDecoderSession == null || enqueueExecutor.isShutdown()) return false;   //racy read, only to skip an obviously useless request
        enqueueExecutor.execute(this::releaseIdleNeModelNow);
        return true;
    }

    private void releaseIdleNeModelNow() {
        synchronized (lock) {
            if (recognizing || !dataToRecognize.isEmpty() || neDecoderSession == null) return;
            try {
                neEncoderSession.close();
                neCacheInitSession.close();
                neCacheInitBatchSession.close();
                neDecoderSession.close();
            } catch (Exception e) {
                android.util.Log.w("recognizer", "Nepali Whisper release failed: " + e.getClass().getSimpleName());
            }
            neEncoderSession = null;
            neCacheInitSession = null;
            neCacheInitBatchSession = null;
            neDecoderSession = null;
            neModelTried = false;   //allow the lazy reload
            android.util.Log.i("recognizer", "Nepali Whisper released on memory pressure");
        }
    }
}
