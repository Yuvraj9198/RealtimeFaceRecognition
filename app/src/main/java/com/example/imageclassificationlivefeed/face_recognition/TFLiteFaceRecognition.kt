package com.example.facerecognitionimages.face_recognition

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import android.util.Pair
import com.example.facerecognitionimages.DB.DBHelper
import com.example.facerecognitionimages.face_recognition.FaceClassifier.Recognition
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteFaceRecognition private constructor(ctx: Context) : FaceClassifier {
    private var isModelQuantized = false

    // Config values.
    private var inputSize = 0
    private lateinit var intValues: IntArray
    private lateinit var embeedings: Array<FloatArray>
    private var imgData: ByteBuffer? = null
    private var tfLite: Interpreter? = null
    var dbHelper: DBHelper
    private var registered = HashMap<String?, Recognition>()
    override fun register(name: String?, rec: Recognition?) {
        if (name == null || rec == null || rec.embeeding == null || rec.crop == null) {
            Log.e("FaceRecognition", "Failed to register: Missing name, recognition, embedding or face image")
            return
        }
        Log.d("FaceRecognition", "Inserting face with name: $name and embedding: ${rec.embeeding.toString()}")
        dbHelper.insertFace(name, rec.embeeding!!, rec.crop!!)
        registered[name] = rec
    }

    init {
        dbHelper = DBHelper(ctx)
        registered.clear()
        dbHelper.allFaces.forEach { (_, recognition) ->
            val key = recognition.title ?: "Unknown"
            registered[key] = recognition
        }
    }

    //TODO  looks for the nearest embeeding in the dataset
    // and retrurns the pair <id, distance>
    private fun findNearest(emb: FloatArray): Pair<String?, Float>? {
        var ret: Pair<String?, Float>? = null
        //Log.d("tryRes","Above= "+ ret!!.first+"   "+ret!!.second)
        for ((name, value) in registered) {
            val knownEmb = (value.embeeding as Array<FloatArray>?)!![0]
            Log.d("tryResiN",name+"   "+knownEmb.contentToString());
            var distance = 0f
            for (i in emb.indices) {
                val diff = emb[i] - knownEmb[i]
                distance += diff * diff
            }
            distance = Math.sqrt(distance.toDouble()).toFloat()
            if (ret == null || distance < ret.second) {
                ret = Pair(name, distance)
            }
        }
        Log.d("tryRes","Below= "+ ret!!.first+"   "+ret!!.second)
        return ret
    }

    //TODO TAKE INPUT IMAGE AND RETURN RECOGNITIONS
    override fun recognizeImage(bitmap: Bitmap?, storeExtra: Boolean): Recognition? {
        val byteBuffer = ByteBuffer.allocateDirect(4 * bitmap!!.width * bitmap!!.width * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(bitmap!!.width * bitmap!!.width)

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until bitmap!!.width) {
            for (j in 0 until bitmap!!.width) {
                val input = intValues[pixel++]
                byteBuffer.putFloat((((input.shr(16)  and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
                byteBuffer.putFloat((((input.shr(8) and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
                byteBuffer.putFloat((((input and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
            }
        }

        val inputArray = arrayOf<Any?>(byteBuffer)
        // Here outputMap is changed to fit the Face Mask detector
        val outputMap: MutableMap<Int, Any> = HashMap()
        embeedings = Array(1) { FloatArray(OUTPUT_SIZE) }
        outputMap[0] = embeedings
        // Run the inference call.
        tfLite!!.runForMultipleInputsOutputs(inputArray, outputMap)
        Log.d("tryResr",embeedings[0].contentToString())
        var distance = Float.MAX_VALUE
        val id = "0"
        var label: String? = "?"
        if (registered.size > 0) {
            val nearest = findNearest(embeedings[0])
            if (nearest != null) {
                val name = nearest.first
                label = name
                distance = nearest.second
            }
        }

        val rec = Recognition(
            id,
            label,
            distance,
            RectF()
        )

            rec.embeeding = embeedings

        return rec
    }

    companion object {
        //private static final int OUTPUT_SIZE = 512;
        private const val OUTPUT_SIZE = 512

        // Only return this many results.
        private const val NUM_DETECTIONS = 1

        // Float model
        private const val IMAGE_MEAN = 128.0f
        private const val IMAGE_STD = 128.0f

        //TODO loads the models into mapped byte buffer format
        @Throws(IOException::class)
        private fun loadModelFile(assets: AssetManager, modelFilename: String): MappedByteBuffer {
            val fileDescriptor = assets.openFd(modelFilename)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }

        @Throws(IOException::class)
        fun create(
            assetManager: AssetManager,
            modelFilename: String,
            inputSize: Int,
            isQuantized: Boolean, ctx: Context
        ): FaceClassifier {
            val d = TFLiteFaceRecognition(ctx)
            d.inputSize = inputSize
            try {
                d.tfLite = Interpreter(loadModelFile(assetManager, modelFilename))
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            d.isModelQuantized = isQuantized
            // Pre-allocate buffers.
            val numBytesPerChannel: Int
            numBytesPerChannel = if (isQuantized) {
                1 // Quantized
            } else {
                4 // Floating point
            }
            d.imgData =
                ByteBuffer.allocateDirect(1 * d.inputSize * d.inputSize * 3 * numBytesPerChannel)
           // d.imgData.order(ByteOrder.nativeOrder())
            d.intValues = IntArray(d.inputSize * d.inputSize)
            return d
        }
    }
}
