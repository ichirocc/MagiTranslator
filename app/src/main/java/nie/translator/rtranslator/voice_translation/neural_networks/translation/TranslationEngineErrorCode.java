package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import ai.onnxruntime.OrtException;
import nie.translator.rtranslator.tools.ErrorCodes;

/**
 * Error classification of the local translation engine (SPEC v2 part 3 §18.1).
 * The cause is kept apart from what the UI shows: {@link #toLegacyCode()} maps to the existing
 * {@link ErrorCodes} the listeners already understand, so screens keep working unchanged.
 * Never put source/target text in an error (PrivacyLog rule).
 */
public enum TranslationEngineErrorCode {
    MODEL_NOT_INSTALLED,
    MODEL_DOWNLOAD_FAILED,
    MODEL_CHECKSUM_MISMATCH,
    MODEL_SIGNATURE_INVALID,
    MODEL_LOAD_FAILED,
    TOKENIZER_MISMATCH,
    LANGUAGE_ID_UNSUPPORTED,
    RUNTIME_UNAVAILABLE,
    EXECUTION_PROVIDER_UNAVAILABLE,
    INPUT_TOO_LONG,
    TOKEN_LIMIT_EXCEEDED,
    INFERENCE_TIMEOUT,
    INFERENCE_CANCELLED,
    OUT_OF_MEMORY,
    INVALID_MODEL_OUTPUT,
    DECODING_FAILED,
    RESOURCE_INCOMPATIBLE,
    INTERNAL_ERROR;

    /** Whether trying again with the same settings can help (§18.5: no blind retry after OOM / integrity errors). */
    public boolean isRetryable() {
        switch (this) {
            case MODEL_DOWNLOAD_FAILED:
            case INFERENCE_TIMEOUT:
            case RUNTIME_UNAVAILABLE:
                return true;
            default:
                return false;
        }
    }

    /** Code delivered to the existing listeners ({@code onFailure(int[] reasons, ...)}). */
    public int toLegacyCode() {
        switch (this) {
            case MODEL_NOT_INSTALLED:
                return ErrorCodes.ERROR_MISSING_MADLAD;
            case MODEL_LOAD_FAILED:
            case MODEL_CHECKSUM_MISMATCH:
            case MODEL_SIGNATURE_INVALID:
            case TOKENIZER_MISMATCH:
            case RESOURCE_INCOMPATIBLE:
                return ErrorCodes.ERROR_LOADING_MODEL;
            case LANGUAGE_ID_UNSUPPORTED:
                return ErrorCodes.ERROR_LANGUAGE_NOT_SUPPORTED;
            case OUT_OF_MEMORY:
                return ErrorCodes.ERROR_OUT_OF_MEMORY;
            case INFERENCE_CANCELLED:
                return ErrorCodes.ERROR_TRANSLATION_CANCELLED;
            default:
                return ErrorCodes.ERROR_EXECUTING_MODEL;
        }
    }

    /** Classifies a failure of the inference path. The cause chain is walked so wrapped errors are not lost. */
    public static TranslationEngineErrorCode fromThrowable(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof TranslationCancelledException) return INFERENCE_CANCELLED;
            if (c instanceof OutOfMemoryError) return OUT_OF_MEMORY;
            if (c instanceof OrtException) {
                OrtException oe = (OrtException) c;
                String msg = oe.getMessage() == null ? "" : oe.getMessage().toLowerCase(java.util.Locale.ROOT);
                // RunOptions.setTerminate(true) makes the native run fail with "Exiting due to terminate flag"
                if (msg.contains("terminate")) return INFERENCE_CANCELLED;
                if (msg.contains("bad_alloc") || msg.contains("out of memory") || msg.contains("failed to allocate")) return OUT_OF_MEMORY;
                if (oe.getCode() == OrtException.OrtErrorCode.ORT_NO_MODEL || oe.getCode() == OrtException.OrtErrorCode.ORT_NO_SUCHFILE) return MODEL_NOT_INSTALLED;
                if (oe.getCode() == OrtException.OrtErrorCode.ORT_INVALID_PROTOBUF || oe.getCode() == OrtException.OrtErrorCode.ORT_INVALID_GRAPH) return MODEL_LOAD_FAILED;
                return DECODING_FAILED;
            }
            if (c instanceof java.io.FileNotFoundException) return MODEL_NOT_INSTALLED;
        }
        return INTERNAL_ERROR;
    }
}
