// pdfchitalka v6.4.14

package com.example.pdfchitalka

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    private val pageBoundaries = mutableListOf<Int>()

    // TTS компоненты - ИСПРАВЛЕНИЯ ИЗ v5.3.16
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

    // Текст и позиции - ДОБАВЛЕНО ИЗ v5.3.16
    private var currentTextContent: String = ""
    private var currentSpeechPosition: Int = 0
    private var currentTextPosition: Int = 0
    private var isReadingFullBook: Boolean = false
    private var shouldAutoScroll: Boolean = true

    // Регистрация для выбора файла
    private val pickPdfFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { loadPdfFromUri(it) }
        }

    // Регистрация для сохранения файла
    private val savePdfFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
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
            Toast.makeText(
                this,
                "Для полной функциональности нужны все разрешения",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        prefs = getSharedPreferences("tts_settings", MODE_PRIVATE)
        loadTtsSettings()

        // Инициализация TTS - ИСПРАВЛЕНО ИЗ v5.3.16
        textToSpeech = TextToSpeech(this, this)

        // Настройка кнопок зума
        binding.btnZoomIn.setOnClickListener { zoomIn() }
        binding.btnZoomOut.setOnClickListener { zoomOut() }
        binding.btnResetZoom.setOnClickListener { resetZoom() }

        // Жесты для листания страниц
        binding.pdfImageView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val endY = event.y
                    val deltaY = startY - endY

                    if (abs(deltaY) > 100) {
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

    override fun onDestroy() {
        saveTtsSettings()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        // Закрываем PdfRenderer и дескриптор
        try {
            pdfRenderer?.close()
            pdfRenderer = null

            currentParcelFd?.close()
            currentParcelFd = null
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
    }

    // ИСПРАВЛЕННЫЙ МЕТОД ИНИЦИАЛИЗАЦИИ TTS ИЗ v5.3.16
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Установка русского языка - ПРАВИЛЬНАЯ РЕАЛИЗАЦИЯ ИЗ v5.3.16
            val result = textToSpeech?.setLanguage(Locale("ru", "RU"))

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Русский язык не поддерживается", Toast.LENGTH_SHORT).show()
            } else {
                isTtsInitialized = true
                setupTtsListeners()
                setupAvailableVoices()
                setVoice(ttsSettings.voice)
                runOnUiThread {
                    updatePlayerButtons()
                }
            }
        } else {
            Toast.makeText(this, "Ошибка инициализации синтезатора речи", Toast.LENGTH_SHORT).show()
            runOnUiThread {
                updatePlayerButtons()
            }
        }
    }

    private fun setupAvailableVoices() {
        // Получаем доступные голоса
        val voices = textToSpeech?.voices
        voices?.forEach { voice ->
            if (voice.locale.toString().startsWith("ru")) {
                // Предпочитаем женские голоса для русского
                if (voice.name.contains("female", ignoreCase = true) ||
                    voice.name.contains("женск", ignoreCase = true)
                ) {
                    textToSpeech?.voice = voice
                    return@forEach
                }
            }
        }
    }

    private fun setupUI() {
        binding.btnSearch.setOnClickListener {
            if (isTextPdf) {
                showSearchDialog()
            } else {
                Toast.makeText(this, "Поиск доступен только для текстовых PDF", Toast.LENGTH_SHORT)
                    .show()
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

        binding.btnRewindToStart.setOnClickListener {
            if (isSpeaking || isPaused) {
                showPage(0)
                currentSpeechPosition = getTextPositionForPage(0)
                speakFromPosition(currentSpeechPosition)
            } else {
                showPage(0)
            }
        }

        binding.btnRewindBack.setOnClickListener {
            if (isSpeaking || isPaused) {
                showPreviousPage()
                currentSpeechPosition = getTextPositionForPage(currentPage)
                speakFromPosition(currentSpeechPosition)
            } else {
                showPreviousPage()
            }
        }

        binding.btnPause.setOnClickListener {
            if (isSpeaking) {
                textToSpeech?.stop()
                isSpeaking = false
                isPaused = true
                updatePlayerButtons()
                updatePlayerVisibility()
                Toast.makeText(this, "Пауza", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStop.setOnClickListener { stopSpeech() }

        binding.btnPlay.setOnClickListener {
            if (isPaused) {
                isSpeaking = true
                isPaused = false
                speakFromPosition(currentSpeechPosition)
                updatePlayerButtons()
                updatePlayerVisibility()
            } else if (!isSpeaking) {
                startSpeech()
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

        // ДОБАВЛЕНО ИЗ v5.3.16 - кнопка копирования текста
//        binding.btnCopyText.setOnClickListener {
//            copyTextToClipboard()
//        }
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

    // ИСПРАВЛЕННЫЙ МЕТОД УСТАНОВКИ ГОЛОСА ИЗ v5.3.16
    private fun setVoice(voiceType: String) {
        if (!isTtsInitialized) return

        textToSpeech?.let { tts ->
            try {
                // Находим русский голос
                val voices = tts.voices
                var selectedVoice: android.speech.tts.Voice? = null

                // Ищем предпочтительный голос
                for (voice in voices.orEmpty()) {
                    if (voice.locale.toString().startsWith("ru")) {
                        if (voiceType == "female" &&
                            (voice.name.contains("female", ignoreCase = true) ||
                                    voice.name.contains("женск", ignoreCase = true))
                        ) {
                            selectedVoice = voice
                            break
                        } else if (voiceType == "male" &&
                            (voice.name.contains("male", ignoreCase = true) ||
                                    voice.name.contains("мужск", ignoreCase = true))
                        ) {
                            selectedVoice = voice
                            break
                        }
                    }
                }

                // Если не нашли предпочтительный, берем любой русский
                if (selectedVoice == null) {
                    selectedVoice = voices?.firstOrNull { voice ->
                        voice.locale.toString().startsWith("ru")
                    }
                }

                if (selectedVoice != null) {
                    tts.voice = selectedVoice
                    Log.d("TTS", "Голос установлен: ${selectedVoice.name}")

                    runOnUiThread {
                        Toast.makeText(this, "Голос: ${selectedVoice.name}", Toast.LENGTH_SHORT)
                            .show()
                    }
                } else {
                    // Если русских нет - используем дефолтный
                    tts.setSpeechRate(1.0f)
                    Log.e("TTS", "Русских голосов нет, использую дефолтный")
                }
            } catch (e: Exception) {
                Log.e("TTS", "Ошибка установки голоса: ${e.message}")
                textToSpeech?.setSpeechRate(1.0f)
            }
        }
    }

    private fun checkAvailableVoices() {
        textToSpeech?.voices?.forEach { voice ->
            Log.d("TTS_DEBUG", "Голос: ${voice.name}, Язык: ${voice.locale}")
        }
    }

    // ИСПРАВЛЕННЫЙ СЛУШАТЕЛЬ TTS ИЗ v5.3.16
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
                    Log.d("TTS", "Озвучка завершена: $utteranceId")

                    if (utteranceId?.startsWith("speak_") == true) {
                        val nextPos = utteranceId.removePrefix("speak_").toIntOrNull() ?: 0
                        currentTextPosition = nextPos

                        // АВТОМАТИЧЕСКОЕ ПЕРЕЛИСТЫВАНИЕ
                        if (ttsSettings.showCurrentPage) {
                            val nextPage = getPageForTextPosition(nextPos)
                            if (nextPage != currentPage) {
                                showPage(nextPage)
                            }
                        }

                        if (isReadingFullBook && nextPos < currentTextContent.length) {
                            speakFromPosition(nextPos)
                        } else if (!isReadingFullBook) {
                            when (ttsSettings.repeatMode) {
                                1 -> {
                                    // Повтор текущей страницы
                                    currentSpeechPosition = getTextPositionForPage(currentPage)
                                    speakFromPosition(currentSpeechPosition)
                                }

                                2 -> startSpeechFullBook(true) // Повтор всей книги
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
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка озвучки: $errorMsg",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // ДОБАВЛЕНО ИЗ v5.3.16 - отслеживание позиции
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                runOnUiThread {
                    currentSpeechPosition = start
                    // Автолистание - прокрутка текста к текущей позиции
                    if (binding.pdfTextView.visibility == View.VISIBLE) {
                        val layout = binding.pdfTextView.layout
                        if (layout != null) {
                            val line = layout.getLineForOffset(start)
                            val y = layout.getLineTop(line)
                            binding.textScrollView.scrollTo(0, y)
                        }
                    }
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

                // Сохраняем границы страниц
                pageBoundaries.clear()
                var totalChars = 0

                for (page in 0 until document.numberOfPages) {
                    textStripper.startPage = page + 1
                    textStripper.endPage = page + 1
                    val pageText = textStripper.getText(document)
                    totalChars += pageText.length
                    pageBoundaries.add(totalChars)
                }

                val fullText = textStripper.getText(document)
                document.close()

                runOnUiThread {
                    currentTextContent = fullText
                    isTextPdf = currentTextContent.length > 100

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

            currentParcelFd =
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
                    val bitmap =
                        Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    binding.pdfImageView.setImageBitmap(bitmap)
                    binding.pdfImageView.visibility = View.VISIBLE
                    binding.pdfTextView.visibility = View.GONE

                    currentPage = pageIndex
                    page.close()

                    updatePageIndicator()
                    scaleToFit()

                    // Обновляем позицию озвучки если она активна
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
        binding.pageIndicator.text =
            resources.getString(R.string.page_indicator, currentPage + 1, totalPages)

        if (totalPages > 1) {
            binding.pageIndicator.visibility = View.VISIBLE
            binding.btnPrevPage.visibility = View.VISIBLE
            binding.btnNextPage.visibility = View.VISIBLE
            binding.btnFirstPage.visibility = View.VISIBLE
            binding.btnLastPage.visibility = View.VISIBLE
            binding.btnPrevPage.isEnabled = currentPage > 0
            binding.btnNextPage.isEnabled = currentPage < totalPages - 1
            binding.btnFirstPage.isEnabled = currentPage > 0
            binding.btnLastPage.isEnabled = currentPage < totalPages - 1
        } else {
            binding.pageIndicator.visibility = View.GONE
            binding.btnPrevPage.visibility = View.GONE
            binding.btnNextPage.visibility = View.GONE
            binding.btnFirstPage.visibility = View.GONE
            binding.btnLastPage.visibility = View.GONE
        }
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

    // ИСПРАВЛЕННЫЙ МЕТОД ОЗВУЧКИ ИЗ v5.3.16
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

            // ИСПРАВЛЕНИЕ: Установка правильных параметров речи
            textToSpeech?.setSpeechRate(0.9f) // Немного медленнее для лучшего восприятия
            textToSpeech?.setPitch(1.0f)

            textToSpeech?.speak(chunk, TextToSpeech.QUEUE_FLUSH, params, "speak_$nextPosition")
            Log.d("TTS", "Озвучка начата с позиции $startPos, длина: ${chunk.length}")
        } catch (e: Exception) {
            Log.e("TTS", "Ошибка запуска озвучки: ${e.message}")
            stopSpeech()
        }
    }

    // ИСПРАВЛЕННЫЙ МЕТОД ЗАПУСКА ОЗВУЧКИ ИЗ v5.3.16
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

    // ИСПРАВЛЕННЫЙ МЕТОД ОСТАНОВКИ ОЗВУЧКИ ИЗ v5.3.16
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
        binding.btnPause.isEnabled = isSpeaking
        binding.btnStop.isEnabled = isSpeaking || isPaused
        binding.btnPlay.isEnabled = !isSpeaking || isPaused

        if (isSpeaking) {
            binding.btnPause.setImageResource(android.R.drawable.ic_media_pause)
        } else if (isPaused) {
            binding.btnPause.setImageResource(android.R.drawable.ic_media_play)
        }
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

    private fun showSpeechOptionsDialog() {
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
                val newSettings = ttsSettings.copy(
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
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
                if (wasSpeaking || wasPaused) {
                    shouldShowPlayer = true
                    updatePlayerVisibility()
                }
            }
            .setOnDismissListener {
                if (wasSpeaking || wasPaused) {
                    shouldShowPlayer = true
                    updatePlayerVisibility()
                }

            }
            .show()
    }
        // на всякий случай
        private fun checkTtsStatus() {
            val currentLanguage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                textToSpeech?.voice?.locale?.displayLanguage ?: "Не определен"
            } else {
                @Suppress("DEPRECATION")
                textToSpeech?.language?.displayLanguage ?: "Не определен"
            }

            val status = """
                        Статус TTS: ${if (isTtsInitialized) "Готов" else "Не готов"}
                        Язык: $currentLanguage
                        Скорость: ${ttsSettings.speed}x
                        Режим: ${if (isReadingFullBook) "Всю книгу" else "Текущую страницу"}
                    """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Статус TTS")
                .setMessage(status)
                .setPositiveButton("OK", null)
                .show()
        }

        private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentScale *= detector.scaleFactor
                currentScale = max(0.5f, min(currentScale, 5.0f))
                binding.pdfImageView.scaleX = currentScale
                binding.pdfImageView.scaleY = currentScale
                return true
            }
        }

        private fun zoomIn() {
            currentScale = min(currentScale * 1.2f, 5.0f)
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
            Log.d("ZOOM", "Увеличили: $currentScale")
        }

        private fun zoomOut() {
            currentScale = max(currentScale * 0.8f, 0.5f)
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
            Log.d("ZOOM", "Уменьшили: $currentScale")
        }

        private fun resetZoom() {
            currentScale = 1.0f
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
            scaleToFit()
            Log.d("ZOOM", "Сбросили зум")
        }

        private fun scaleToFit() {
            binding.pdfImageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            binding.pdfImageView.translationX = 0f
            binding.pdfImageView.translationY = 0f
            binding.pdfImageView.scaleX = 1.0f
            binding.pdfImageView.scaleY = 1.0f
            currentScale = 1.0f
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
                permissions.addAll(
                    listOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
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

            popup.menu.findItem(R.id.menu_save).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_share).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_speak).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_stop).isEnabled = isSpeaking || isPaused
            popup.menu.findItem(R.id.menu_bookmark).isEnabled = hasPdfLoaded
            popup.menu.findItem(R.id.menu_show_bookmarks).isEnabled = bookmarks.isNotEmpty()
            popup.menu.findItem(R.id.menu_search).isEnabled =
                isTextPdf && currentTextContent.isNotEmpty()
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

                    R.id.menu_tts_settings -> {
                        showTtsSettingsDialog()
                        true
                    }

                    R.id.menu_goto_page -> {
                        showGotoPageDialog()
                        true
                    }

                    R.id.menu_about -> {
                        showAboutDialog()
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
                        controller.systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

        // ДОБАВЛЕННЫЙ МЕТОД ИЗ v5.3.16 - копирование текста в буфер обмена
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

        // ИСПРАВЛЕННЫЙ МЕТОД ПОИСКА
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

                    // ИСПРАВЛЕНИЕ: Правильный поиск без игнорирования регистра
                    var index = currentTextContent.indexOf(query)

                    while (index >= 0) {
                        results.add(index)
                        val page = findPageForTextPosition(index)
                        pages.add(page)
                        index = currentTextContent.indexOf(query, index + 1)
                    }

                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE

                        if (results.isNotEmpty()) {
                            searchResults = results
                            searchResultPages = pages
                            currentSearchIndex = 0
                            showSearchResult(results[0], query, pages[0])
                            Toast.makeText(
                                this,
                                "Найдено ${results.size} совпадений",
                                Toast.LENGTH_SHORT
                            ).show()
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

            // Точное определение страницы по позиции текста
            try {
                PDDocument.load(currentPdfFile).use { document ->
                    var totalChars = 0

                    for (page in 0 until document.numberOfPages) {
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
                Log.e("Search", "Ошибка определения страницы для поиска", e)
            }

            // Резервный метод если точный не сработал
            val approxPage =
                (position * totalPages / currentTextContent.length).coerceIn(0, totalPages - 1)
            return approxPage
        }

        private fun showSearchResult(position: Int, query: String, page: Int) {
            try {
                val start = max(0, position - 30)
                val end = min(currentTextContent.length, position + query.length + 30)
                val context = currentTextContent.substring(start, end)

                // Подсветка найденного текста
                val highlighted = context.replace(query, "►$query◄")

                binding.searchResultText.text = highlighted
                binding.searchPosition.text = resources.getString(
                    R.string.search_position,
                    currentSearchIndex + 1,
                    searchResults.size
                )
                binding.searchPageNumber.text = resources.getString(R.string.search_page, page + 1)

                binding.searchPageNumber.setOnClickListener {
                    if (isSpeaking || isPaused) {
                        stopSpeech()
                    }

                    binding.pdfImageView.visibility = View.VISIBLE
                    binding.pdfTextView.visibility = View.GONE
                    binding.searchPanel.visibility = View.GONE

                    showPage(page)

                    Toast.makeText(this, "Переход на страницу ${page + 1}", Toast.LENGTH_SHORT)
                        .show()
                }

                binding.searchPanel.visibility = View.VISIBLE
                binding.pdfTextView.text = currentTextContent
                binding.pdfTextView.visibility = View.VISIBLE
                binding.pdfImageView.visibility = View.GONE

                // Прокрутка к найденному тексту
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
                showSearchResult(
                    searchResults[currentSearchIndex],
                    searchQuery,
                    searchResultPages[currentSearchIndex]
                )
                binding.btnPrevSearch.isEnabled = true
                binding.btnNextSearch.isEnabled = currentSearchIndex < searchResults.size - 1
            }
        }

        private fun showPrevSearchResult() {
            if (currentSearchIndex > 0) {
                currentSearchIndex--
                showSearchResult(
                    searchResults[currentSearchIndex],
                    searchQuery,
                    searchResultPages[currentSearchIndex]
                )
                binding.btnNextSearch.isEnabled = true
                binding.btnPrevSearch.isEnabled = currentSearchIndex > 0
            }
        }

        private fun showGotoPageDialog() {
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
                    } else {
                        Toast.makeText(this, "Неверный номер страницы", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        private fun showAboutDialog() {
            val aboutText = """
                        PdfChitalka v6.4.14
                        Сборка от ${getCurrentDate()}
                        Полнофункциональный просмотрщик PDF
                        
                        Особенности:
                        • Нативный просмотр PDF
                        • Озвучка текста с автопролистыванием
                        • Закладки и навигация
                        • Настройки голоса и скорости
                        • Исправленная работа озвучки
                        • Исправленный поиск
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
