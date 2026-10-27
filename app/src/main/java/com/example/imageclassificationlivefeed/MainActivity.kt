package com.example.imageclassificationlivefeed

import android.Manifest
import android.app.Dialog
import android.app.Fragment
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image.Plane
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.facerecognitionimages.face_recognition.FaceClassifier
import com.example.facerecognitionimages.face_recognition.TFLiteFaceRecognition
import com.example.imageclassificationlivefeed.Drawing.MultiBoxTracker

import com.example.imageclassificationlivefeed.ImageUtils.convertYUV420ToARGB8888
import com.example.imageclassificationlivefeed.ImageUtils.getTransformationMatrix
import com.example.objectdetectionlivefeed.Drawing.BorderedText
import com.example.objectdetectionlivefeed.Drawing.OverlayView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), OnImageAvailableListener, TextToSpeech.OnInitListener {
    var handler: Handler? = null
    private var frameToCropTransform: Matrix? = null
    private var sensorOrientation = 0
    private var cropToFrameTransform: Matrix? = null
    var trackingOverlay: OverlayView? = null
    private var borderedText: BorderedText? = null
    private var tracker: MultiBoxTracker? = null
    private var useFacing: Int? = null
    private var currentToast: Toast? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechRecognizerIntent: Intent? = null
    private var isListening = false
    private var lastDetectedPerson: String? = null
    private var lastDetectionTime: Long = 0
    private val detectionCooldown = 5000 // 5-second cooldown
    var lastUnknownDetectionTime: Long = 0
    val unknownCooldown: Long = 20000



    //TODO delcare face detector
    var detector: FaceDetector? = null

    //TODO declare face recognizer
    private var faceClassifier: FaceClassifier? = null
    var registerFace = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        handler = Handler()

        //TODO handling permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED || checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                == PackageManager.PERMISSION_DENIED||checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED
            ) {
                val permission =
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
                requestPermissions(permission, 121)
            }
        }
        val intent = intent
        useFacing = intent.getIntExtra(KEY_USE_FACING, CameraCharacteristics.LENS_FACING_BACK)

        //TODO show live camera footage
        setFragment()


        //TODO initialize the tracker to draw rectangles
        tracker = MultiBoxTracker(this)


        //TODO initalize face detector
        // Multiple object detection in static images
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(highAccuracyOpts)


        //TODO initialize FACE Recognition
        try {
            faceClassifier = TFLiteFaceRecognition.create(
                assets,
                "facenet.tflite",
                TF_OD_API_INPUT_SIZE2,
                false, applicationContext
            )
        } catch (e: IOException) {
            e.printStackTrace()
            val toast = Toast.makeText(
                applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT
            )
            toast.show()
            finish()
        }
        findViewById<View>(R.id.imageView4).setOnClickListener { registerFace = true }
        findViewById<View>(R.id.imageView3).setOnClickListener { switchCamera() }
        findViewById<View>(R.id.imageView5).setOnClickListener {
            val intent = Intent(this, savedPerson::class.java)
            startActivity(intent)
        }
        // Initialize TTS
        textToSpeech = TextToSpeech(this, this)

        // Initialize STT
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent!!.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        speechRecognizerIntent!!.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 121 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment()
        }
    }

    //TODO fragment which show live footage from camera
    var previewHeight = 0
    var previewWidth = 0
    protected fun setFragment() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            cameraId = manager.cameraIdList[(useFacing)!!]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        val fragment: Fragment
        val camera2Fragment = CameraConnectionFragment.newInstance(
            object : CameraConnectionFragment.ConnectionCallback {
                override fun onPreviewSizeChosen(size: Size?, rotation: Int) {
                    previewHeight = size!!.height
                    previewWidth = size.width
                    val textSizePx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics
                    )
                    borderedText = BorderedText(textSizePx)
                    borderedText!!.setTypeface(Typeface.MONOSPACE)
                    val cropSize = CROP_SIZE
                    previewWidth = size.width
                    previewHeight = size.height
                    sensorOrientation = rotation - screenOrientation
                    rgbFrameBitmap =
                        Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
                    frameToCropTransform = getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT
                    )
                    cropToFrameTransform = Matrix()
                    frameToCropTransform!!.invert(cropToFrameTransform)
                    trackingOverlay = findViewById<View>(R.id.tracking_overlay) as OverlayView
                    trackingOverlay!!.addCallback(
                        object : OverlayView.DrawCallback {
                            override fun drawCallback(canvas: Canvas?) {
                                if (canvas != null) {
                                    tracker?.draw(canvas)
                                }
                                Log.d("tryDrawRect", "inside draw")
                            }
                        })
                    tracker!!.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation)
                }
            },
            this,
            R.layout.camera_fragment,
            Size(640, 480)
        )
        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    //TODO getting frames of live camera footage and passing them to model
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var rgbFrameBitmap: Bitmap? = null
    override fun onImageAvailable(reader: ImageReader) {
        // We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = Runnable {
                convertYUV420ToARGB8888(
                    (yuvBytes[0])!!,
                    (yuvBytes[1])!!,
                    (yuvBytes[2])!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            performFaceDetection()
        } catch (e: Exception) {
            Log.d("tryError", e.message + "abc ")
            return
        }
    }

    protected fun fillBytes(planes: Array<Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    protected val screenOrientation: Int
        protected get() = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    var croppedBitmap: Bitmap? = null
    var mappedRecognitions: ArrayList<FaceClassifier.Recognition>? = null




    // Speak out message using TTS
    private fun speakOut(message: String) {
        textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }
    //TODO Perform face detection
    fun performFaceDetection() {


        imageConverter!!.run()
        rgbFrameBitmap!!.setPixels(rgbBytes!!, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)
        Handler().post {
            mappedRecognitions = ArrayList()
            val image = InputImage.fromBitmap((croppedBitmap)!!, 0)
            detector!!.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        // If no faces are detected, cancel the current toast
                        currentToast?.cancel()
                    }

                    for (face: Face in faces) {
                        val bounds = face.boundingBox
                        performFaceRecognition(face)
                    }
                    registerFace = false
                    tracker!!.trackResults(mappedRecognitions!!, 10)
                    trackingOverlay!!.postInvalidate()
                    postInferenceCallback!!.run()
                }
                .addOnFailureListener {
                    // Task failed with an exception
                    currentToast?.cancel() // Cancel toast if detection fails
                }
        }
    }
    // Listen for the person's name using STT
    private fun startListeningForName(croppedFace: Bitmap, result: FaceClassifier.Recognition?) {
        var cropp =croppedFace
        if (!isListening) {
            isListening = true
            speechRecognizer?.startListening(speechRecognizerIntent)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false

                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (matches != null && matches.size > 0) {
                        val spokenName = matches[0].trim()
                        Log.d("Speak",spokenName)

                        registerFaceDialogue(cropp, result, spokenName)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }


    //TODO perform face recognition
    fun performFaceRecognition(face: Face) {

        // TODO crop the face
        val bounds: Rect = face.boundingBox
        if (bounds.top < 0) bounds.top = 0
        if (bounds.left < 0) bounds.left = 0
        if (bounds.left + bounds.width() > croppedBitmap!!.width) bounds.right = croppedBitmap!!.width - 1
        if (bounds.top + bounds.height() > croppedBitmap!!.height) bounds.bottom = croppedBitmap!!.height - 1

        var crop: Bitmap = Bitmap.createBitmap(
            croppedBitmap!!,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()
        )
        crop = Bitmap.createScaledBitmap(crop, TF_OD_API_INPUT_SIZE2, TF_OD_API_INPUT_SIZE2, false)

        val result: FaceClassifier.Recognition? = faceClassifier!!.recognizeImage(crop, registerFace)
        var title = "Unknown"
        var confidence = 0f
        if (result != null) {
            val currentTime = System.currentTimeMillis()
            if (registerFace) {
                Log.d("Embeddinginregister",result.embeeding.toString())

                registerFaceDialogue(crop, result)
            } else {
                if (result.distance!! < 1f) {
                    confidence = result.distance!!
                    title = result.title.toString()

                    if (lastDetectedPerson == title && currentTime - lastDetectionTime < detectionCooldown) {
                        // Skip speaking since it's within the cooldown period
                        return
                    }

                    // Update last detected person and detection time
                    lastDetectedPerson = title
                    lastDetectionTime = currentTime
                    Log.d("Embeddingindetected",result.embeeding.toString())

                    speakOut("Person detected: $title")
                    // If a person is detected, show a toast
                    showIdentifiedPersonToast(title, confidence)
                } else {
                    // Handle unknown person detection cooldown
                    if (currentTime - lastUnknownDetectionTime < unknownCooldown) {
                        // Skip if within the cooldown for unknown persons
                        return
                    }

                    // Update the last detection time for unknown persons
                    lastUnknownDetectionTime = currentTime
                    speakOut("Unknown person detected.Speak the name to register?")

                    Log.d("EmbeddinginUnknown",result.embeeding.toString())



                    Handler(Looper.getMainLooper()).postDelayed({
                        startListeningForName(crop, result) // Start STT to capture spoken name
                    }, 5000) // 2-second delay
                }
            }


        }

        val location = RectF(bounds)
        if (bounds != null) {
            if (useFacing == CameraCharacteristics.LENS_FACING_BACK) {
                location.right = croppedBitmap!!.width - location.right
                location.left = croppedBitmap!!.width - location.left
            }
            cropToFrameTransform!!.mapRect(location)
            val recognition = FaceClassifier.Recognition(
                face.trackingId.toString() + "",
                title,
                confidence,
                location
            )
            mappedRecognitions!!.add(recognition)
        }
    }

    private fun registerFaceDialogue(croppedFace: Bitmap, rec: FaceClassifier.Recognition?, spokenName: String? = null) {
        rec?.crop = croppedFace
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.register_face_dialogue)
        val ivFace = dialog.findViewById<ImageView>(R.id.dlg_image)
        val nameEd = dialog.findViewById<EditText>(R.id.dlg_input)
        val register = dialog.findViewById<Button>(R.id.button2)
        // Set cropped face in the dialog
        ivFace.setImageBitmap(croppedFace) // Pre-fill spoken name into the EditText if provided
        if (!spokenName.isNullOrEmpty()) {
            nameEd.setText(spokenName)
            speakOut("The Name Spoken is $spokenName. If the name is correct say okay to register. ")
            //speakOut("If the name is correct say yes to register. ")
            Handler(Looper.getMainLooper()).postDelayed({
                listenForConfirmation(croppedFace, rec, spokenName,dialog)
            }, 5000)

        }

        register.setOnClickListener(View.OnClickListener {
            val name = nameEd.text.toString()
            if (name.isEmpty()) {
                nameEd.error = "Enter Name"
                return@OnClickListener
            }
            faceClassifier!!.register(name, rec)
            Toast.makeText(this@MainActivity, "Face Registered Successfully", Toast.LENGTH_SHORT)
                .show()
            dialog.dismiss()
        })
        dialog.setOnDismissListener {
        }
        dialog.show()
    }

    private fun listenForConfirmation(croppedFace: Bitmap, result: FaceClassifier.Recognition?, name: String, dialog: Dialog) {
        if (!isListening) {
            isListening = true
            speechRecognizer?.startListening(speechRecognizerIntent)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false

                }

                override fun onError(error: Int) {
                    isListening = false

                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (matches != null && matches.size > 0) {
                        val confirmation = matches[0]
                        Log.d("Speak",confirmation)
                        if (confirmation.equals("okay", ignoreCase = true)) {
                            // Register the face if the user confirms with "yes"
                            Log.d("Speak",confirmation)

                            Log.d("FaceConfirmation", "Registering face with name: $name and result: ${result?.embeeding?.toString()}")

                            if (name != null && result?.embeeding!= null) {
                                faceClassifier?.register(name, result)
                                Toast.makeText(this@MainActivity, "Face Registered Successfully", Toast.LENGTH_SHORT).show()

                            }
                            else {
                                Log.e("FaceConfirmation", "Embedding is null, registration failed")
                                Toast.makeText(this@MainActivity, "Failed to register: embedding is null", Toast.LENGTH_SHORT).show()
                            }
                            dialog.dismiss()
                        } else {
                            speakOut("Face Not Registered")
                            dialog.dismiss()
                            // You could also restart listening for the name here
                        }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }
    // Method to show the toast and manage its cancellation
    private fun showIdentifiedPersonToast(name: String, confidence: Float) {
        // Cancel any previous toast if it's showing
        currentToast?.cancel()

        // Show new toast
        currentToast = Toast.makeText(
            this@MainActivity,
            "Identified: $name with confidence: $confidence",
            Toast.LENGTH_SHORT
        )
        currentToast?.show()
    }



    override fun onDestroy() {
        super.onDestroy()
        detector!!.close()
    }

    //TODO register face dialogue

    //TODO switch camera
    fun switchCamera() {
        val intent = intent
        useFacing = if (useFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        intent.putExtra(KEY_USE_FACING, useFacing)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        restartWith(intent)
    }

    private fun restartWith(intent: Intent) {
        finish()
        overridePendingTransition(0, 0)
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val MAINTAIN_ASPECT = false
        private const val TEXT_SIZE_DIP = 10f
        private const val KEY_USE_FACING = "use_facing"
        private const val CROP_SIZE = 1000
        private const val TF_OD_API_INPUT_SIZE2 = 160
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.getDefault()
        }
    }
}