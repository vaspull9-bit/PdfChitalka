// PdfChitalka v6.5.3 21/09/2025 21:30 Стабильная
// Чего нет: Женского голоса нет, повторений страницы нет, показа страницы чтения нет.
// Все остальное - есть и мы пускаем эту версия в продакшн

    package com.example.pdfchitalka

    import android.annotation.SuppressLint
    import android.content.Intent
    import android.content.SharedPreferences
    import android.graphics.Bitmap
    import android.net.Uri
    import android.os.Build
    import android.os.Bundle
    import android.util.Log
    import android.speech.tts.TextToSpeech
    import android.speech.tts.UtteranceProgressListener
    import android.view.View
    import android.view.WindowInsets
    import android.view.WindowInsetsController
    import android.widget.Toast
    import androidx.activity.OnBackPressedCallback
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.appcompat.app.AlertDialog
    import androidx.appcompat.widget.PopupMenu
    import androidx.core.content.FileProvider
    import androidx.core.view.isVisible
    import com.example.pdfchitalka.databinding.ActivityMainBinding
    import com.tom_roush.pdfbox.text.PDFTextStripper
    import java.io.File
    import java.io.FileOutputStream
    import java.util.*
    import android.widget.CheckBox
    import android.widget.SeekBar
    import android.widget.RadioGroup
    import android.graphics.pdf.PdfRenderer
    import android.os.ParcelFileDescriptor
    import android.view.ScaleGestureDetector
    import kotlin.math.max
    import kotlin.math.min
    import android.view.MotionEvent
    import kotlin.math.abs
    import android.widget.EditText
    import com.google.gson.Gson
    import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
    import androidx.appcompat.app.AppCompatActivity
    import com.tom_roush.pdfbox.pdmodel.PDDocument

    // Data class для закладок
    data class Bookmark(
        val position: Int,
        val title: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Настройки TTS
    data class TtsSettings(
        val showCurrentPage: Boolean = true,
        val speed: Float = 1.0f,
        val repeatMode: Int = 0,
        val keepScreenOn: Boolean = false,
        val voice: String = "female"
    )

    class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
        private var startY: Float = 0f
        private lateinit var binding: ActivityMainBinding
        private lateinit var prefs: SharedPreferences
        private var currentPdfUri: Uri? = null
        private var currentPdfFile: File? = null
        private var currentParcelFd: ParcelFileDescriptor? = null
        private var bookmarks: MutableList<Bookmark> = mutableListOf()
        private var isFullscreen: Boolean = false

        // PDF данные
        private var pdfRenderer: PdfRenderer? = null
        private var isTextPdf: Boolean = false
        private var currentPage: Int = 0
        private var totalPages: Int = 0

        // TTS компоненты
        private var textToSpeech: TextToSpeech? = null
        private var isTtsInitialized: Boolean = false
        private var isSpeaking: Boolean = false
        private var isPaused: Boolean = false
        private var ttsSettings: TtsSettings = TtsSettings()
        private var shouldShowPlayer: Boolean = false

        // Для поиска
        private var searchQuery: String = ""
        private var searchResults: List<Int> = emptyList()
        private var searchResultPages: List<Int> = emptyList()
        private var currentSearchIndex: Int = 0
        private var currentScale: Float = 1.0f
        private val scaleDetector: ScaleGestureDetector by lazy {
            ScaleGestureDetector(this, ScaleListener())
        }

        // Текст и позиции
        private var currentTextContent: String = ""
        private var currentSpeechPosition: Int = 0
        private var currentTextPosition: Int = 0
        private var isReadingFullBook: Boolean = false
        private var shouldAutoScroll: Boolean = true

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
        // Добавить в переменные класса:
        private var currentTranslateX: Float = 0f
        private var currentTranslateY: Float = 0f
        private var isScaling: Boolean = false
        private var lastTouchX: Float = 0f
        private var lastTouchY: Float = 0f


        ////////////////////////////////////////////////////////////////////////
        //
        //
        //                        ФУНКЦИИ
        //
        //
        ///////////////////////////////////////////////////////////////////////



        @SuppressLint("ClickableViewAccessibility")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Инициализация PDFBox
            PDFBoxResourceLoader.init(applicationContext)

            prefs = getSharedPreferences("tts_settings", MODE_PRIVATE)
            loadTtsSettings()

            // Инициализация TTS
            textToSpeech = TextToSpeech(this, this)

            // Настройка кнопок зума
            binding.btnZoomIn.setOnClickListener { zoomIn() }
            binding.btnZoomOut.setOnClickListener { zoomOut() }
            binding.btnResetZoom.setOnClickListener { resetZoom() }

            // Жесты для листания страниц
            // Заменяем текущий setOnTouchListener на этот:
            binding.pdfImageView.setOnTouchListener { v, event ->
                scaleDetector.onTouchEvent(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.y
                        lastTouchX = event.x
                        lastTouchY = event.y

                        // Сбрасываем флаг масштабирования при новом касании
                        if (!scaleDetector.isInProgress) {
                            isScaling = false
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!scaleDetector.isInProgress && !isScaling) {
                            // Сдвиг изображения
                            val deltaX = event.x - lastTouchX
                            val deltaY = event.y - lastTouchY

                            currentTranslateX += deltaX
                            currentTranslateY += deltaY

                            // Ограничиваем сдвиг чтобы не уйти слишком далеко
                            limitTranslation()

                            applyImageTransform()

                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        true
                    }


                    MotionEvent.ACTION_UP -> {
                        val endY = event.y
                        val deltaY = startY - endY

                        // Обрабатываем свайп только если не было масштабирования и сдвига
                        if (abs(deltaY) > 100 && !isScaling && currentTranslateX == 0f && currentTranslateY == 0f) {
                            if (isSpeaking || isPaused) {
                                if (deltaY > 0) {
                                    showNextPage()
                                    currentSpeechPosition = getTextPositionForPage(currentPage)
                                    speakFromPosition(currentSpeechPosition)
                                } else {
                                    showPreviousPage()
                                    currentSpeechPosition = getTextPositionForPage(currentPage)
                                    speakFromPosition(currentSpeechPosition)
                                }
                            } else {
                                if (deltaY > 0) showNextPage() else showPreviousPage()
                            }
                        }
                        true
                    }

                    else -> false
                }
            }



            // Пустой клик для accessibility
            binding.pdfImageView.setOnClickListener { }

            // Настройка Toolbar
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)

            // Обработка кнопки назад
            setupBackPressedHandler()

            // Проверка и запрос разрешений
            checkAndRequestPermissions()

            setupUI()
            handleIntent(intent)
            applyTtsSettings()
        }

// Добавляем функции для ограничения сдвига и применения трансформации:
        private fun limitTranslation() {
            val scaledWidth = binding.pdfImageView.width * currentScale
            val scaledHeight = binding.pdfImageView.height * currentScale
            val viewWidth = binding.pdfImageView.width
            val viewHeight = binding.pdfImageView.height

            // Ограничиваем сдвиг по X
            val maxTranslateX = max(0f, (scaledWidth - viewWidth) / 2)
            val minTranslateX = -maxTranslateX
            currentTranslateX = currentTranslateX.coerceIn(minTranslateX, maxTranslateX)

            // Ограничиваем сдвиг по Y
            val maxTranslateY = max(0f, (scaledHeight - viewHeight) / 2)
            val minTranslateY = -maxTranslateY
            currentTranslateY = currentTranslateY.coerceIn(minTranslateY, maxTranslateY)
        }

        private fun applyImageTransform() {
            binding.pdfImageView.translationX = currentTranslateX
            binding.pdfImageView.translationY = currentTranslateY
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
        }

        override fun onDestroy() {
            saveTtsSettings()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null

            // Закрываем PdfRenderer и дескриптор
            try {
                pdfRenderer?.close()
                currentParcelFd?.close()
            } catch (e: Exception) {
                Log.e("PDF", "Error closing resources", e)
            }

            // Удаляем временный файл
            currentPdfFile?.delete()

            super.onDestroy()
        }

        override fun onResume() {
            super.onResume()

            binding.pdfImageView.visibility = View.VISIBLE
            binding.pdfTextView.visibility = View.GONE
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
            updatePlayerVisibility()
            updateSpeakButtonState() // ← ДОБАВИТЬ ЗДЕСЬ
        }

        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                val languagesToTry = listOf(
                    Locale.forLanguageTag("ru-RU"),
                    Locale.getDefault(),
                    Locale.US,
                    Locale.UK
                )

                var success = false
                for (locale in languagesToTry) {
                    val result = textToSpeech?.setLanguage(locale)
                    if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        isTtsInitialized = true
                        success = true
                        setupTtsListeners()
                        checkAvailableVoices()
                        setVoice(ttsSettings.voice)
                        break
                    }
                }

                if (!success) {
                    Toast.makeText(this, "Нет подходящего языка TTS", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Ошибка инициализации TTS", Toast.LENGTH_SHORT).show()
            }
        }

        private fun setupUI() {
            binding.btnSearch.setOnClickListener {
                if (isTextPdf) {
                    showSearchDialog()
                } else {
                    Toast.makeText(this, "Поиск доступен только для текстовых PDF", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnNextSearch.setOnClickListener { showNextSearchResult() }
            binding.btnPrevSearch.setOnClickListener { showPrevSearchResult() }
            binding.btnCloseSearch.setOnClickListener {
                binding.searchPanel.visibility = View.GONE
                binding.pdfTextView.visibility = View.GONE
                binding.pdfImageView.visibility = View.VISIBLE
            }

            binding.btnMenu.setOnClickListener { showPopupMenu(it) }

            binding.btnPrevPage.setOnClickListener {
                if (isSpeaking || isPaused) {
                    showPreviousPage()
                    currentSpeechPosition = getTextPositionForPage(currentPage)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showPreviousPage()
                }
            }

            binding.btnNextPage.setOnClickListener {
                if (isSpeaking || isPaused) {
                    showNextPage()
                    currentSpeechPosition = getTextPositionForPage(currentPage)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showNextPage()
                }
            }

            // ИСПРАВЛЕНИЕ: Кнопка паузы теперь работает и как плей
            binding.btnPause.setOnClickListener {
                if (isSpeaking) {
                    textToSpeech?.stop()
                    isSpeaking = false
                    isPaused = true
                    updatePlayerButtons()
                    updatePlayerVisibility()
                    Toast.makeText(this, "Пауза", Toast.LENGTH_SHORT).show()
                } else if (isPaused) {
                    isSpeaking = true
                    isPaused = false
                    speakFromPosition(currentSpeechPosition)
                    updatePlayerButtons()
                    updatePlayerVisibility()
                    Toast.makeText(this, "Продолжено", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnStop.setOnClickListener { stopSpeech() }

            // ИСПРАВЛЕНИЕ: Убираем отдельную кнопку Play - теперь одна кнопка Pause/Play
            binding.btnPlay.visibility = View.GONE

            // Кнопки навигации в тулбаре
            binding.btnFirstPage.setOnClickListener {
                if (isSpeaking || isPaused) {
                    showPage(0)
                    currentSpeechPosition = getTextPositionForPage(0)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showPage(0)
                }
            }

            binding.btnLastPage.setOnClickListener {
                val lastPage = totalPages - 1
                if (isSpeaking || isPaused) {
                    showPage(lastPage)
                    currentSpeechPosition = getTextPositionForPage(lastPage)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showPage(lastPage)
                }
            }

            // Кнопка репродуктора - ВЫНЕСЕНА ОТДЕЛЬНО!
            binding.btnSpeakToolbar.setOnClickListener {
                if (currentPdfFile == null) {
                    Toast.makeText(this, "Сначала загрузите PDF файл", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Проверяем готовность текста для озвучки
                if (currentTextContent.isNotEmpty() && currentTextContent.length > 100) {
                    showSpeechOptionsDialog()
                } else {
                    Toast.makeText(this, "Текст еще не готов для озвучки", Toast.LENGTH_SHORT).show()
                }
            }

            binding.btnRewindForward.setOnClickListener {
                if (isSpeaking || isPaused) {
                    showNextPage()
                    currentSpeechPosition = getTextPositionForPage(currentPage)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showNextPage()
                }
            }

            binding.btnRewindToEnd.setOnClickListener {
                if (isSpeaking || isPaused) {
                    val lastPage = totalPages - 1
                    showPage(lastPage)
                    currentSpeechPosition = getTextPositionForPage(lastPage)
                    speakFromPosition(currentSpeechPosition)
                } else {
                    showPage(totalPages - 1)
                }
            }

            binding.btnTtsSettings.setOnClickListener { showTtsSettingsDialog() }
        }

        ///////////////////////////////////////////////////
        //
        //                       перейти на страницу
        //
        //
        ///////////////////////////////////////////////

        private fun showGotoPageDialog() {
            if (totalPages <= 1) {
                Toast.makeText(this, "Документ содержит только 1 страницу", Toast.LENGTH_SHORT).show()
                return
            }

            val input = EditText(this).apply {
                hint = "Введите номер страницы (1-$totalPages)"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }

            AlertDialog.Builder(this)
                .setTitle("Перейти на страницу")
                .setView(input)
                .setPositiveButton("Перейти") { _, _ ->
                    val pageNum = input.text.toString().toIntOrNull()
                    if (pageNum != null && pageNum in 1..totalPages) {
                        val targetPage = pageNum - 1
                        if (isSpeaking || isPaused) {
                            showPage(targetPage)
                            currentSpeechPosition = getTextPositionForPage(targetPage)
                            speakFromPosition(currentSpeechPosition)
                        } else {
                            showPage(targetPage)
                        }
                        Toast.makeText(this, "Переход на страницу $pageNum", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Неверный номер страницы", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }


        ///////////////////////////////////////////////////
        //
        //                       Озвучка
        //
        //
        ///////////////////////////////////////////////


        private fun shouldAutoScrollToNextPage(currentPosition: Int): Boolean {
            if (!ttsSettings.showCurrentPage) return false
            if (currentPage >= totalPages - 1) return false // последняя страница

            val nextPageStartPos = getTextPositionForPage(currentPage + 1)
            // Если текущая позиция достигла или превысила начало следующей страницы
            return currentPosition >= nextPageStartPos
        }


        private fun loadTtsSettings() {
            ttsSettings = TtsSettings(
                showCurrentPage = prefs.getBoolean("show_current_page", true),
                speed = prefs.getFloat("speed", 1.0f),
                repeatMode = prefs.getInt("repeat_mode", 0),
                keepScreenOn = prefs.getBoolean("keep_screen_on", false),
                voice = prefs.getString("voice", "female") ?: "female"
            )
        }

        private fun saveTtsSettings() {
            prefs.edit().apply {
                putBoolean("show_current_page", ttsSettings.showCurrentPage)
                putFloat("speed", ttsSettings.speed)
                putInt("repeat_mode", ttsSettings.repeatMode)
                putBoolean("keep_screen_on", ttsSettings.keepScreenOn)
                putString("voice", ttsSettings.voice)
                apply()
            }
        }

        private fun applyTtsSettings() {
            if (ttsSettings.keepScreenOn) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            textToSpeech?.setSpeechRate(ttsSettings.speed)
            shouldAutoScroll = ttsSettings.showCurrentPage

            if (ttsSettings.showCurrentPage) {
                updatePageIndicator()
            }

            setVoice(ttsSettings.voice)

            if (isSpeaking) {
                textToSpeech?.setSpeechRate(ttsSettings.speed)
                setVoice(ttsSettings.voice)
            }

            updatePlayerVisibility()
        }

        private fun setVoice(voiceType: String) {
            if (!isTtsInitialized) return

            textToSpeech?.let { tts ->
                try {
                    val availableVoices = tts.voices?.toList() ?: emptyList()

                    // Лучшая логика выбора голоса
                    val targetVoice = when (voiceType) {
                        "male" -> availableVoices.firstOrNull { voice ->
                            voice.name.contains("male", true) ||
                                    voice.name.contains("мужск", true) ||
                                    voice.name.contains("низк", true) ||
                                    voice.name.contains("low", true) ||
                                    voice.name.contains("deep", true) ||
                                    voice.locale.toString().contains("ru") &&
                                    !voice.name.contains("female", true)
                        }
                        else -> availableVoices.firstOrNull { voice ->
                            voice.name.contains("female", true) ||
                                    voice.name.contains("женск", true) ||
                                    voice.name.contains("высок", true) ||
                                    voice.name.contains("high", true) ||
                                    voice.locale.toString().contains("ru") &&
                                    !voice.name.contains("male", true)
                        }
                    } ?: availableVoices.firstOrNull { voice ->
                        voice.locale.toString().contains("ru")
                    } ?: availableVoices.firstOrNull() // Fallback на любой голос

                    if (targetVoice != null) {
                        tts.voice = targetVoice
                        Log.d("TTS", "Установлен голос: ${targetVoice.name}, тип: $voiceType")

                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Голос: ${targetVoice.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TTS", "Ошибка установки голоса: ${e.message}")
                }
            }
        }

        private fun checkAvailableVoices() {
            textToSpeech?.voices?.forEach { voice ->
                Log.d("TTS_DEBUG", "Голос: ${voice.name}, Язык: ${voice.locale}")
            }
        }

        private fun setupTtsListeners() {
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        isSpeaking = true
                        shouldShowPlayer = true
                        updatePlayerVisibility()
                        updatePlayerButtons()
                        Log.d("TTS", "Озвучка начата: $utteranceId")
                    }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        isSpeaking = false
                        updatePlayerButtons()

                        if (utteranceId?.startsWith("speak_") == true) {
                            val nextPos = utteranceId.removePrefix("speak_").toIntOrNull() ?: 0
                            currentTextPosition = nextPos

                            // АВТОПРОКРУТКА: Если включена опция показа текущей страницы
                            if (shouldAutoScrollToNextPage(nextPos)) {
                                showNextPage()
                                currentSpeechPosition = getTextPositionForPage(currentPage)
                                if (isReadingFullBook) {
                                    speakFromPosition(currentSpeechPosition)
                                }
                            }

                            if (isReadingFullBook && nextPos < currentTextContent.length) {
                                speakFromPosition(nextPos)
                            } else if (!isReadingFullBook) {
                                // ОБРАБОТКА РЕЖИМОВ ПОВТОРА
                                when (ttsSettings.repeatMode) {
                                    1 -> { // Повтор текущей страницы
                                        currentSpeechPosition = getTextPositionForPage(currentPage)
                                        if (currentSpeechPosition < currentTextContent.length) {
                                            speakFromPosition(currentSpeechPosition)
                                        }
                                    }
                                    2 -> { // Повтор всей книги
                                        startSpeechFullBook(false)
                                    }
                                    else -> stopSpeech()
                                }
                            } else {
                                stopSpeech()
                            }
                        }
                        updatePlayerVisibility()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        Log.e("TTS", "Ошибка озвучки (старый метод): $utteranceId")
                        stopSpeech()
                        Toast.makeText(this@MainActivity, "Ошибка озвучки", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    runOnUiThread {
                        Log.e("TTS", "Ошибка озвучки: $errorCode, utterance: $utteranceId")
                        stopSpeech()
                        val errorMsg = when (errorCode) {
                            TextToSpeech.ERROR_INVALID_REQUEST -> "Неверный запрос"
                            TextToSpeech.ERROR_NETWORK -> "Ошибка сети"
                            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Таймаут сети"
                            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Данные не установлены"
                            TextToSpeech.ERROR_SERVICE -> "Ошибка сервиса"
                            TextToSpeech.ERROR_SYNTHESIS -> "Ошибка синтеза"
                            else -> "Неизвестная ошибка: $errorCode"
                        }
                        Toast.makeText(this@MainActivity, "Ошибка озвучки: $errorMsg", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        private fun prepareTextForTts(pdfFile: File) {
            Thread {
                try {
                    val document = PDDocument.load(pdfFile)
                    val textStripper = PDFTextStripper().apply {
                        lineSeparator = "\n"
                        wordSeparator = " "
                    }

                    val fullText = textStripper.getText(document)
                    document.close()

                    runOnUiThread {
                        currentTextContent = fullText
                        isTextPdf = currentTextContent.length > 100
                        updateSpeakButtonState() // ← ДОБАВИТЬ ЗДЕСЬ

                        if (isTextPdf) {
                            Toast.makeText(this, "Текст готов для озвучки", Toast.LENGTH_SHORT).show()
                            binding.progressBar.visibility = View.GONE
                            binding.pdfImageView.visibility = View.VISIBLE
                            binding.pdfTextView.visibility = View.GONE
                        } else {
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        Log.e("TTS", "Ошибка извлечения текста: ${e.message}")
                    }
                }
            }.start()
        }

        private fun loadPdfForViewing(pdfFile: File) {
            try {
                currentParcelFd?.close()
                currentParcelFd = null
                pdfRenderer?.close()

                binding.pdfImageView.visibility = View.VISIBLE

                currentParcelFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(currentParcelFd!!)
                totalPages = pdfRenderer!!.pageCount
                showPage(0)
                updatePageIndicator()
            } catch (e: Exception) {
                Log.e("PDF", "Ошибка загрузки PDF", e)
                Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
            }
        }

        private fun loadPdfFromUri(uri: Uri) {
            if (isSpeaking || isPaused) {
                stopSpeech()
            }

            if (!hasRequiredPermissions()) {
                checkAndRequestPermissions()
                return
            }

            try {
                binding.progressBar.visibility = View.VISIBLE

                Thread {
                    try {
                        val tempFile = File(cacheDir, "temp_pdf_${System.currentTimeMillis()}.pdf")
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        runOnUiThread {
                            currentPdfFile = tempFile
                            currentPdfUri = uri
                            loadPdfForViewing(tempFile)
                            prepareTextForTts(tempFile)
                            loadBookmarks()
                        }

                    } catch (e: Exception) {
                        Log.e("PDF", "Ошибка загрузки PDF", e)
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showPage(pageIndex: Int) {
            try {
                pdfRenderer?.let { renderer ->
                    if (pageIndex in 0 until renderer.pageCount) {
                        val page = renderer.openPage(pageIndex)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        binding.pdfImageView.setImageBitmap(bitmap)
                        binding.pdfImageView.visibility = View.VISIBLE
                        binding.pdfTextView.visibility = View.GONE

                        currentPage = pageIndex
                        page.close()

                        updatePageIndicator()
                        scaleToFit()

                        if (isSpeaking || isPaused) {
                            currentSpeechPosition = getTextPositionForPage(currentPage)
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка отображения страницы", Toast.LENGTH_SHORT).show()
            }
        }

        private fun updatePageIndicator() {
            val hasPdfLoaded = currentPdfFile != null
            val hasMultiplePages = totalPages > 1

            binding.pageIndicator.text = resources.getString(R.string.page_indicator, currentPage + 1, totalPages)
            binding.pageIndicator.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE

            // Кнопки навигации
            binding.btnPrevPage.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE
            binding.btnNextPage.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE
            binding.btnFirstPage.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE
            binding.btnLastPage.visibility = if (hasMultiplePages) View.VISIBLE else View.GONE

            // Кнопка репродуктора - показываем если есть PDF, а isTextPdf обновим позже
            binding.btnSpeakToolbar.visibility = if (hasPdfLoaded) View.VISIBLE else View.GONE
            binding.btnSpeakToolbar.isEnabled = isTextPdf // включаем только если текстовый PDF

            // Состояние кнопок
            binding.btnPrevPage.isEnabled = currentPage > 0
            binding.btnNextPage.isEnabled = currentPage < totalPages - 1
            binding.btnFirstPage.isEnabled = currentPage > 0
            binding.btnLastPage.isEnabled = currentPage < totalPages - 1
        }

        private fun showPreviousPage() {
            if (currentPage > 0) {
                showPage(currentPage - 1)
            }
        }

        private fun showNextPage() {
            if (currentPage < totalPages - 1) {
                showPage(currentPage + 1)
            }
        }

        private fun updateSpeakButtonState() {
            val hasPdfLoaded = currentPdfFile != null
            binding.btnSpeakToolbar.visibility = if (hasPdfLoaded) View.VISIBLE else View.GONE
            binding.btnSpeakToolbar.isEnabled = isTextPdf
        }

        private fun startSpeechFullBook(fromCurrentPage: Boolean = false) {
            if (!isTextPdf) return

            isReadingFullBook = true
            isSpeaking = true
            isPaused = false
            shouldShowPlayer = true

            currentSpeechPosition = if (fromCurrentPage) {
                getTextPositionForPage(currentPage)
            } else {
                0
            }

            showPlayerPanel()
            speakFromPosition(currentSpeechPosition)

            val message = if (fromCurrentPage) {
                "Озвучка с текущей страницы до конца"
            } else {
                "Озвучка всей книги с начала"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        private fun speakPage() {
            if (!isTextPdf) return

            isReadingFullBook = false
            isSpeaking = true
            isPaused = false
            shouldShowPlayer = true

            currentSpeechPosition = getTextPositionForPage(currentPage)
            Log.d("TTS", "Озвучка страницы $currentPage, позиция: $currentSpeechPosition")

            showPlayerPanel()
            speakFromPosition(currentSpeechPosition)

            Toast.makeText(this, "Озвучка начата", Toast.LENGTH_SHORT).show()
        }

        private fun speakFromPosition(startPos: Int) {
            if (startPos >= currentTextContent.length) {
                Log.d("TTS", "Достигнут конец текста")
                stopSpeech()
                return
            }

            if (!isTtsInitialized) {
                Log.e("TTS", "TTS не инициализирован")
                return
            }

            currentTextPosition = startPos

            if (ttsSettings.showCurrentPage) {
                val currentPageForPosition = getPageForTextPosition(startPos)
                if (currentPageForPosition != currentPage) {
                    showPage(currentPageForPosition)
                }
            }

            val textToRead = currentTextContent.substring(startPos)
            val maxChunkSize = 4000
            val chunk = if (textToRead.length > maxChunkSize) {
                textToRead.substring(0, maxChunkSize)
            } else {
                textToRead
            }

            val nextPosition = startPos + chunk.length

            if (chunk.isBlank()) {
                Log.d("TTS", "Пустой чанк для озвучки")
                if (isReadingFullBook && nextPosition < currentTextContent.length) {
                    speakFromPosition(nextPosition)
                } else {
                    stopSpeech()
                }
                return
            }

            try {
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "speak_$nextPosition")
                }

                textToSpeech?.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, "speak_$nextPosition")
                Log.d("TTS", "Озвучка начата с позиции $startPos, длина: ${chunk.length}")
            } catch (e: Exception) {
                Log.e("TTS", "Ошибка запуска озвучки: ${e.message}")
                stopSpeech()
            }
        }

        private fun startSpeech() {
            if (!isTextPdf) {
                Toast.makeText(this, "Озвучка недоступна", Toast.LENGTH_SHORT).show()
                return
            }

            try {
                textToSpeech?.stop()
                speakPage()
                showPlayerPanel()
                Toast.makeText(this, "Озвучка начата", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка озвучки", Toast.LENGTH_SHORT).show()
            }
        }

        private fun stopSpeech() {
            textToSpeech?.stop()
            isSpeaking = false
            isPaused = false
            shouldShowPlayer = false
            updatePlayerButtons()
            updatePlayerVisibility()
            Toast.makeText(this, "Озвучка остановлена", Toast.LENGTH_SHORT).show()
        }

        private fun showPlayerPanel() {
            shouldShowPlayer = true
            updatePlayerVisibility()
            updatePlayerButtons()
        }

        private fun updatePlayerVisibility() {
            val shouldBeVisible = shouldShowPlayer && (isSpeaking || isPaused)
            binding.playerPanel.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
        }

        private fun updatePlayerButtons() {
            // ИСПРАВЛЕНИЕ 2: Одна кнопка работает как Pause/Play
            if (isSpeaking) {
                binding.btnPause.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnPause.isEnabled = true
            } else if (isPaused) {
                binding.btnPause.setImageResource(android.R.drawable.ic_media_play)
                binding.btnPause.isEnabled = true
            } else {
                binding.btnPause.setImageResource(android.R.drawable.ic_media_play)
                binding.btnPause.isEnabled = false
            }

            binding.btnStop.isEnabled = isSpeaking || isPaused
        }

        private fun getTextPositionForPage(page: Int): Int {
            if (totalPages == 0 || currentTextContent.isEmpty()) return 0

            try {
                PDDocument.load(currentPdfFile).use { document ->
                    var totalChars = 0

                    for (i in 0 until page) {
                        val textStripper = PDFTextStripper().apply {
                            startPage = i + 1
                            endPage = i + 1
                        }
                        val pageText = textStripper.getText(document)
                        totalChars += pageText.length
                    }

                    return totalChars
                }
            } catch (e: Exception) {
                val approxPageLength = currentTextContent.length / max(1, totalPages)
                return (page * approxPageLength).coerceAtMost(currentTextContent.length - 1)
            }
        }

        private fun getPageForTextPosition(position: Int): Int {
            if (currentPdfFile == null || currentTextContent.isEmpty()) return currentPage

            try {
                PDDocument.load(currentPdfFile).use { document ->
                    var totalChars = 0
                    val totalPageCount = document.numberOfPages

                    for (page in 0 until totalPageCount) {
                        val textStripper = PDFTextStripper().apply {
                            startPage = page + 1
                            endPage = page + 1
                        }
                        val pageText = textStripper.getText(document)
                        totalChars += pageText.length

                        if (position < totalChars) {
                            return page
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AutoPage", "Ошибка определения страницы: ${e.message}")
            }

            return (position * totalPages / currentTextContent.length).coerceIn(0, totalPages - 1)
        }

//  Меню озвучки
private fun showSpeechOptionsDialog() {
    if (currentPdfFile == null) {
        Toast.makeText(this, "Сначала загрузите PDF файл", Toast.LENGTH_SHORT).show()
        return
    }

    if (currentTextContent.isEmpty() || currentTextContent.length <= 100) {
        Toast.makeText(this, "Текст еще не готов для озвучки", Toast.LENGTH_SHORT).show()
        return
    }

    val options = arrayOf(
        "Озвучить всю книгу с начала",
        "Озвучить с текущей страницы до конца",
        "Озвучить только текущую страницу"
    )

    AlertDialog.Builder(this)
        .setTitle("Режим озвучки")
        .setItems(options) { _, which ->
            when (which) {
                0 -> startSpeechFullBook(false)
                1 -> startSpeechFullBook(true)
                2 -> speakPage()
            }
        }
        .setNegativeButton("Отмена", null)
        .show()
}

        private fun showTtsSettingsDialog() {
            val dialogView = layoutInflater.inflate(R.layout.dialog_tts_settings, null)

            val cbShowCurrentPage = dialogView.findViewById<CheckBox>(R.id.cbShowCurrentPage)
            val sbSpeed = dialogView.findViewById<SeekBar>(R.id.sbSpeed)
            val rgRepeatMode = dialogView.findViewById<RadioGroup>(R.id.rgRepeatMode)
            val cbKeepScreenOn = dialogView.findViewById<CheckBox>(R.id.cbKeepScreenOn)
            val rgVoice = dialogView.findViewById<RadioGroup>(R.id.rgVoice)

            cbShowCurrentPage.isChecked = ttsSettings.showCurrentPage
            sbSpeed.progress = (ttsSettings.speed * 100).toInt()

            when (ttsSettings.repeatMode) {
                1 -> rgRepeatMode.check(R.id.rbRepeatPage)
                2 -> rgRepeatMode.check(R.id.rbRepeatAll)
                else -> rgRepeatMode.check(R.id.rbRepeatNone)
            }

            cbKeepScreenOn.isChecked = ttsSettings.keepScreenOn

            when (ttsSettings.voice) {
                "male" -> rgVoice.check(R.id.rbVoiceMale)
                else -> rgVoice.check(R.id.rbVoiceFemale)
            }

            val wasSpeaking = isSpeaking
            val wasPaused = isPaused

            AlertDialog.Builder(this)
                .setTitle("Настройки чтения")
                .setView(dialogView)
                .setPositiveButton("Сохранить") { _, _ ->
                    // Сохраняем настройки независимо от состояния загрузки
                    val newSettings = TtsSettings(
                        showCurrentPage = cbShowCurrentPage.isChecked,
                        speed = sbSpeed.progress / 100f,
                        repeatMode = when (rgRepeatMode.checkedRadioButtonId) {
                            R.id.rbRepeatPage -> 1
                            R.id.rbRepeatAll -> 2
                            else -> 0
                        },
                        keepScreenOn = cbKeepScreenOn.isChecked,
                        voice = when (rgVoice.checkedRadioButtonId) {
                            R.id.rbVoiceMale -> "male"
                            else -> "female"
                        }
                    )

                    ttsSettings = newSettings
                    saveTtsSettings()
                    applyTtsSettings()
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }


        private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val oldScale = currentScale
                currentScale *= scaleFactor
                currentScale = max(0.5f, min(currentScale, 5.0f))

                // Сбрасываем сдвиг при изменении масштаба
                if (oldScale != currentScale) {
                    currentTranslateX = 0f
                    currentTranslateY = 0f
                }

                applyImageTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }

        private fun zoomIn() {
            currentScale = min(currentScale * 1.2f, 5.0f)
            currentTranslateX = 0f
            currentTranslateY = 0f
            applyImageTransform()
        }

        private fun zoomOut() {
            currentScale = max(currentScale * 0.8f, 0.5f)
            currentTranslateX = 0f
            currentTranslateY = 0f
            applyImageTransform()
        }

        private fun resetZoom() {
            currentScale = 1.0f
            currentTranslateX = 0f
            currentTranslateY = 0f
            applyImageTransform()
        }

        private fun scaleToFit() {
            binding.pdfImageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            currentScale = 1.0f
            currentTranslateX = 0f
            currentTranslateY = 0f
            applyImageTransform()
        }

        private fun setupBackPressedHandler() {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        isFullscreen -> toggleFullscreen()
                        binding.searchPanel.isVisible -> {
                            binding.searchPanel.visibility = View.GONE
                            binding.pdfTextView.visibility = View.GONE
                            binding.pdfImageView.visibility = View.VISIBLE
                        }
                        isSpeaking || isPaused -> {
                            // Плеер остается видимым
                        }
                        binding.playerPanel.isVisible -> {
                            shouldShowPlayer = false
                            updatePlayerVisibility()
                        }
                        binding.progressBar.isVisible -> showExitConfirmation()
                        else -> isEnabled = false
                    }
                }
            }
            onBackPressedDispatcher.addCallback(this, callback)
        }

        private fun showExitConfirmation() {
            AlertDialog.Builder(this)
                .setTitle("Прервать загрузку?")
                .setPositiveButton("Да") { _, _ -> finish() }
                .setNegativeButton("Нет", null)
                .show()
        }

        private fun checkAndRequestPermissions() {
            val permissions = mutableListOf<String>()

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissions.addAll(listOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ))
            }

            if (permissions.isNotEmpty()) {
                requestPermissionLauncher.launch(permissions.toTypedArray())
            }
        }

        private fun hasRequiredPermissions(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            handleIntent(intent)
        }

        private fun handleIntent(intent: Intent) {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let { loadPdfFromUri(it) }
            }
        }

        private fun showPopupMenu(view: View) {
            val popup = PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

            val hasPdfLoaded = currentPdfFile != null

            // ИСПРАВЛЕНИЕ 1: Убираем копирование из меню
            popup.menu.findItem(R.id.menu_save).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_share).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_speak).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_stop).isEnabled = isSpeaking || isPaused
            popup.menu.findItem(R.id.menu_bookmark).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_show_bookmarks).isEnabled = bookmarks.isNotEmpty()
            popup.menu.findItem(R.id.menu_search).isEnabled = isTextPdf && currentTextContent.isNotEmpty()
            popup.menu.findItem(R.id.menu_tts_settings).isEnabled = true
            popup.menu.findItem(R.id.menu_goto_page).isEnabled = hasPdfLoaded && totalPages > 1

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_load -> {
                        pickPdfFile.launch("application/pdf")
                        true
                    }
                    R.id.menu_save -> {
                        savePdfFile.launch("document_${System.currentTimeMillis()}.pdf")
                        true
                    }
                    R.id.menu_share -> {
                        sharePdf()
                        true
                    }
                    R.id.menu_speak -> {
                        showSpeechOptionsDialog()
                        true
                    }
                    R.id.menu_search -> {
                        showSearchDialog()
                        true
                    }
                    R.id.menu_goto_page -> {
                        showGotoPageDialog()
                        true
                    }
                    R.id.menu_stop -> {
                        stopSpeech()
                        true
                    }
                    R.id.menu_bookmark -> {
                        saveBookmark()
                        true
                    }
                    R.id.menu_show_bookmarks -> {
                        showBookmarksDialog()
                        true
                    }
                    R.id.menu_fullscreen -> {
                        toggleFullscreen()
                        true
                    }
                   R.id.menu_about -> {
                        showAboutDialog()
                        true
                    }

                    R.id.menu_tts_settings -> {
                        showTtsSettingsDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }




        private fun saveBookmark() {
            val bookmark = Bookmark(
                position = currentPage,
                title = "Страница ${currentPage + 1}"
            )
            bookmarks.add(bookmark)
            Toast.makeText(this, "Закладка сохранена", Toast.LENGTH_SHORT).show()
        }

        private fun loadBookmarks() {
            val fileKey = currentPdfFile?.absolutePath ?: return
            val bookmarksJson = prefs.getString("bookmarks_$fileKey", "[]")
            bookmarks = if (bookmarksJson.isNullOrEmpty()) {
                mutableListOf()
            } else {
                Gson().fromJson(bookmarksJson, Array<Bookmark>::class.java).toMutableList()
            }
        }

        private fun showBookmarksDialog() {
            if (bookmarks.isEmpty()) {
                Toast.makeText(this, "Нет закладок", Toast.LENGTH_SHORT).show()
                return
            }

            val items = bookmarks.mapIndexed { index, bookmark ->
                "${index + 1}. ${bookmark.title}"
            }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Закладки")
                .setItems(items) { _, which ->
                    val page = bookmarks[which].position
                    showPage(page)
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        private fun toggleFullscreen() {
            isFullscreen = !isFullscreen
            binding.toolbar.visibility = if (isFullscreen) View.GONE else View.VISIBLE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    if (isFullscreen) {
                        controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    }
                }
            }
        }

        private fun savePdfToUri(uri: Uri) {
            try {
                currentPdfFile?.let { sourceFile ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                            Toast.makeText(this, "PDF сохранен", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }

        private fun sharePdf() {
            currentPdfFile?.let { file ->
                val shareUri = FileProvider.getUriForFile(
                    this, "${packageName}.provider", file
                )
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    type = "application/pdf"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Поделиться PDF"))
            } ?: run {
                Toast.makeText(this, "Сначала загрузите PDF", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showSearchDialog() {
            val input = EditText(this).apply {
                hint = "Введите текст для поиска"
            }

            AlertDialog.Builder(this)
                .setTitle("Поиск в PDF")
                .setView(input)
                .setPositiveButton("Искать") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotEmpty()) {
                        searchQuery = query
                        searchInPdf(query)
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        private fun searchInPdf(query: String) {
            if (currentTextContent.isEmpty()) {
                Toast.makeText(this, "Сначала загрузите PDF с текстом", Toast.LENGTH_SHORT).show()
                return
            }

            binding.progressBar.visibility = View.VISIBLE

            Thread {
                try {
                    val results = mutableListOf<Int>()
                    val pages = mutableListOf<Int>()
                    var index = currentTextContent.indexOf(query, 0, true)

                    while (index >= 0) {
                        results.add(index)
                        val page = findPageForTextPosition(index)
                        pages.add(page)
                        index = currentTextContent.indexOf(query, index + 1, true)
                    }

                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE

                        if (results.isNotEmpty()) {
                            searchResults = results
                            searchResultPages = pages
                            currentSearchIndex = 0
                            showSearchResult(results[0], query, pages[0])
                            Toast.makeText(this, "Найдено ${results.size} совпадений", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Не найдено: '$query'", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this, "Ошибка поиска", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        private fun findPageForTextPosition(position: Int): Int {
            if (currentPdfFile == null || currentTextContent.isEmpty()) return 0
            val approxPage = (position * totalPages / currentTextContent.length).coerceIn(0, totalPages - 1)
            return approxPage
        }

        private fun showSearchResult(position: Int, query: String, page: Int) {
            try {
                val start = max(0, position - 30)
                val end = min(currentTextContent.length, position + query.length + 30)
                val context = currentTextContent.substring(start, end)

                val highlighted = context.replace(query, "►$query◄", true)

                binding.searchResultText.text = highlighted
                binding.searchPosition.text = resources.getString(R.string.search_position, currentSearchIndex + 1, searchResults.size)
                binding.searchPageNumber.text = resources.getString(R.string.search_page, page + 1)

                binding.searchPageNumber.setOnClickListener {
                    if (isSpeaking || isPaused) {
                        stopSpeech()
                    }

                    binding.pdfImageView.visibility = View.VISIBLE
                    binding.pdfTextView.visibility = View.GONE
                    binding.searchPanel.visibility = View.GONE

                    showPage(page)

                    Toast.makeText(this, "Переход на страницу ${page + 1}", Toast.LENGTH_SHORT).show()
                }

                binding.searchPanel.visibility = View.VISIBLE
                binding.pdfTextView.text = currentTextContent
                binding.pdfTextView.visibility = View.VISIBLE
                binding.pdfImageView.visibility = View.GONE

                val layout = binding.pdfTextView.layout
                if (layout != null) {
                    val line = layout.getLineForOffset(position)
                    val y = layout.getLineTop(line)
                    binding.textScrollView.scrollTo(0, y)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка отображения результата", Toast.LENGTH_SHORT).show()
            }
        }

        private fun showNextSearchResult() {
            if (currentSearchIndex < searchResults.size - 1) {
                currentSearchIndex++
                showSearchResult(searchResults[currentSearchIndex], searchQuery, searchResultPages[currentSearchIndex])
                binding.btnPrevSearch.isEnabled = true
                binding.btnNextSearch.isEnabled = currentSearchIndex < searchResults.size - 1
            }
        }

        private fun showPrevSearchResult() {
            if (currentSearchIndex > 0) {
                currentSearchIndex--
                showSearchResult(searchResults[currentSearchIndex], searchQuery, searchResultPages[currentSearchIndex])
                binding.btnNextSearch.isEnabled = true
                binding.btnPrevSearch.isEnabled = currentSearchIndex > 0
            }
        }

        private fun showAboutDialog() {
            val aboutText = """
        PdfChitalka v6.5.3
        Сборка от ${getCurrentDate()}
        Полнофункциональный просмотрщик PDF
        
        Особенности:
        • Нативный просмотр PDF как в оригинале
        • Поддержка графических и текстовых PDF
        • Озвучка текстового содержимого с автопролистыванием
        • Закладки и навигация
        • Настройки отображения и голоса
        • Исправленная работа плеера
        • Улучшенный интерфейс
    """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("О программе")
                .setMessage(aboutText)
                .setPositiveButton("OK", null)
                .show()
        }

        private fun getCurrentDate(): String {
            val calendar = Calendar.getInstance()
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val month = calendar.get(Calendar.MONTH) + 1
            val year = calendar.get(Calendar.YEAR)
            return "$day.$month.$year"
        }
    }