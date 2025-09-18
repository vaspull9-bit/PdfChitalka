package com.example.pdfchitalka

import android.annotation.SuppressLint
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


import android.graphics.pdf.PdfRenderer  // ← нативный PDF рендерер
import android.os.ParcelFileDescriptor

//


import com.tom_roush.pdfbox.android.PDFBoxResourceLoader  // Добавь этот импорт
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import android.view.ScaleGestureDetector
import kotlin.math.max
import kotlin.math.min
import android.view.MotionEvent
import kotlin.math.abs
import android.widget.EditText


import com.google.gson.Gson



// Data class для закладок
data class Bookmark(
    val position: Int,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

// Настройки TTS
// Обновляем TtsSettings
data class TtsSettings(
    val showCurrentPage: Boolean = true,
    val speed: Float = 1.0f,
    val repeatMode: Int = 0,
    val keepScreenOn: Boolean = false,
    val voice: String = "female" // Добавляем голос
)

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var startY: Float = 0f  // ← ДОБАВЬ ЭТУ ПЕРЕМЕННУЮ
    private lateinit var binding: ActivityMainBinding

    private lateinit var prefs: SharedPreferences
    private var currentPdfUri: Uri? = null
    private var currentPdfFile: File? = null
    private var bookmarks: MutableList<Bookmark> = mutableListOf()
    private var isFullscreen: Boolean = false

    // PDF данные
    private var pdfRenderer: PdfRenderer? = null
    private var isTextPdf: Boolean = false
    private var textParts: List<String> = emptyList()
    private var currentPage: Int = 0
    private var totalPages: Int = 0

    // TTS компоненты
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized: Boolean = false
    private var isSpeaking: Boolean = false
    private var isPaused: Boolean = false
    private var ttsSettings: TtsSettings = TtsSettings()

    // Для поиска
    private var searchQuery: String = ""
    private var searchResults: List<Int> = emptyList()
    private var currentSearchIndex: Int = 0
    private var currentScale: Float = 1.0f
    private val scaleDetector: ScaleGestureDetector by lazy {
        ScaleGestureDetector(this, ScaleListener())
    }

    // Добавляем в класс MainActivity
    private var currentTextContent: String = ""

    private var currentWords: List<String> = emptyList()

    private var currentSpeechPosition: Int = 0
    private var isReadingFullBook: Boolean = false

    private var isSpeakingFromCurrentPage: Boolean = false
    private var shouldShowGraphicDuringTts: Boolean = true // ПО УМОЛЧАНИЮ ГРАФИКА!

    // Добавить в класс переменную
    private var shouldAutoScroll: Boolean = true

    // Добавить в начало класса
    private var ttsVoice: String = "female"
    private val availableVoices = mutableListOf<String>()
    // Добавить в класс
    private var searchResultPages: List<Int> = emptyList()

    // Добавить в класс переменную


    //////////////////////////////////////////////////////
    //
    //          Начало функций
    //
    ///////////////////////////////////////////////////////




    // ОБНОВЛЯЕМ prepareTextForTts
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

                    if (isTextPdf) {
                        Toast.makeText(this, "Текст готов для озвучки", Toast.LENGTH_SHORT).show()

                        // ВАЖНО: Убираем прогресс-бар после загрузки
                        binding.progressBar.visibility = View.GONE

                        binding.pdfImageView.visibility = View.VISIBLE
                        binding.pdfTextView.visibility = View.GONE
                    } else {
                        // Убираем прогресс-бар даже если текст не подходит
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // Всегда убираем прогресс-бар при ошибке
                    binding.progressBar.visibility = View.GONE
                    Log.e("TTS", "Ошибка извлечения текста: ${e.message}")
                }
            }
        }.start()
    }







    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            currentScale *= detector.scaleFactor
            currentScale = max(0.5f, min(currentScale, 5.0f))
            // ЗАМЕНИ applyZoom() НА:
            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
            return true
        }
    }

    private fun zoomIn() {
        currentScale = min(currentScale * 1.2f, 5.0f)
        // ЗАМЕНИ applyZoom() НА:
        binding.pdfImageView.scaleX = currentScale
        binding.pdfImageView.scaleY = currentScale
    }

    private fun zoomOut() {
        currentScale = max(currentScale * 0.8f, 0.5f)
        // ЗАМЕНИ applyZoom() НА:
        binding.pdfImageView.scaleX = currentScale
        binding.pdfImageView.scaleY = currentScale
    }

    private fun resetZoom() {
        currentScale = 1.0f
        // ЗАМЕНИ applyZoom() НА:
        binding.pdfImageView.scaleX = currentScale
        binding.pdfImageView.scaleY = currentScale
        // И вызови scaleToFit() для возврата к нормальному масштабу
        scaleToFit()
    }




    // Добавить эти переменные в начало класса (после других переменных)
    private var isTextModeActive: Boolean = false
    private var lastSpeechPosition: Int = 0

    // ИСПРАВЛЯЕМ функцию showPage - убираем остановку озвучки
    private fun showPage(pageIndex: Int) {
        try {
            // ПРЕРЫВАЕМ ОЗВУЧКУ ТОЛЬКО ПРИ РУЧНОМ ПЕРЕЛИСТЫВАНИИ
            if (isSpeaking || isPaused) {
                textToSpeech?.stop()
                isSpeaking = false
                isPaused = false
                // НЕ СКРЫВАЕМ ПЛЕЕР - только останавливаем озвучку
                updatePlayerButtons()
            }

            pdfRenderer?.let { renderer ->
                if (pageIndex in 0 until renderer.pageCount) {
                    val page = renderer.openPage(pageIndex)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    binding.pdfImageView.setImageBitmap(bitmap)
                    currentPage = pageIndex
                    page.close()

                    updatePageIndicator()
                    scaleToFit()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка отображения страницы", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scaleToFit() {
        binding.pdfImageView.scaleType = android.widget.ImageView.ScaleType.FIT_XY
        binding.pdfImageView.translationX = 0f
        binding.pdfImageView.translationY = 0f
    }


    private fun loadPdfForViewing(pdfFile: File) {
        try {
            binding.pdfImageView.visibility = View.VISIBLE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val parcelFileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(parcelFileDescriptor)
                totalPages = pdfRenderer!!.pageCount

                showPage(0)
                updatePageIndicator()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
        }
    }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация PDFBox
        PDFBoxResourceLoader.init(applicationContext)

        prefs = getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        loadTtsSettings()

        // Инициализация TTS
        textToSpeech = TextToSpeech(this, this)

        // Настройка кнопок зума
        binding.btnZoomIn.setOnClickListener { zoomIn() }
        binding.btnZoomOut.setOnClickListener { zoomOut() }
        binding.btnResetZoom.setOnClickListener { resetZoom() }

        // Жесты для листания страниц

        // ЗАМЕНИТЕ полностью OnTouchListener для жестов:
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
                            // ЖЕСТЫ РАБОТАЮТ КАК ВЕРХНИЕ СТРЕЛКИ - не прерываем озвучку!
                            if (deltaY > 0) {
                                // Свайп вниз - следующая страница
                                showNextPage()
                                currentSpeechPosition = getTextPositionForPage(currentPage)
                                speakFromPosition(currentSpeechPosition)
                            } else {
                                // Свайп вверх - предыдущая страница
                                showPreviousPage()
                                currentSpeechPosition = getTextPositionForPage(currentPage)
                                speakFromPosition(currentSpeechPosition)
                            }
                        } else {
                            // Если озвучка выключена - просто листаем
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

        // Закрываем PdfRenderer
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                pdfRenderer?.close()
                pdfRenderer = null
            }
        } catch (e: Exception) {
            Log.e("PDF", "Error closing PdfRenderer", e)
        }

        // Удаляем временный файл
        currentPdfFile?.delete()

        super.onDestroy()
    }



    // Функция загрузки настроек
    // ОБНОВЛЯЕМ loadTtsSettings
    private fun loadTtsSettings() {
        ttsSettings = TtsSettings(
            showCurrentPage = prefs.getBoolean("show_current_page", true),
            speed = prefs.getFloat("speed", 1.0f),
            repeatMode = prefs.getInt("repeat_mode", 0),
            keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        )
    }



    // Функция сохранения настроек
    private fun saveTtsSettings() {
        prefs.edit().apply {
            putBoolean("show_current_page", ttsSettings.showCurrentPage)
            putFloat("speed", ttsSettings.speed)
            putInt("repeat_mode", ttsSettings.repeatMode)
            // putBoolean("dark_mode", ttsSettings.darkMode)
            putBoolean("keep_screen_on", ttsSettings.keepScreenOn)
            apply()
        }
    }

    // ОБНОВЛЯЕМ applyTtsSettings
    private fun applyTtsSettings() {
        // Настройка невыключения экрана
        if (ttsSettings.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Настройка скорости речи
        textToSpeech?.setSpeechRate(ttsSettings.speed)

        // Немедленная настройка голоса БЕЗ перезапуска озвучки
        setVoice(ttsSettings.voice)

        // УБИРАЕМ перезапуск озвучки - голос меняется на лету
        // if (isSpeaking || isPaused) {
        //     val currentPos = currentSpeechPosition
        //     stopSpeech()
        //     currentSpeechPosition = currentPos
        //     speakFromPosition(currentPos)
        // }

        if (ttsSettings.showCurrentPage) {
            updatePageIndicator()
        }
    }


    // ОБНОВЛЯЕМ функцию setVoice
    private fun setVoice(voiceType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            textToSpeech?.let { tts ->
                // Пробуем найти подходящий голос
                tts.voices?.firstOrNull { voice ->
                    val isRussian = voice.locale.toString().contains("ru", true)
                    when (voiceType) {
                        "male" -> isRussian && (voice.name.contains("male", true) ||
                                voice.name.contains("low", true) ||
                                voice.name.contains("deep", true))
                        else -> isRussian && !voice.name.contains("male", true)
                    }
                }?.let {
                    tts.voice = it
                    return
                }

                // Если не нашли, используем любой русский
                tts.voices?.firstOrNull { it.locale.toString().contains("ru", true) }?.let {
                    tts.voice = it
                }
            }
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languagesToTry = listOf(
                Locale("ru", "RU"),
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
                    // ДОБАВЛЯЕМ ВЫЗОВ НОВОГО ЛИСТЕНЕРА ↓
                    setupTtsListeners()  // ← Вот это единственное добавление!
                    break
                }
            }

            if (success) {
                setupTtsListeners()
            } else {
                Toast.makeText(this, "Нет подходящего языка TTS", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Ошибка инициализации TTS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()

        runOnUiThread {
            // Восстанавливаем правильное отображение
            if (isSpeaking || isPaused) {
                // Если озвучка активна - показываем ТЕКСТ
                if (currentTextContent.isNotEmpty()) {
                    binding.pdfTextView.text = currentTextContent
                    binding.pdfTextView.visibility = View.VISIBLE
                    binding.pdfImageView.visibility = View.GONE
                }
            } else {
                // Если озвучка выключена - показываем ГРАФИКУ
                binding.pdfImageView.visibility = View.VISIBLE
                binding.pdfTextView.visibility = View.GONE
            }

            binding.pdfImageView.scaleX = currentScale
            binding.pdfImageView.scaleY = currentScale
        }
    }

    // Обновляем слушатель TTS для автоматического пролистывания
    private fun setupTtsListeners() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread {
                    isSpeaking = true
                    updatePlayerButtons()

                    // Автопролистывание при начале озвучки
                    if (utteranceId?.startsWith("speak_") == true) {
                        val pos = utteranceId.removePrefix("speak_").toIntOrNull() ?: 0
                        autoScrollToTextPosition(pos)
                    }
                }
            }

            // В onDone слушателе ВОССТАНАВЛИВАЕМ рабочее повторение
            override fun onDone(utteranceId: String?) {
                runOnUiThread {
                    if (utteranceId?.startsWith("speak_") == true && isSpeaking) {
                        val nextPos = utteranceId.removePrefix("speak_").toIntOrNull() ?: 0

                        if (isReadingFullBook && nextPos < currentTextContent.length) {
                            // Автопролистывание для чтения всей книги
                            autoScrollToTextPosition(nextPos)
                            currentSpeechPosition = nextPos
                            speakFromPosition(nextPos)
                        } else if (!isReadingFullBook) {
                            // РАБОЧЕЕ ПОВТОРЕНИЕ СТРАНИЦЫ
                            when (ttsSettings.repeatMode) {
                                1 -> {
                                    // Повторять страницу - начинаем с начала ТЕКУЩЕЙ страницы
                                    currentSpeechPosition = getTextPositionForPage(currentPage)
                                    speakFromPosition(currentSpeechPosition)
                                }
                                2 -> {
                                    // Повторять всё - начинаем с начала книги
                                    currentSpeechPosition = 0
                                    speakFromPosition(0)
                                }
                                else -> stopSpeech()
                            }
                        } else {
                            stopSpeech()
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    stopSpeech()
                    Toast.makeText(this@MainActivity, "Ошибка озвучки", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread {
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

    private fun scrollToTextPosition(position: Int) {
        val layout = binding.pdfTextView.layout
        if (layout != null && position < currentTextContent.length) {
            val line = layout.getLineForOffset(position)
            val y = layout.getLineTop(line)
            binding.textScrollView.smoothScrollTo(0, y)
        }
    }



    private fun setupBackPressedHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    isFullscreen -> toggleFullscreen()
                    isSpeaking || isPaused -> stopSpeech()
                    binding.playerPanel.isVisible -> hidePlayerPanel()
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
    private fun setupUI() {
        binding.btnSearch.setOnClickListener {
            if (isTextPdf) {
                showSearchDialog()
            } else {
                Toast.makeText(this, "Поиск доступен только для текстовых PDF", Toast.LENGTH_SHORT).show()
            }
        }

        // УБИРАЕМ НЕСУЩЕСТВУЮЩИЕ ФУНКЦИИ


        // Добавляем обработчики для кнопок поиска
        binding.btnNextSearch.setOnClickListener { showNextSearchResult() }
        binding.btnPrevSearch.setOnClickListener { showPrevSearchResult() }
        binding.btnCloseSearch.setOnClickListener {
        binding.searchPanel.visibility = View.GONE
        binding.pdfTextView.visibility = View.GONE
        binding.pdfImageView.visibility = View.VISIBLE
        }

        binding.btnMenu.setOnClickListener { showPopupMenu(it) }
        // В setupUI() ВОССТАНАВЛИВАЕМ правильные обработчики
        binding.btnPrevPage.setOnClickListener {
            if (isSpeaking || isPaused) {
                // ПРЕРЫВАЕМ озвучку при ручном перелистывании
                stopSpeech()
                showPreviousPage()
                // ЗАПУСКАЕМ заново с новой страницы
                speakPage()
            } else {
                showPreviousPage()
            }
        }
        binding.btnNextPage.setOnClickListener {
            if (isSpeaking || isPaused) {
                // ПРЕРЫВАЕМ озвучку при ручном перелистывании
                stopSpeech()
                showNextPage()
                // ЗАПУСКАЕМ заново с новой страницы
                speakPage()
            } else {
                showNextPage()
            }
        }

        // Кнопки плеера
        // Кнопка "В начало файла" (стрелка влево с палочкой)
        binding.btnRewindToStart.setOnClickListener {
            if (isSpeaking || isPaused) {
                // Переходим на первую страницу и начинаем озвучку
                showPage(0)
                currentSpeechPosition = getTextPositionForPage(0)
                speakFromPosition(currentSpeechPosition)
            } else {
                showPage(0)
            }
        }
        // Кнопка "Страница назад" (стрелка влево)
        binding.btnRewindBack.setOnClickListener {
            if (isSpeaking || isPaused) {
                // Переходим на предыдущую страницу и продолжаем озвучку
                showPreviousPage()
                currentSpeechPosition = getTextPositionForPage(currentPage)
                speakFromPosition(currentSpeechPosition)
            } else {
                showPreviousPage()
            }
        }
        // Кнопка "Пауза" (две палки)
        binding.btnPause.setOnClickListener {
            if (isSpeaking) {
                textToSpeech?.stop()
                isSpeaking = false
                isPaused = true
                updatePlayerButtons()
                Toast.makeText(this, "Пауза", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnStop.setOnClickListener { stopSpeech() }
        // Кнопка "Продолжить" (стрелка вправо) - переименуйте в коде если нужно
        binding.btnPlay.setOnClickListener {
            if (isPaused) {
                // Продолжаем с текущей позиции
                isSpeaking = true
                isPaused = false
                speakFromPosition(currentSpeechPosition)
                updatePlayerButtons()
                Toast.makeText(this, "Продолжение", Toast.LENGTH_SHORT).show()
            } else if (!isSpeaking) {
                // Начинаем с текущей страницы
                startSpeechFullBook(true)
            }
        }
        // Кнопка "Страница вперед" (стрелка вправо)
        binding.btnRewindForward.setOnClickListener {
            if (isSpeaking || isPaused) {
                // Переходим на следующую страницу и продолжаем озвучку
                showNextPage()
                currentSpeechPosition = getTextPositionForPage(currentPage)
                speakFromPosition(currentSpeechPosition)
            } else {
                showNextPage()
            }
        }
        // Кнопка "В конец файла" (стрелка вправо с палочкой)
        binding.btnRewindToEnd.setOnClickListener {
            if (isSpeaking || isPaused) {
                // Переходим на последнюю страницу и начинаем озвучку
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

    // Добавьте новую функцию для возобновления:
    private fun resumeSpeech() {
        if (isPaused) {
            isSpeaking = true
            isPaused = false
            speakFromPosition(lastSpeechPosition)
            updatePlayerButtons()
        }
    }

    // ОБНОВЛЯЕМ showPopupMenu
    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        val hasPdfLoaded = currentPdfFile != null

        popup.menu.findItem(R.id.menu_save).isEnabled = hasPdfLoaded
        popup.menu.findItem(R.id.menu_share).isEnabled = hasPdfLoaded
        popup.menu.findItem(R.id.menu_speak).isEnabled = hasPdfLoaded
        popup.menu.findItem(R.id.menu_stop).isEnabled = isSpeaking || isPaused
        popup.menu.findItem(R.id.menu_bookmark).isEnabled = hasPdfLoaded // Всегда true если файл загружен
        popup.menu.findItem(R.id.menu_show_bookmarks).isEnabled = bookmarks.isNotEmpty() // Активна если есть закладки
        popup.menu.findItem(R.id.menu_search).isEnabled = isTextPdf && currentTextContent.isNotEmpty()
        popup.menu.findItem(R.id.menu_tts_settings).isEnabled = true

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
                R.id.menu_tts_settings -> {  // Новая кнопка настроек озвучки
                    showTtsSettingsDialog()
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

    // ОБНОВЛЯЕМ loadPdfFromUri
    private fun loadPdfFromUri(uri: Uri) {
        // Останавливаем текущую озвучку
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
                        loadBookmarks() // Загружаем закладки для этого файла
                    }

                } catch (e: Exception) {
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




    private fun extractTextForTts(pdfFile: File) {
        Thread {
            try {
                val document = PDDocument.load(pdfFile)
                val textStripper = PDFTextStripper().apply {
                    lineSeparator = "\n\n"
                    wordSeparator = " "
                }

                val fullText = textStripper.getText(document)
                val cleanText = fullText.replace("\\s+".toRegex(), "").trim()

                runOnUiThread {
                    isTextPdf = cleanText.length > 100
                    if (isTextPdf) {
                        textParts = splitTextIntoParts(fullText, 1500)
                        Toast.makeText(this, "Текст готов для озвучки", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Мало текста для озвучки", Toast.LENGTH_SHORT).show()
                    }
                    binding.progressBar.visibility = View.GONE
                }
                document.close()
            } catch (e: Exception) {
                runOnUiThread {
                    isTextPdf = false
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Ошибка извлечения текста", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun splitTextIntoParts(text: String, maxLength: Int): List<String> {
        val parts = mutableListOf<String>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val endIndex = minOf(currentIndex + maxLength, text.length)
            var part = text.substring(currentIndex, endIndex)

            val lastSentenceEnd = findLastSentenceEnd(part)
            if (lastSentenceEnd > 0 && endIndex < text.length && lastSentenceEnd != part.length) {
                part = text.substring(currentIndex, currentIndex + lastSentenceEnd)
                currentIndex += lastSentenceEnd
            } else {
                currentIndex = endIndex
            }

            parts.add(part)
        }

        return parts
    }

    private fun findLastSentenceEnd(text: String): Int {
        val sentenceEnders = listOf('.', '!', '?', '\n')
        for (i in text.length - 1 downTo 0) {
            if (sentenceEnders.contains(text[i]) && !isDecimalPoint(text, i)) {
                return i + 1
            }
        }
        for (i in text.length - 1 downTo 0) {
            if (text[i].isWhitespace()) {
                return i + 1
            }
        }
        return text.length
    }

    private fun isDecimalPoint(text: String, index: Int): Boolean {
        return text[index] == '.' &&
                index > 0 && index < text.length - 1 &&
                text[index - 1].isDigit() &&
                text[index + 1].isDigit()
    }


    // ПЕРЕПИСЫВАЕМ функции запуска озвучки
    private fun startSpeechFullBook(fromCurrentPage: Boolean = false) {
        if (!isTextPdf) {
            Toast.makeText(this, "Текст не готов для озвучки", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentTextContent.isEmpty()) {
            Toast.makeText(this, "Текст пустой", Toast.LENGTH_SHORT).show()
            return
        }

        isReadingFullBook = true
        isSpeaking = true
        isPaused = false

        // НЕ ПЕРЕКЛЮЧАЕМ НА ТЕКСТОВЫЙ VIEW! Оставляем графический вид
        // binding.pdfTextView.visibility = View.GONE
        // binding.pdfImageView.visibility = View.VISIBLE

        // Определяем начальную позицию
        currentSpeechPosition = if (fromCurrentPage) {
            getTextPositionForPage(currentPage)
        } else {
            0
        }

        showPlayerPanel()
        speakFromPosition(currentSpeechPosition)

        val message = if (fromCurrentPage) {
            "Озвучка начата с текущей страницы"
        } else {
            "Озвучка начата с начала книги"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun speakPage() {
        if (!isTextPdf) {
            Toast.makeText(this, "Текст не готов для озвучки", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentTextContent.isEmpty()) {
            Toast.makeText(this, "Текст пустой", Toast.LENGTH_SHORT).show()
            return
        }

        isReadingFullBook = false
        isSpeaking = true
        isPaused = false

        // НЕ ПЕРЕКЛЮЧАЕМ НА ТЕКСТОВЫЙ VIEW! Оставляем графический вид
        // binding.pdfTextView.visibility = View.GONE
        // binding.pdfImageView.visibility = View.VISIBLE

        // Начинаем с начала текущей страницы
        currentSpeechPosition = getTextPositionForPage(currentPage)
        lastSpeechPosition = currentSpeechPosition

        showPlayerPanel()
        speakFromPosition(currentSpeechPosition)

        Toast.makeText(this, "Озвучка начата с начала страницы", Toast.LENGTH_SHORT).show()
    }



    // ПРОСТАЯ И РАБОЧАЯ функция из 5.3.16
    private fun getTextPositionForPage(page: Int): Int {
        // Простое вычисление по пропорции (РАБОТАЛО в 5.3.16)
        if (totalPages == 0 || currentTextContent.isEmpty()) return 0
        val approxPageLength = currentTextContent.length / totalPages
        return (page * approxPageLength).coerceAtMost(currentTextContent.length - 1)
    }

    private fun getPageStartPosition(page: Int): Int {
        // Для текстовых PDF делим текст по страницам пропорционально
        if (textParts.isNotEmpty() && page < textParts.size) {
            var position = 0
            for (i in 0 until page) {
                position += textParts[i].length
            }
            return position
        }

        // Для графических PDF приблизительно
        val approxPageLength = currentTextContent.length / max(1, totalPages)
        return min(page * approxPageLength, currentTextContent.length - 1)
    }

    // ОБНОВЛЯЕМ speakFromPosition
    private fun speakFromPosition(startPos: Int) {
        if (startPos >= currentTextContent.length) {
            stopSpeech()
            return
        }

        // ВАЖНО: Убеждаемся что текстовый view видим перед озвучкой
        if (binding.pdfTextView.visibility != View.VISIBLE) {
            binding.pdfTextView.visibility = View.VISIBLE
            binding.pdfImageView.visibility = View.GONE
        }

        val textToRead = currentTextContent.substring(startPos)
        val maxChunkSize = 4000
        val chunk = if (textToRead.length > maxChunkSize) {
            textToRead.substring(0, maxChunkSize)
        } else {
            textToRead
        }

        val nextPosition = startPos + chunk.length

        // Автопролистывание перед началом озвучки
        if (ttsSettings.showCurrentPage) {
            autoScrollToTextPosition(startPos)
        }

        textToSpeech?.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "speak_$nextPosition")
    }

    private fun getVisibleTextStartPosition(): Int {
        if (!isTextPdf) return 0

        val scrollY = binding.textScrollView.scrollY
        val layout = binding.pdfTextView.layout
        return if (layout != null) {
            val line = layout.getLineForVertical(scrollY)
            layout.getLineStart(line)
        } else {
            getPageStartPosition(currentPage) // Начинаем с начала страницы
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



    // ПРОСТОЙ И РАБОЧИЙ автопролистывание из 5.3.16
    private fun autoScrollToTextPosition(position: Int) {
//        if (!ttsSettings.showCurrentPage) return
//
//        runOnUiThread {
//            try {
//                // ВАЖНО: Принудительно обновляем текст и убеждаемся что он видим
//                if (binding.pdfTextView.visibility != View.VISIBLE) {
//                    binding.pdfTextView.text = currentTextContent
//                    binding.pdfTextView.visibility = View.VISIBLE
//                    binding.pdfImageView.visibility = View.GONE
//                }
//
//                if (position < currentTextContent.length) {
//                    binding.textScrollView.scrollTo(0, position)
//                }
//            } catch (e: Exception) {
//                Log.e("AutoScroll", "Ошибка скролла: ${e.message}")
//            }
//        }
    }

    // ЗАМЕНИТЕ функцию stopSpeech:
    private fun stopSpeech() {
        textToSpeech?.stop()
        isSpeaking = false
        isPaused = false
        lastSpeechPosition = currentSpeechPosition

        // ВАЖНО: Плавно возвращаем графический вид
        runOnUiThread {
            binding.pdfImageView.visibility = View.VISIBLE
            binding.pdfTextView.visibility = View.GONE
            updatePlayerButtons()
            hidePlayerPanel()
        }

        Toast.makeText(this, "Озвучка остановлена", Toast.LENGTH_SHORT).show()
    }

    private fun togglePause() {
        if (isSpeaking) {
            textToSpeech?.stop()
            isPaused = true
        } else if (isPaused) {
            speakPage()
        }
        updatePlayerButtons()
    }

    private fun updatePlayerButtons() {
        binding.btnPause.isEnabled = isSpeaking
        binding.btnStop.isEnabled = isSpeaking || isPaused
        binding.btnPlay.isEnabled = !isSpeaking || isPaused
    }

    private fun showPlayerPanel() {
        binding.playerPanel.visibility = View.VISIBLE
        updatePlayerButtons()
    }

    private fun hidePlayerPanel() {
        binding.playerPanel.visibility = View.GONE
    }

    private fun rewindToStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            showPage(0)
        }
        if (isSpeaking || isPaused) {
            speakPage()
        }
    }


    private fun rewindBack() {
        if (currentPage > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showPage(currentPage - 1)
            }
            if (isSpeaking || isPaused) {
                speakPage()
            }
        }
    }


    private fun rewindForward() {
        if (currentPage < totalPages - 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showPage(currentPage + 1)
            }
            if (isSpeaking || isPaused) {
                speakPage()
            }
        }
    }

    private fun rewindToEnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            showPage(totalPages - 1)
        }
        if (isSpeaking || isPaused) {
            speakPage()
        }
    }


    private fun showPreviousPage() {
        if (currentPage > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showPage(currentPage - 1)
            }
        }
    }



    private fun showNextPage() {
        if (currentPage < totalPages - 1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                showPage(currentPage + 1)
            }
        }
    }



    private fun updatePageIndicator() {
        binding.pageIndicator.text = "${currentPage + 1}/$totalPages"

        if (totalPages > 1) {
            binding.pageIndicator.visibility = View.VISIBLE
            binding.btnPrevPage.visibility = View.VISIBLE
            binding.btnNextPage.visibility = View.VISIBLE
            binding.btnPrevPage.isEnabled = currentPage > 0
            binding.btnNextPage.isEnabled = currentPage < totalPages - 1
        } else {
            binding.pageIndicator.visibility = View.GONE
            binding.btnPrevPage.visibility = View.GONE
            binding.btnNextPage.visibility = View.GONE
        }
    }

    // ЗАМЕНЯЕМ функцию showSpeechOptionsDialog
    private fun showSpeechOptionsDialog() {
        val options = arrayOf(
            "Озвучить всю книгу с начала",
            "Озвучить с текущей страницы до конца"
        )

        AlertDialog.Builder(this)
            .setTitle("Режим озвучки")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startSpeechFullBook(false) // Вся книга с начала
                    1 -> startSpeechFullBook(true)  // С текущей страницы до конца
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ОБНОВЛЯЕМ showTtsSettingsDialog
    private fun showTtsSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tts_settings, null)

        val cbShowCurrentPage = dialogView.findViewById<CheckBox>(R.id.cbShowCurrentPage)
        val sbSpeed = dialogView.findViewById<SeekBar>(R.id.sbSpeed)
        val rgRepeatMode = dialogView.findViewById<RadioGroup>(R.id.rgRepeatMode)
        val cbKeepScreenOn = dialogView.findViewById<CheckBox>(R.id.cbKeepScreenOn)
        val rgVoice = dialogView.findViewById<RadioGroup>(R.id.rgVoice)

        // Устанавливаем текущие значения
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

        AlertDialog.Builder(this)
            .setTitle("Настройки чтения")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                // Сохраняем настройки БЕЗ прерывания озвучки
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
                applyTtsSettings() // Применяем БЕЗ перезапуска озвучки

                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

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
""".trimIndent()  // ← УБИРАЕМ isWordByWordMode

        AlertDialog.Builder(this)
            .setTitle("Статус TTS")
            .setMessage(status)
            .setPositiveButton("OK", null)
            .show()
    }



    // ОБНОВЛЯЕМ функцию сохранения закладки
    // ОБНОВЛЯЕМ saveBookmark
    private fun saveBookmark() {
        val bookmark = Bookmark(
            position = currentPage,
            title = "Страница ${currentPage + 1}"
        )
        bookmarks.add(bookmark)
        Toast.makeText(this, "Закладка сохранена", Toast.LENGTH_SHORT).show()
    }

    // ОБНОВЛЯЕМ функцию загрузки закладок
    private fun loadBookmarks() {
        val fileKey = currentPdfFile?.absolutePath ?: return
        val bookmarksJson = prefs.getString("bookmarks_$fileKey", "[]")
        bookmarks = if (bookmarksJson.isNullOrEmpty()) {
            mutableListOf()
        } else {
            Gson().fromJson(bookmarksJson, Array<Bookmark>::class.java).toMutableList()
        }
    }


    // ОБНОВЛЯЕМ отображение закладок в диалоге
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

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { loadPdfFromUri(it) }
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


    //////////////////////////////////////////////////
    ////////////////  ВСЕ Функции поиска \\\\\\\\\\\\\\\\
    /////////////////////////////////////////////////////////
    ///////////\\\\\\\\\\\\\\\\\\\\\\\\\\\\\

///////////// это версия поиска из v 5.3.0, уже исправленная машиной


//////////////////\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\\


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

//// -------------------------------------------

    // ОБНОВЛЯЕМ функцию searchInPdf
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
                    // Определяем страницу для результата
                    val page = findPageForTextPosition(index)
                    pages.add(page)
                    index = currentTextContent.indexOf(query, index + 1, true)
                }

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE // ВАЖНО: убираем прогресс-бар

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
                    binding.progressBar.visibility = View.GONE // Убираем даже при ошибке
                    Toast.makeText(this, "Ошибка поиска", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // Функция для определения страницы по позиции текста
    private fun findPageForTextPosition(position: Int): Int {
        if (currentPdfFile == null || currentTextContent.isEmpty()) return 0

        try {
            // Простое вычисление страницы по пропорции
            val approxPage = (position * totalPages / currentTextContent.length).coerceIn(0, totalPages - 1)
            return approxPage
        } catch (e: Exception) {
            Log.e("PageFind", "Ошибка вычисления страницы: ${e.message}")
            return 0
        }
    }

    //// -------------------------------------------
    // Обновляем функцию showSearchResult
    private fun showSearchResult(position: Int, query: String, page: Int) {
        try {
            val start = max(0, position - 30)
            val end = min(currentTextContent.length, position + query.length + 30)
            val context = currentTextContent.substring(start, end)

            val highlighted = context.replace(query, "►$query◄", true)

            binding.searchResultText.text = highlighted
            binding.searchPosition.text = "${currentSearchIndex + 1}/${searchResults.size}"
            binding.searchPageNumber.text = "Страница: ${page + 1}"

            // Обработчик клика по номеру страницы
            binding.searchPageNumber.setOnClickListener {
                if (isSpeaking || isPaused) {
                    stopSpeech()
                }

                // Переходим на графическую страницу
                showPage(page)
                binding.searchPanel.visibility = View.GONE
                Toast.makeText(this, "Переход на страницу ${page + 1}", Toast.LENGTH_SHORT).show()
            }

            binding.searchPanel.visibility = View.VISIBLE
            binding.pdfTextView.text = currentTextContent
            binding.pdfTextView.visibility = View.VISIBLE
            binding.pdfImageView.visibility = View.GONE

            // Скроллим к найденной позиции
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
//// --------------------------------------------------------------------------------

    // Обновляем функции навигации по поиску
    private fun showNextSearchResult() {
        if (currentSearchIndex < searchResults.size - 1) {
            currentSearchIndex++
            showSearchResult(searchResults[currentSearchIndex], searchQuery, searchResultPages[currentSearchIndex])
            binding.btnPrevSearch.isEnabled = true
            binding.btnNextSearch.isEnabled = currentSearchIndex < searchResults.size - 1
        }
    }

//// ---------------------------------------------------------------------------------------

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
            PdfChitalka v5.3.27
            Полнофункциональный просмотрщик PDF
            
            Особенности:
            • Нативный просмотр PDF как в оригинале
            • Поддержка графических и текстовых PDF
            • Озвучка текстового содержимого
            • Закладки и навигация
            • Настройки отображения
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("О программе")
            .setMessage(aboutText)
            .setPositiveButton("OK", null)
            .show()
    }

}