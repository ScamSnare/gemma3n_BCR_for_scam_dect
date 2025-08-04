import android.util.Log
import android.os.Environment
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import android.content.Context


class ModelManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelManager"
    }

    // Download the model from https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task?download=true
    private val MODEL_NAME = "gemma-3n-E2B-it-int4.task"
    private val WEIGHT_FOLDER_PATH = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
    private val preferredBackend = LlmInference.Backend.CPU
    
    private val modelPath: String = "$WEIGHT_FOLDER_PATH/$MODEL_NAME"
    private var llmInference: LlmInference? = null
    private val session: LlmInferenceSession? = null

    fun loadModel() {
        
        // Check if the model is already loaded
        if (this.llmInference != null) {
            Log.d(TAG, "Model already loaded.")
            return
        }

        // Check if the model file exists
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file does not exist at path: ${modelFile.absolutePath}")
            return
        }

        // Load the model from path and set options
        Log.d(TAG, "Loading model from path: $modelPath")
        val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setPreferredBackend(preferredBackend)
            .setMaxNumImages(0)
        val options = optionsBuilder.build()


        // Create an instance of the LLM Inference task
        val llmInference = LlmInference.createFromOptions(context, options)

        val session =LlmInferenceSession.createFromOptions(
            llmInference,
            LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(10)
                .setTopP(0.9f)
                .setTemperature(0.1f)
                .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(false)
                    .build()
                )
                .build(),
        )

        session.addQueryChunk("Hello, Gemma!")

        val res = session.generateResponse()
        Log.d(TAG, "Inference result: $res")
    }    
}