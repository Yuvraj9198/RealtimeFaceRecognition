package com.example.imageclassificationlivefeed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.facerecognitionimages.DB.DBHelper
import com.example.facerecognitionimages.face_recognition.FaceClassifier
import com.example.facerecognitionimages.face_recognition.TFLiteFaceRecognition

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.IOException

// a simple data class for facelist
data class FaceItem(val id: Int, val name: String, val image: android.graphics.Bitmap?)

class savedPerson : AppCompatActivity() {
    var galleryCard: Button? = null
    private lateinit var dbHelper: DBHelper
    private lateinit var faceAdapter: FaceAdapter
    private val faceList = mutableListOf<FaceItem>()
    var image_uri: Uri? = null
    private var editingFaceId: Int = -1

    //TODO get the image from gallery and display it
    var galleryActivityResultLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult(),
        object : ActivityResultCallback<ActivityResult?> {
            override fun onActivityResult(result: ActivityResult?) {
                if (result != null) {
                    if (result.resultCode == RESULT_OK) {
                        image_uri = result.data!!.data
                        val inputImage = uriToBitmap(image_uri!!)
                        val rotated = rotateBitmap(inputImage)

                        performFaceDetection(rotated)
                    }
                }
            }
        })

    //TODO declare face detector
    var detector: FaceDetector? = null

    //TODO declare face recognizer
    private var faceClassifier: FaceClassifier? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_saved_person)

        // Handling permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED
            ) {
                val permissions = arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                requestPermissions(permissions, PERMISSION_CODE)
            }
        }

        // Initialize views
        galleryCard = findViewById(R.id.gallerycard)
        val recyclerView: RecyclerView = findViewById(R.id.facesRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Initialize the faceAdapter
        faceAdapter = FaceAdapter(faceList, { id -> deleteFace(id) }) { id, name ->
            showEditDialog(id, name)  // Show edit dialog
        }

        recyclerView.adapter = faceAdapter

        // Initialize DBHelper
        dbHelper = DBHelper(this)

        // Load data AFTER initializing adapter
        loadFacesFromDatabase()

        // Set up gallery button
        galleryCard!!.setOnClickListener {
            val galleryIntent =
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryActivityResultLauncher.launch(galleryIntent)
        }

        // Configure FaceDetector
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(highAccuracyOpts)

        // Initialize face recognition model
        try {
            faceClassifier = TFLiteFaceRecognition.create(
                assets,
                "facenet.tflite",
                TF_OD_API_INPUT_SIZE,
                false,
                applicationContext
            )
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(
                applicationContext,
                "Classifier could not be initialized",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }


    private fun loadFacesFromDatabase() {
        val dbFaces = dbHelper.allFaces
        faceList.clear()
        for ((id, recognition) in dbFaces) {
            faceList.add(FaceItem(id, recognition.title ?: "Unknown", recognition.crop))
            Log.d("SavedPerson", "ID: $id, Name: ${recognition.title}")
        }
        faceAdapter.notifyDataSetChanged()
    }

    private fun deleteFace(id: Int) {
        val rowsDeleted = dbHelper.deleteFace(id)
        if (rowsDeleted > 0) {
            val index = faceList.indexOfFirst { it.id == id }
            if (index != -1) {
                faceAdapter.removeItem(index)
            }
            Toast.makeText(this, "Face deleted successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to delete face.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditDialog(id: Int, currentName: String) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.edit_face_dialog)

        val nameEd = dialog.findViewById<EditText>(R.id.edit_face_name)
        val saveButton = dialog.findViewById<Button>(R.id.save_button)
        val cancelButton = dialog.findViewById<Button>(R.id.cancel_button)

        nameEd.setText(currentName)  // Pre-fill existing name

        saveButton.setOnClickListener {
            val newName = nameEd.text.toString()
            if (newName.isEmpty()) {
                nameEd.error = "Enter a name"
                return@setOnClickListener
            }

            updateFaceName(id, newName)
            dialog.dismiss()
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }
    private fun updateFaceName(id: Int, newName: String) {
        val success = dbHelper.updateFace(id, newName, null) // Update only the name
        if (success) {
            Toast.makeText(this, "Face name updated!", Toast.LENGTH_SHORT).show()
            loadFacesFromDatabase() // Refresh UI
        } else {
            Toast.makeText(this, "Failed to update face.", Toast.LENGTH_SHORT).show()
        }
    }





    private fun uriToBitmap(selectedFileUri: Uri): Bitmap? {
    try {
        val parcelFileDescriptor = contentResolver.openFileDescriptor(selectedFileUri, "r")
        val fileDescriptor = parcelFileDescriptor!!.fileDescriptor
        val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
        parcelFileDescriptor.close()
        return image
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return null
}

//TODO rotate image if image captured on samsung devices
//TODO Most phone cameras are landscape, meaning if you take the photo in portrait, the resulting photos will be rotated 90 degrees.
@SuppressLint("Range")
fun rotateBitmap(input: Bitmap?): Bitmap {
    val orientationColumn =
        arrayOf(MediaStore.Images.Media.ORIENTATION)
    val cur =
        contentResolver.query(image_uri!!, orientationColumn, null, null, null)
    var orientation = -1
    if (cur != null && cur.moveToFirst()) {
        orientation = cur.getInt(cur.getColumnIndex(orientationColumn[0]))
    }
    Log.d("tryOrientation", orientation.toString() + "")
    val rotationMatrix = Matrix()
    rotationMatrix.setRotate(orientation.toFloat())
    return Bitmap.createBitmap(
        input!!,
        0,
        0,
        input.width,
        input.height,
        rotationMatrix,
        true
    )
}

//TODO perform face detection
fun performFaceDetection(inputImage: Bitmap) {
    val mutableBmp = inputImage.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBmp)
    val image = InputImage.fromBitmap(inputImage, 0)
    val result = detector!!.process(image)
        .addOnSuccessListener { faces ->
            for (face in faces) {
                val bounds = face.boundingBox
                val p = Paint()
                p.style = Paint.Style.STROKE
                p.strokeWidth = 3f
                p.color = Color.RED
                canvas.drawRect(bounds, p)
                performFaceRecognition(bounds, inputImage)
            }

        }
        .addOnFailureListener {
            // Task failed with an exception
            // ...
        }
}

//TODO perform face recognition
fun performFaceRecognition(bounds: Rect, inputImage: Bitmap) {
    if (bounds.top < 0) {
        bounds.top = 0
    }
    if (bounds.left < 0) {
        bounds.left = 0
    }
    if (bounds.right > inputImage.width) {
        bounds.right = inputImage.width - 1
    }
    if (bounds.bottom > inputImage.height) {
        bounds.bottom = inputImage.height - 1
    }
    //TODO crop the face
    var croppedFace = Bitmap.createBitmap(
        inputImage,
        bounds.left,
        bounds.top,
        bounds.width(),
        bounds.height()
    )
    croppedFace = Bitmap.createScaledBitmap(
        croppedFace,
        TF_OD_API_INPUT_SIZE,
        TF_OD_API_INPUT_SIZE,
        false
    )

    //TODO fet the embedding for face
    val startTime = SystemClock.uptimeMillis()
    val result = faceClassifier!!.recognizeImage(croppedFace, true)
    val lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime
    result?.let { registerFaceDialogue(croppedFace, it) }
}


    private fun registerFaceDialogue(croppedFace: Bitmap, rec: FaceClassifier.Recognition) {
        // to store that cropped face so it can be used
        rec.crop = croppedFace
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.register)
        val ivFace = dialog.findViewById<ImageView>(R.id.dlg_image)
        val nameEd = dialog.findViewById<EditText>(R.id.dlg_input)
        val register = dialog.findViewById<Button>(R.id.button2)
        val discard = dialog.findViewById<Button>(R.id.discard)
        ivFace.setImageBitmap(croppedFace)
        val layoutParams = WindowManager.LayoutParams()
        layoutParams.copyFrom(dialog.window?.attributes)
        layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.window?.attributes = layoutParams
        register.setOnClickListener(View.OnClickListener {
            val name = nameEd.text.toString()
            if (name.isEmpty()) {
                nameEd.error = "Enter Name"
                return@OnClickListener
            }
            faceClassifier!!.register(name, rec)
            Toast.makeText(
                this@savedPerson,
                "Face Registered Successfully",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        })
        discard?.setOnClickListener {
            dialog.dismiss() // Dismiss the dialog on Discard button click
        }
        dialog.show()
    }


    override fun onDestroy() {
        super.onDestroy()
        detector!!.close()
    }

    companion object {
        private const val TF_OD_API_INPUT_SIZE = 160
        const val PERMISSION_CODE = 100
    }

}