#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_chiller3_bcr_SpeechRecognitionThread_initWhisper(JNIEnv *env, jclass clazz, jstring model_path) {
    const char *modelPath = env->GetStringUTFChars(model_path, nullptr);
    if (!modelPath) {
        LOGE("Failed to get model path");
        return 0;
    }

    LOGD("Initializing whisper with model: %s", modelPath);
    
    // Initialize whisper parameters
    struct whisper_context_params cparams = whisper_context_default_params();
    
    // Load the model
    struct whisper_context *ctx = whisper_init_from_file_with_params(modelPath, cparams);
    
    env->ReleaseStringUTFChars(model_path, modelPath);
    
    if (!ctx) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }
    
    LOGD("Whisper context initialized successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_chiller3_bcr_SpeechRecognitionThread_transcribeAudio(JNIEnv *env, jclass clazz, 
                                                              jlong context_ptr, 
                                                              jfloatArray audio_data, 
                                                              jint sample_rate) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (!ctx) {
        LOGE("Invalid whisper context");
        return nullptr;
    }
    
    // Get audio data from Java
    jfloat *audioArray = env->GetFloatArrayElements(audio_data, nullptr);
    jsize audioLength = env->GetArrayLength(audio_data);
    
    if (!audioArray) {
        LOGE("Failed to get audio data");
        return nullptr;
    }
    
    LOGD("Transcribing audio: %d samples at %d Hz", audioLength, sample_rate);
    
    // Set up whisper parameters
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    
    // Configure parameters for better performance
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;  // Don't translate, just transcribe
    wparams.language         = "en";   // Set to English, change as needed
    wparams.n_threads        = 1;      // Use single thread for mobile
    wparams.offset_ms        = 0;
    wparams.duration_ms      = 0;      // Process entire audio
    
    // Run the inference
    int result = whisper_full(ctx, wparams, audioArray, audioLength);
    
    env->ReleaseFloatArrayElements(audio_data, audioArray, JNI_ABORT);
    
    if (result != 0) {
        LOGE("Whisper inference failed with code: %d", result);
        return nullptr;
    }
    
    // Get the number of segments
    const int n_segments = whisper_full_n_segments(ctx);
    LOGD("Transcription completed with %d segments", n_segments);
    
    if (n_segments == 0) {
        LOGD("No segments detected in audio");
        return env->NewStringUTF("");
    }
    
    // Concatenate all segments
    std::string fullText;
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text) {
            if (i > 0) {
                fullText += " ";
            }
            fullText += text;
        }
    }
    
    LOGD("Final transcription: %s", fullText.c_str());
    return env->NewStringUTF(fullText.c_str());
}

JNIEXPORT void JNICALL
Java_com_chiller3_bcr_SpeechRecognitionThread_freeWhisper(JNIEnv *env, jclass clazz, jlong context_ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (ctx) {
        LOGD("Freeing whisper context");
        whisper_free(ctx);
    }
}

} // extern "C"
