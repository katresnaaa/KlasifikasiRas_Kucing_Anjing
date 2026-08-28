package com.example.ras_kucing_anjing

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PredictionFragment : Fragment(R.layout.fragment_prediction) {

    private lateinit var imageView: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var selectImageButton: Button
    private lateinit var captureImageButton: Button

    private var interpreter: Interpreter? = null
    private var inputImageWidth: Int = 0
    private var inputImageHeight: Int = 0
    private var inputImageChannels: Int = 3
    private var labels: List<String> = emptyList()

    private var tempImageUri: Uri? = null

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val bitmap = uriToBitmap(it)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    classifyImage(bitmap)
                } else {
                    showToast("Gagal membaca gambar")
                }
            }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                tempImageUri?.let { uri ->
                    val bitmap = uriToBitmap(uri)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        classifyImage(bitmap)
                    } else {
                        showToast("Gagal memproses foto kamera")
                    }
                }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                launchCamera()
            } else {
                showToast("Izin kamera diperlukan untuk mengambil foto")
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        imageView = view.findViewById(R.id.imageView)
        resultTextView = view.findViewById(R.id.resultTextView)
        selectImageButton = view.findViewById(R.id.selectImageButton)
        captureImageButton = view.findViewById(R.id.captureImageButton)

        initInterpreter()
        labels = loadBreedLabels()

        selectImageButton.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        captureImageButton.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }
    }

    private fun checkCameraPermissionAndLaunch() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        try {
            val tempFile = java.io.File.createTempFile("temp_image", ".jpg", requireContext().cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            tempImageUri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "com.example.ras_kucing_anjing.fileprovider",
                tempFile
            )
            cameraLauncher.launch(tempImageUri)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Gagal menyiapkan kamera: ${e.message}")
        }
    }

    private fun initInterpreter() {
        try {
            val context = requireContext()
            val modelBuffer = loadModelFile("animalbreed.tflite")
            interpreter = Interpreter(modelBuffer)

            val inputShape = interpreter?.getInputTensor(0)?.shape() ?: return
            inputImageHeight = inputShape.getOrNull(1) ?: 224
            inputImageWidth = inputShape.getOrNull(2) ?: 224
            inputImageChannels = if (inputShape.size > 3) inputShape[3] else 3
        } catch (e: Exception) {
            e.printStackTrace()
            resultTextView.text = "Gagal memuat model: ${e.message}"
        }
    }

    private fun loadModelFile(fileName: String): ByteBuffer {
        requireContext().assets.open(fileName).use { inputStream ->
            val bytes = inputStream.readBytes()
            return ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap? {
        return try {
            val contentResolver = requireContext().contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun classifyImage(bitmap: Bitmap) {
        try {
            val model = interpreter ?: run {
                showToast("Model belum siap")
                return
            }

            if (inputImageWidth == 0 || inputImageHeight == 0) {
                showToast("Dimensi input model tidak valid")
                return
            }

            if (inputImageChannels != 3) {
                showToast("Model meminta $inputImageChannels channel, tapi support 3.")
                return
            }

            val resized = Bitmap.createScaledBitmap(bitmap, inputImageWidth, inputImageHeight, true)
            
            val inputTensor = model.getInputTensor(0)
            val dataType = inputTensor.dataType()

            val byteBuffer = convertBitmapToByteBuffer(resized, dataType)

            val outputTensor = model.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            
            val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputTensor.dataType())

            model.run(byteBuffer, outputBuffer.buffer.rewind())

            val scores = outputBuffer.floatArray
            if (scores.isEmpty()) {
                resultTextView.text = "Model tidak menghasilkan output"
                return
            }

            val (maxIndex, maxScore) = scores.withIndex().maxByOrNull { it.value } ?: return
            val label = labels.getOrNull(maxIndex) ?: "Kelas $maxIndex"
            
            // Filter nilai kepercayaan rendah
            // User meminta agar TIDAK dimirip-miripkan jika tidak sesuai.
            // Diturunkan ke 0.60 (60%) agar foto dari layar laptop (kualitas rendah) tetap terdeteksi.
            if (maxScore < 0.60f) {
                 resultTextView.text = "Objek pada gambar tidak dikenal"
                 return
            }

            // Perbaikan tampilan hasil
            val isDog = label.contains("🐶")
            val speciesType = if (isDog) "Anjing ras" else "Kucing ras"
            val resultText = "Terdeteksi : $speciesType $label\nPrediksi: ${String.format("%.2f%%", maxScore * 100f)}"
            resultTextView.text = resultText
        } catch (e: Throwable) {
            e.printStackTrace()
            showToast("Terjadi error klasifikasi: ${e.message}")
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap, dataType: org.tensorflow.lite.DataType): ByteBuffer {
        val bytesPerChannel = if (dataType == org.tensorflow.lite.DataType.FLOAT32) 4 else 1
        val inputSize = inputImageWidth * inputImageHeight * inputImageChannels * bytesPerChannel
        
        val byteBuffer = ByteBuffer.allocateDirect(inputSize)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputImageWidth * inputImageHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixelIndex = 0
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = intValues[pixelIndex++]
                
                val r = (pixel shr 16 and 0xFF)
                val g = (pixel shr 8 and 0xFF)
                val b = (pixel and 0xFF)

                if (dataType == org.tensorflow.lite.DataType.FLOAT32) {
                    // Normalisasi -1..1 (Standard MobileNet)
                    byteBuffer.putFloat((r - 127.5f) / 127.5f)
                    byteBuffer.putFloat((g - 127.5f) / 127.5f)
                    byteBuffer.putFloat((b - 127.5f) / 127.5f)
                } else {
                    byteBuffer.put(r.toByte())
                    byteBuffer.put(g.toByte())
                    byteBuffer.put(b.toByte())
                }
            }
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun loadBreedLabels(): List<String> {
        val breeds = mutableListOf<Pair<String, String>>() // RawName, DisplayName
        val seenBreeds = mutableSetOf<String>()

        try {
            requireContext().assets.open("list.txt").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("#") || line.isBlank()) return@forEach
                        
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size < 3) return@forEach 

                        val imageName = parts[0]
                        val speciesId = parts[2].toIntOrNull() ?: 0 // 1=Cat, 2=Dog

                        val rawName = imageName.substringBeforeLast("_")
                        
                        if (!seenBreeds.contains(rawName)) {
                            seenBreeds.add(rawName)
                            
                            val displayName = rawName.replace("_", " ").capitalizeWords()
                            val speciesEmoji = if (speciesId == 2) "🐶" else "🐱"
                            
                            breeds.add(Pair(rawName, "$displayName $speciesEmoji"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Error loading labels: ${e.message}")
        }

        // KEMBALI KE SORTING ASCII
        // TFLite Model Maker biasanya mengurutkan label secara alphabetis (ASCII)
        // Ini memastikan 'Bengal' (B) muncul sebelum 'beagle' (b), berbeda dengan urutan ID di list.txt
        breeds.sortBy { it.first }

        return breeds.map { it.second }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        interpreter?.close()
    }

    private fun String.capitalizeWords(): String {
        return split(" ").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }
}
