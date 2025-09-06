package com.example.pdfchitalka

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.pdfchitalka.databinding.ActivityMainBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private var currentPdfUri: Uri? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isSpeaking = false

    // Регистрация для выбора файла
    private val pickPdfFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadPdfFromUri(it) }
    }

    // Регистрация для сохранения файла
    private val savePdfFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { savePdfToUri(it) }
    }

    // Регистрация для запроса разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Разрешения предоставлены", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Для полной функциональности нужны все разрешения", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Настройка Toolbar
        setSupportActionBar(binding.toolbar)

        // Проверка и запрос разрешений
        checkAndRequestPermissions()

        // Инициализация TextToSpeech
        textToSpeech = TextToSpeech(this, this)

        // Инициализация PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        setupUI()
        handleIntent(intent)
    }

    override fun onDestroy() {
        // Остановка и освобождение ресурсов TextToSpeech
        textToSpeech?.run {
            stop()
            shutdown()
        }
        super.onDestroy()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Базовые разрешения для Android 10 и ниже
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Для Android 13+ (READ_MEDIA_IMAGES включает доступ к изображениям и PDF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        // Для Android 14+ - Selected Photos Access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            handlePartialMediaAccess()
        }
    }

    private fun handlePartialMediaAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Проверяем, есть ли у нас полный доступ или только частичный
            val hasFullAccess = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasPartialAccess = checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasPartialAccess && !hasFullAccess) {
                Toast.makeText(this,
                    "Предоставлен частичный доступ к медиа. Для полной функциональности предоставьте полный доступ в настройках.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Android 12 и ниже
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Установка русского языка
            val result = textToSpeech?.setLanguage(Locale("ru", "RU"))

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Русский язык не поддерживается", Toast.LENGTH_SHORT).show()
            } else {
                isTtsInitialized = true
                setupTtsListeners()
                runOnUiThread {
                    updateSpeechButtonState()
                }
            }
        } else {
            Toast.makeText(this, "Ошибка инициализации синтезатора речи", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                updateSpeechButtonState()
            }
        }
    }

    private fun setupTtsListeners() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = true
                    updateSpeechButtonState()
                    Toast.makeText(this@MainActivity, "Начало воспроизведения", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    updateSpeechButtonState()
                    Toast.makeText(this@MainActivity, "Воспроизведение завершено", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = false
                    updateSpeechButtonState()
                    Toast.makeText(this@MainActivity, "Ошибка воспроизведения", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun setupUI() {
        // Кнопка загрузки PDF
        binding.btnLoadPdf.setOnClickListener {
            if (hasRequiredPermissions()) {
                pickPdfFile.launch("application/pdf")
            } else {
                Toast.makeText(this, "Для загрузки файлов нужны разрешения", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
            }
        }

        // Кнопка копирования текста
        binding.btnCopyText.setOnClickListener {
            copyTextToClipboard()
        }

        // Кнопка сохранения PDF
        binding.btnSavePdf.setOnClickListener {
            if (currentPdfUri != null) {
                savePdfFile.launch("document_${System.currentTimeMillis()}.pdf")
            } else {
                Toast.makeText(this, "Сначала загрузите PDF файл", Toast.LENGTH_SHORT).show()
            }
        }

        // Кнопка отправки/шаринга
        binding.btnSharePdf.setOnClickListener {
            sharePdf()
        }

        // Кнопка озвучивания текста
        binding.btnSpeakText.setOnClickListener {
            toggleSpeech()
        }

        // Кнопка остановки озвучивания
        binding.btnStopSpeech.setOnClickListener {
            stopSpeech()
        }

        // Кнопка "О компании"
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // Изначально кнопки озвучивания неактивны
        updateSpeechButtonState()
    }

    private fun updateSpeechButtonState() {
        val defaultText = getString(R.string.default_pdf_text)
        val loadingText = getString(R.string.loading_pdf_text)
        val currentText = binding.pdfTextView.text.toString()

        val hasContentToSpeak = currentText.isNotBlank() &&
                currentText != defaultText &&
                currentText != loadingText

        // Состояние кнопок
        binding.btnSpeakText.isEnabled = hasContentToSpeak && isTtsInitialized && !isSpeaking
        binding.btnStopSpeech.isEnabled = isSpeaking && isTtsInitialized

        // Визуальные изменения для кнопки озвучивания
        if (isSpeaking) {
            binding.btnSpeakText.text = getString(R.string.menu_pause)
            binding.btnSpeakText.alpha = 0.8f
        } else {
            binding.btnSpeakText.text = getString(R.string.menu_speak)
            binding.btnSpeakText.alpha = if (hasContentToSpeak && isTtsInitialized) 1.0f else 0.5f
        }

        // Визуальные изменения для кнопки остановки
        binding.btnStopSpeech.alpha = if (isSpeaking) 1.0f else 0.5f
    }

    private fun toggleSpeech() {
        if (isSpeaking) {
            pauseSpeech()
        } else {
            startSpeech()
        }
    }

    private fun startSpeech() {
        val text = binding.pdfTextView.text.toString()
        if (text.isNotEmpty() && isTtsInitialized) {
            // Очистка очереди и запуск воспроизведения
            textToSpeech?.stop()

            // Настройка параметров речи
            textToSpeech?.setSpeechRate(0.9f) // Немного медленнее для лучшего восприятия
            textToSpeech?.setPitch(1.0f)

            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pdf_utterance")
            updateSpeechButtonState()
        }
    }

    private fun pauseSpeech() {
        textToSpeech?.stop()
        isSpeaking = false
        updateSpeechButtonState()
        Toast.makeText(this, "Воспроизведение приостановлено", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeech() {
        textToSpeech?.stop()
        isSpeaking = false
        updateSpeechButtonState()
        Toast.makeText(this, "Воспроизведение прервано", Toast.LENGTH_SHORT).show()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    loadPdfFromUri(uri)
                }
            }
        }
    }

    private fun loadPdfFromUri(uri: Uri) {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, "Нет разрешений для чтения файла", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }

        try {
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.pdfTextView.text = getString(R.string.loading_pdf_text)

            Thread {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val document = PDDocument.load(inputStream)
                        val textStripper = PDFTextStripper()
                        val pdfText = textStripper.getText(document)

                        runOnUiThread {
                            binding.pdfTextView.text = pdfText
                            currentPdfUri = uri

                            document.close()
                            Toast.makeText(this, "PDF загружен успешно", Toast.LENGTH_SHORT).show()
                            updateSpeechButtonState()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка загрузки PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                        e.printStackTrace()
                    }
                } finally {
                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                    }
                }
            }.start()
        } catch (e: Exception) {
            binding.progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun copyTextToClipboard() {
        val selectedText = binding.pdfTextView.text.toString()
        val defaultText = getString(R.string.default_pdf_text)

        if (selectedText.isNotEmpty() && selectedText != defaultText) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PDF текст", selectedText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Текст скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Нет текста для копирования", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePdfToUri(uri: Uri) {
        try {
            currentPdfUri?.let { sourceUri ->
                contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                        Toast.makeText(this, "PDF сохранен успешно", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePdf() {
        currentPdfUri?.let { uri ->
            if (!hasRequiredPermissions()) {
                Toast.makeText(this, "Нет разрешений для доступа к файлу", Toast.LENGTH_SHORT).show()
                checkAndRequestPermissions()
                return
            }

            try {
                // Создаем временный файл для sharing
                val tempFile = File(cacheDir, "shared_document_${System.currentTimeMillis()}.pdf")

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Получаем URI через FileProvider
                val shareUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    tempFile
                )

                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(shareIntent, "Поделиться PDF через"))

            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка отправки: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        } ?: run {
            Toast.makeText(this, "Сначала загрузите PDF файл", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAboutDialog() {
        val aboutText = """
            VShargin (C) 2025
            vaspull9@gmail.com
            PdfChitalka, v1.0
            
            Программа чтения и озвучки PDF файлов
            
            Функции:
            • Открытие PDF файлов
            • Извлечение и копирование текста
            • Озвучивание текста (TTS)
            • Сохранение и обмен файлами
            • Поддержка Android 5.0+
            
            Используемые технологии:
            • PDFBox для обработки PDF
            • Android TextToSpeech
            • Material Design 3
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("О компании")
            .setMessage(aboutText)
            .setPositiveButton("Закрыть", null)
            .setIcon(R.drawable.ic_info_circle)
            .show()
    }
}