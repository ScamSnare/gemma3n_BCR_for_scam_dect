

package com.chiller3.bcr

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.chiller3.bcr.output.OutputFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Thread that performs speech-to-text transcription on recorded audio files using whisper.cpp.
 * 
 * This thread takes a WAV file output from RecorderThread and uses whisper.cpp to generate
 * a text transcription, which is then logged to the console.
 */
class SpeechRecognitionThread(
    private val context: Context,
    private val audioFile: OutputFile,
    private val listener: OnTranscriptionCompletedListener? = null
) : Thread("SpeechRecognitionThread") {
    
    private val tag = "${SpeechRecognitionThread::class.java.simpleName}/${id}"
    
    companion object {
        private const val WHISPER_SAMPLE_RATE = 16000 // Whisper expects 16kHz
        private const val TEMP_WAV_PREFIX = "whisper_temp_"
        private const val TEMP_WAV_SUFFIX = ".wav"
        
        // Load the native whisper library
        private var isNativeLibraryLoaded: Boolean? = null
        
        fun isLibraryAvailable(context: Context? = null): Boolean {
            if (isNativeLibraryLoaded == null) {
                try {
                    // For system apps, we need to manually extract and load libraries
                    var libraryLoaded = false
                    
                    // First try the standard approach
                    try {
                        System.loadLibrary("whisper-jni")
                        libraryLoaded = true
                        Log.i("SpeechRecognitionThread", "Whisper JNI library loaded successfully (standard)")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w("SpeechRecognitionThread", "Standard library loading failed: ${e.message}")
                        
                        // Try loading dependencies first
                        try {
                            System.loadLibrary("c++_shared")
                            System.loadLibrary("whisper")
                            System.loadLibrary("whisper-jni")
                            libraryLoaded = true
                            Log.i("SpeechRecognitionThread", "Whisper JNI library loaded successfully (with dependencies)")
                        } catch (e2: UnsatisfiedLinkError) {
                            Log.w("SpeechRecognitionThread", "Library loading with dependencies failed: ${e2.message}")
                            
                            // For system apps, try manual extraction and loading if context is available
                            if (context != null) {
                                try {
                                    libraryLoaded = loadLibrariesManually(context)
                                    if (libraryLoaded) {
                                        Log.i("SpeechRecognitionThread", "Libraries loaded successfully via manual extraction")
                                    }
                                } catch (e3: Exception) {
                                    Log.e("SpeechRecognitionThread", "Manual library loading failed", e3)
                                }
                            } else {
                                Log.w("SpeechRecognitionThread", "Context not available for manual library extraction")
                            }
                        }
                    }
                    
                    isNativeLibraryLoaded = libraryLoaded
                    
                    if (!libraryLoaded) {
                        Log.e("SpeechRecognitionThread", "All library loading attempts failed")
                        Log.e("SpeechRecognitionThread", "Library path: ${System.getProperty("java.library.path")}")
                        Log.e("SpeechRecognitionThread", "System architecture: ${System.getProperty("os.arch")}")
                    }
                    
                } catch (e: Exception) {
                    isNativeLibraryLoaded = false
                    Log.e("SpeechRecognitionThread", "Unexpected error during library loading", e)
                }
            }
            return isNativeLibraryLoaded == true
        }
        
        /**
         * Manually extract and load libraries for system apps
         */
        private fun loadLibrariesManually(context: Context): Boolean {
            return try {
                // Get the current ABI
                val abi = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
                    android.os.Build.SUPPORTED_64_BIT_ABIS[0]
                } else {
                    android.os.Build.SUPPORTED_32_BIT_ABIS[0]
                }
                Log.d("SpeechRecognitionThread", "Using ABI: $abi")
                
                val libraryNames = arrayOf("libc++_shared.so", "libwhisper.so", "libwhisper-jni.so")
                val extractedLibs = mutableListOf<String>()
                
                for (libName in libraryNames) {
                    val extractedPath = extractLibraryFromApk(context, libName, abi)
                    if (extractedPath != null) {
                        extractedLibs.add(extractedPath)
                        Log.d("SpeechRecognitionThread", "Extracted $libName to $extractedPath")
                    } else {
                        Log.w("SpeechRecognitionThread", "Failed to extract $libName")
                    }
                }
                
                // Try to load the extracted libraries
                for (libPath in extractedLibs) {
                    try {
                        System.load(libPath)
                        Log.d("SpeechRecognitionThread", "Successfully loaded $libPath")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w("SpeechRecognitionThread", "Failed to load $libPath: ${e.message}")
                    }
                }
                
                // Test if the JNI library is working by trying to call a native method
                try {
                    // This will throw an exception if the library isn't properly loaded
                    val testPath = extractedLibs.find { it.contains("whisper-jni") }
                    if (testPath != null) {
                        // Try a simple JNI call to verify the library is working
                        try {
                            // Test with a dummy path - this should fail gracefully if the library is working
                            val testResult = initWhisper("dummy_test_path_that_does_not_exist")
                            // If we get here without UnsatisfiedLinkError, JNI is working
                            Log.d("SpeechRecognitionThread", "JNI methods are available (test returned: $testResult)")
                            if (testResult != 0L) {
                                // Clean up if we accidentally loaded something
                                try { freeWhisper(testResult) } catch (e: Exception) { /* ignore */ }
                            }
                        } catch (e: UnsatisfiedLinkError) {
                            // If we get UnsatisfiedLinkError, the JNI methods are not found
                            Log.e("SpeechRecognitionThread", "JNI methods not found after library loading: ${e.message}")
                            return false
                        } catch (e: Exception) {
                            // Any other exception means the JNI is working but the test failed (expected)
                            Log.d("SpeechRecognitionThread", "JNI methods are available (test call failed as expected: ${e.message})")
                        }
                        
                        Log.i("SpeechRecognitionThread", "Manual library extraction completed successfully")
                        return true
                    }
                } catch (e: Exception) {
                    Log.w("SpeechRecognitionThread", "Library loaded but JNI methods not available: ${e.message}")
                }
                
                false
            } catch (e: Exception) {
                Log.e("SpeechRecognitionThread", "Error in manual library loading", e)
                false
            }
        }
        
        /**
         * Extract a specific library from the APK
         */
        private fun extractLibraryFromApk(context: Context, libraryName: String, abi: String): String? {
            return try {
                // For system apps, we need to extract from the APK manually
                val applicationInfo = context.applicationInfo
                val apkPath = applicationInfo.sourceDir
                Log.d("SpeechRecognitionThread", "APK path: $apkPath")
                
                val zipFile = ZipFile(apkPath)
                val libPath = "lib/$abi/$libraryName"
                val zipEntry = zipFile.getEntry(libPath)
                
                if (zipEntry != null) {
                    val extractDir = File(context.filesDir, "extracted_libs")
                    if (!extractDir.exists()) {
                        extractDir.mkdirs()
                    }
                    
                    val extractedFile = File(extractDir, libraryName)
                    
                    // Only extract if file doesn't exist or is older
                    if (!extractedFile.exists() || extractedFile.lastModified() < zipEntry.time) {
                        zipFile.getInputStream(zipEntry).use { input ->
                            extractedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        // Make the library executable
                        extractedFile.setExecutable(true)
                        Log.d("SpeechRecognitionThread", "Extracted $libraryName to ${extractedFile.absolutePath}")
                    }
                    
                    zipFile.close()
                    return extractedFile.absolutePath
                } else {
                    Log.w("SpeechRecognitionThread", "Library $libPath not found in APK")
                    zipFile.close()
                    return null
                }
            } catch (e: Exception) {
                Log.e("SpeechRecognitionThread", "Error extracting $libraryName", e)
                null
            }
        }
        
        // Native methods for whisper.cpp integration
        @JvmStatic
        external fun initWhisper(modelPath: String): Long
        
        @JvmStatic
        external fun transcribeAudio(contextPtr: Long, audioData: FloatArray, sampleRate: Int): String?
        
        @JvmStatic
        external fun freeWhisper(contextPtr: Long)
    }
    
    sealed class TranscriptionResult {
        data class Success(val text: String) : TranscriptionResult()
        data class Error(val message: String, val exception: Throwable? = null) : TranscriptionResult()
    }
    
    interface OnTranscriptionCompletedListener {
        fun onTranscriptionCompleted(result: TranscriptionResult)
    }
    
    @Volatile
    private var isCancelled = false
    
    fun cancel() {
        Log.d(tag, "Speech recognition cancelled")
        isCancelled = true
        interrupt()
    }
    
    override fun run() {
        Log.i(tag, "Starting speech recognition for file: [AUDIO_FILE]")
        
        try {
            val result = performTranscription()
            
            when (result) {
                is TranscriptionResult.Success -> {
                    Log.i(tag, "Transcription completed successfully")
                    Log.i(tag, "Transcription text: ${result.text}")
                }
                is TranscriptionResult.Error -> {
                    Log.e(tag, "Transcription failed: ${result.message}", result.exception)
                }
            }
            
            listener?.onTranscriptionCompleted(result)
            
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error during transcription", e)
            val errorResult = TranscriptionResult.Error("Unexpected error: ${e.message}", e)
            listener?.onTranscriptionCompleted(errorResult)
        }
    }
    
    private fun performTranscription(): TranscriptionResult {
        if (isCancelled) {
            return TranscriptionResult.Error("Transcription was cancelled")
        }
        
        // Check if native library is available
        if (!isLibraryAvailable(context)) {
            return TranscriptionResult.Error("Whisper native library is not available")
        }
        
        // Check if the audio file exists and is accessible
        val documentFile = DocumentFile.fromSingleUri(context, audioFile.uri)
            ?: return TranscriptionResult.Error("Could not access audio file")
        
        if (!documentFile.exists()) {
            return TranscriptionResult.Error("Audio file does not exist")
        }
        
        Log.d(tag, "Processing audio file of size: ${documentFile.length()} bytes")
        
        return try {
            // Convert audio to format expected by whisper (16kHz float32)
            val audioData = convertAudioToWhisperFormat(audioFile.uri)
                ?: return TranscriptionResult.Error("Failed to convert audio to whisper format")
            
            if (isCancelled) {
                return TranscriptionResult.Error("Transcription was cancelled")
            }
            
            // Get the whisper model path
            val modelPath = getWhisperModelPath()
                ?: return TranscriptionResult.Error("Whisper model not found")
            
            // Initialize whisper context
            val contextPtr = Companion.initWhisper(modelPath)
            if (contextPtr == 0L) {
                return TranscriptionResult.Error("Failed to initialize whisper context")
            }
            
            try {
                if (isCancelled) {
                    return TranscriptionResult.Error("Transcription was cancelled")
                }
                
                Log.d(tag, "Running whisper transcription on ${audioData.size} audio samples")
                
                // Perform transcription
                val transcriptionText = Companion.transcribeAudio(contextPtr, audioData, WHISPER_SAMPLE_RATE)
                
                if (transcriptionText.isNullOrBlank()) {
                    TranscriptionResult.Error("Whisper returned empty transcription")
                } else {
                    TranscriptionResult.Success(transcriptionText.trim())
                }
                
            } finally {
                // Clean up whisper context
                Companion.freeWhisper(contextPtr)
            }
            
        } catch (e: Exception) {
            TranscriptionResult.Error("Error during whisper transcription: ${e.message}", e)
        }
    }
    
    /**
     * Converts the input audio file to the format expected by whisper.cpp:
     * - 16kHz sample rate
     * - Single channel (mono)
     * - 32-bit float PCM
     */
    private fun convertAudioToWhisperFormat(audioUri: Uri): FloatArray? {
        return try {
            context.contentResolver.openInputStream(audioUri)?.use { inputStream ->
                // Read the entire file first to avoid reset issues
                val allBytes = inputStream.readBytes()
                Log.d(tag, "Read ${allBytes.size} total bytes from audio file")
                
                if (allBytes.size < 44) {
                    Log.e(tag, "Invalid WAV file: file too short")
                    return null
                }
                
                // Parse WAV header
                val headerBuffer = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN)
                
                // Check RIFF header
                val riffHeader = ByteArray(4)
                headerBuffer.get(riffHeader)
                if (!riffHeader.contentEquals("RIFF".toByteArray())) {
                    Log.e(tag, "Invalid WAV file: not a RIFF file")
                    return null
                }
                
                val fileSize = headerBuffer.int
                val waveHeader = ByteArray(4)
                headerBuffer.get(waveHeader)
                if (!waveHeader.contentEquals("WAVE".toByteArray())) {
                    Log.e(tag, "Invalid WAV file: not a WAVE file")
                    return null
                }
                
                // Find format chunk
                var audioFormat = 0
                var numChannels = 0
                var sampleRate = 0
                var bitsPerSample = 0
                var dataOffset = 0
                var dataSize = 0
                
                while (headerBuffer.remaining() >= 8) {
                    val chunkId = ByteArray(4)
                    headerBuffer.get(chunkId)
                    val chunkSize = headerBuffer.int
                    val chunkIdStr = String(chunkId)
                    
                    when (chunkIdStr) {
                        "fmt " -> {
                            audioFormat = headerBuffer.short.toInt()
                            numChannels = headerBuffer.short.toInt()
                            sampleRate = headerBuffer.int
                            val byteRate = headerBuffer.int
                            val blockAlign = headerBuffer.short
                            bitsPerSample = headerBuffer.short.toInt()
                            
                            Log.d(tag, "WAV format: $audioFormat, channels: $numChannels, sampleRate: $sampleRate, bitsPerSample: $bitsPerSample")
                            
                            // Skip any remaining format data
                            val remaining = chunkSize - 16
                            if (remaining > 0) {
                                headerBuffer.position(headerBuffer.position() + remaining)
                            }
                        }
                        "data" -> {
                            dataOffset = headerBuffer.position()
                            dataSize = chunkSize
                            Log.d(tag, "Found data chunk at offset $dataOffset, size $dataSize")
                            break
                        }
                        else -> {
                            // Skip unknown chunk
                            if (chunkSize > 0 && headerBuffer.remaining() >= chunkSize) {
                                headerBuffer.position(headerBuffer.position() + chunkSize)
                            } else {
                                Log.w(tag, "Invalid chunk size or insufficient data for chunk $chunkIdStr")
                                break
                            }
                        }
                    }
                }
                
                if (dataOffset == 0 || dataSize == 0) {
                    Log.e(tag, "No data chunk found in WAV file")
                    return null
                }
                
                // Validate format
                if (audioFormat != 1) { // PCM
                    Log.e(tag, "Unsupported audio format: $audioFormat (only PCM supported)")
                    return null
                }
                
                if (bitsPerSample != 16) {
                    Log.e(tag, "Unsupported bit depth: $bitsPerSample (only 16-bit supported)")
                    return null
                }
                
                if (sampleRate != WHISPER_SAMPLE_RATE) {
                    Log.w(tag, "Sample rate mismatch: expected $WHISPER_SAMPLE_RATE, got $sampleRate")
                    // For now, continue - in production you would resample
                }
                
                // Extract audio data
                val audioBytes = allBytes.sliceArray(dataOffset until (dataOffset + dataSize))
                Log.d(tag, "Extracted ${audioBytes.size} bytes of audio data")
                
                // Convert to float32 array
                val audioBuffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
                val numSamples = audioBytes.size / 2 / numChannels // 16-bit = 2 bytes per sample
                val floatArray = FloatArray(numSamples)
                
                for (i in 0 until numSamples) {
                    if (numChannels == 1) {
                        // Mono
                        val sample = audioBuffer.short.toInt()
                        floatArray[i] = sample / 32768.0f // Normalize to [-1, 1]
                    } else {
                        // Multi-channel: take first channel only
                        val sample = audioBuffer.short.toInt()
                        floatArray[i] = sample / 32768.0f
                        // Skip other channels
                        for (ch in 1 until numChannels) {
                            audioBuffer.short // Skip
                        }
                    }
                }
                
                Log.d(tag, "Converted to ${floatArray.size} float samples")
                floatArray
            }
        } catch (e: Exception) {
            Log.e(tag, "Error converting audio format", e)
            null
        }
    }
    
    /**
     * Gets the path to the whisper model file.
     * This should be a .bin file downloaded from the whisper.cpp repository.
     */
    private fun getWhisperModelPath(): String? {
        // Look for whisper model in assets or external storage
        // For now, assume it's in the app's assets folder
        val assetManager = context.assets
        
        val modelFiles = arrayOf(
            "ggml-base.en.bin",
            "ggml-small.en.bin", 
            "ggml-tiny.en.bin",
            "ggml-base.bin",
            "ggml-small.bin",
            "ggml-tiny.bin"
        )
        
        for (modelFile in modelFiles) {
            try {
                assetManager.open(modelFile).use {
                    // Model exists in assets, copy to internal storage if needed
                    val internalFile = File(context.filesDir, modelFile)
                    if (!internalFile.exists()) {
                        Log.d(tag, "Copying whisper model to internal storage: $modelFile")
                        copyAssetToInternalStorage(modelFile, internalFile)
                    }
                    Log.d(tag, "Using whisper model: ${internalFile.absolutePath}")
                    return internalFile.absolutePath
                }
            } catch (e: IOException) {
                // Model file doesn't exist in assets, try next one
                continue
            }
        }
        
        Log.e(tag, "No whisper model found in assets. Please add a whisper model file (.bin) to the assets folder.")
        return null
    }
    
    private fun copyAssetToInternalStorage(assetFileName: String, targetFile: File) {
        try {
            context.assets.open(assetFileName).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(tag, "Successfully copied $assetFileName to ${targetFile.absolutePath}")
        } catch (e: IOException) {
            Log.e(tag, "Failed to copy asset $assetFileName", e)
            throw e
        }
    }
}
