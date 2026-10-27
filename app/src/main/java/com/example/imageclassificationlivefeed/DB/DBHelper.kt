package com.example.facerecognitionimages.DB

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.util.Log
import com.example.facerecognitionimages.face_recognition.FaceClassifier.Recognition

class DBHelper(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "create table faces (id integer primary key, name text, embedding text, face_img text)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS faces")
        onCreate(db)
    }

    fun insertFace(name: String?, embedding: Any, faceImage: Bitmap): Boolean {
        val floatList = embedding as Array<FloatArray>
        var embeddingString = ""
        for (f in floatList[0]) {
            embeddingString += "$f,"
        }
        // Convert bitmap to Base64
        val stream = java.io.ByteArrayOutputStream()
        faceImage.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val byteArray = stream.toByteArray()
        val imageString = android.util.Base64.encodeToString(byteArray, android.util.Base64.DEFAULT)

        val db = this.writableDatabase
        val contentValues = ContentValues()
        contentValues.put(FACE_COLUMN_NAME, name)
        contentValues.put(FACE_COLUMN_EMBEDDING, embeddingString)
        contentValues.put("face_img", imageString)
        db.insert("faces", null, contentValues)
        return true
    }

    fun getData(id: Int): Cursor {
        val db = this.readableDatabase
        return db.rawQuery("select * from faces where id=$id", null)
    }

    fun numberOfRows(): Int {
        val db = this.readableDatabase
        return DatabaseUtils.queryNumEntries(db, FACE_TABLE_NAME).toInt()
    }

    fun updateFace(id: Int?, name: String?, embedding: String?): Boolean {
        val db = this.writableDatabase
        val contentValues = ContentValues()
        contentValues.put(FACE_COLUMN_NAME, name)
        contentValues.put(FACE_COLUMN_EMBEDDING, embedding)
        db.update(
            FACE_TABLE_NAME, contentValues, "id = ? ", arrayOf(
                Integer.toString(
                    id!!
                )
            )
        )
        return true
    }

    fun deleteFace(id: Int?): Int {
        val db = this.writableDatabase
        return db.delete(
            FACE_TABLE_NAME,
            "id = ? ", arrayOf(Integer.toString(id!!))
        )
    }

    @get:SuppressLint("Range")
    val allFaces: HashMap<Int, Recognition>
        get() {
            val db = this.readableDatabase
            val res = db.rawQuery("select * from faces", null)
            res.moveToFirst()
            val registered = HashMap<Int, Recognition>()
            while (!res.isAfterLast) {
                val id = res.getInt(res.getColumnIndex(FACE_COLUMN_ID))
                val embeddingString = res.getString(res.getColumnIndex(FACE_COLUMN_EMBEDDING))
                val stringList = embeddingString.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                val embeddingFloat = ArrayList<Float>()
                for (s in stringList) {
                    embeddingFloat.add(s.toFloat())
                }
                val floatArray = FloatArray(embeddingFloat.size)
                for (i in embeddingFloat.indices) {
                    floatArray[i] = embeddingFloat[i]
                }
                val bigArray = Array(1) { floatArray }
                // Decode face image from Base64 string
                val faceImgString = res.getString(res.getColumnIndex("face_img"))
                var faceBitmap: Bitmap? = null
                if (!faceImgString.isNullOrEmpty()) {
                    val bytes = android.util.Base64.decode(faceImgString, android.util.Base64.DEFAULT)
                    faceBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                val recognition =
                    Recognition(res.getString(res.getColumnIndex(FACE_COLUMN_NAME)), bigArray)
                recognition.crop = faceBitmap
                registered[id] = recognition
                res.moveToNext()
            }
            Log.d("tryRL", "rl=" + registered.size)
            return registered
        }

    companion object {
        const val DATABASE_NAME = "MyFaces.db"
        const val FACE_TABLE_NAME = "faces"
        const val FACE_COLUMN_ID = "id"
        const val FACE_COLUMN_NAME = "name"
        const val FACE_COLUMN_EMBEDDING = "embedding"
    }
}