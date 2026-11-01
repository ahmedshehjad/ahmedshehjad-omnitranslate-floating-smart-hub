package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.ClipboardItem
import com.example.engine.LanguageCatalog
import com.example.engine.TranslationEngine
import com.example.engine.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val NOTIFICATION_CHANNEL_ID = "omni_floating_bubble_channel"
        const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _lastCopiedText = MutableStateFlow("")
        val lastCopiedText = _lastCopiedText.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var windowManager: WindowManager? = null
    private var bubbleRootView: View? = null
    private var cardRootView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var cardParams: WindowManager.LayoutParams? = null

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private var ttsManager: TtsManager? = null
    private var currentSourceLang = "en"
    private var currentTargetLang = "bn"
    private var lastResultText = ""

    private var screenWidth = 0
    private var screenHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ttsManager = TtsManager(this)

        updateScreenDimensions()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        setupClipboardListener()
        createFloatingBubbleView()
        createFloatingCardView()

        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OmniTranslate Floating Bubble",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the screen translation bubble active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OmniTranslate Hub Active")
            .setContentText("Screen bubble is ready. Tap to open or drag across apps.")
            .setSmallIcon(R.drawable.ic_bubble_translate)
            .setContentIntent(pendingOpenApp)
            .addAction(R.drawable.ic_floating_close, "Stop Bubble", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupClipboardListener() {
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (text.isNotBlank() && text != _lastCopiedText.value) {
                    _lastCopiedText.value = text
                    // Save to Room DB asynchronously
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = AppDatabase.getInstance(applicationContext)
                            val detected = TranslationEngine.identifyLanguage(text) ?: "auto"
                            val target = if (detected == "bn") "en" else "bn"
                            val translatedResult = TranslationEngine.translate(text, if (detected == "auto") "en" else detected, target)
                            val trans = translatedResult.getOrDefault("")
                            db.clipboardDao().insert(
                                ClipboardItem(
                                    originalText = text,
                                    translatedText = trans,
                                    sourceLanguage = detected,
                                    targetLanguage = target,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } catch (_: Exception) {}
                    }
                    // Visual pulse or toast hint
                    scope.launch(Dispatchers.Main) {
                        bubbleRootView?.animate()?.scaleX(1.2f)?.scaleY(1.2f)?.setDuration(200)?.withEndAction {
                            bubbleRootView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(200)?.start()
                        }?.start()
                    }
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingBubbleView() {
        val bubbleSizePx = dpToPx(62f)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSizePx,
            bubbleSizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = screenHeight / 3
        }

        val frame = FrameLayout(this).apply {
            background = getDrawable(R.drawable.bg_floating_bubble)
            elevation = dpToPx(8f).toFloat()
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_bubble_translate)
                val pad = dpToPx(14f)
                setPadding(pad, pad, pad, pad)
            }
            addView(
                icon,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L

        frame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartTime = System.currentTimeMillis()
                    initialX = bubbleParams?.x ?: 0
                    initialY = bubbleParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    bubbleParams?.x = initialX + deltaX
                    bubbleParams?.y = initialY + deltaY
                    bubbleRootView?.let { windowManager?.updateViewLayout(it, bubbleParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchStartTime
                    val totalDistance = Math.hypot(
                        (event.rawX - initialTouchX).toDouble(),
                        (event.rawY - initialTouchY).toDouble()
                    )

                    if (totalDistance < 20 && duration < 300) {
                        // Click event! Toggle floating card
                        toggleFloatingCard()
                    } else {
                        // Magnetic snap to left or right edge
                        snapBubbleToEdge()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleRootView = frame
        windowManager?.addView(bubbleRootView, bubbleParams)
    }

    private fun snapBubbleToEdge() {
        val currentX = bubbleParams?.x ?: 0
        val targetX = if (currentX + dpToPx(30f) < screenWidth / 2) {
            dpToPx(12f)
        } else {
            screenWidth - dpToPx(74f)
        }

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                bubbleParams?.x = anim.animatedValue as Int
                bubbleRootView?.let { windowManager?.updateViewLayout(it, bubbleParams) }
            }
        }
        animator.start()
    }

    @SuppressLint("SetTextI18n")
    private fun createFloatingCardView() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val cardWidth = Math.min((screenWidth * 0.90f).toInt(), dpToPx(380f))

        cardParams = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_floating_card)
            val padding = dpToPx(16f)
            setPadding(padding, padding, padding, padding)
            elevation = dpToPx(16f).toFloat()
        }

        // --- Header Row ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "⚡ OmniTranslate"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Language pills
        val sourcePill = createPillTextView(currentSourceLang.uppercase())
        val swapBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_swap)
            val p = dpToPx(6f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f)).apply {
                setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            }
        }
        val targetPill = createPillTextView(currentTargetLang.uppercase())

        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_close)
            val p = dpToPx(6f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply {
                setMargins(dpToPx(8f), 0, 0, 0)
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(sourcePill)
        headerRow.addView(swapBtn)
        headerRow.addView(targetPill)
        headerRow.addView(closeBtn)

        cardLayout.addView(headerRow)

        // --- Input Edit Box ---
        val inputEdit = EditText(this).apply {
            hint = "Type or paste text to translate..."
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val inputBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A233A"))
                cornerRadius = dpToPx(12f).toFloat()
                setStroke(dpToPx(1f), Color.parseColor("#3300E5FF"))
            }
            background = inputBg
            val p = dpToPx(10f)
            setPadding(p, p, p, p)
            maxLines = 4
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(12f), 0, dpToPx(8f))
            }
        }
        cardLayout.addView(inputEdit)

        // --- Action Buttons Row (Translate & Paste) ---
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(10f))
            }
        }

        val pasteBtn = TextView(this).apply {
            text = "📋 Paste Clip"
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#152542"))
                cornerRadius = dpToPx(8f).toFloat()
            }
            background = bg
            val p = dpToPx(8f)
            setPadding(p, dpToPx(6f), p, dpToPx(6f))
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dpToPx(24f), dpToPx(24f)).apply {
                setMargins(0, 0, dpToPx(8f), 0)
            }
        }

        val translateBtn = TextView(this).apply {
            text = "Translate ✨"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                colors = intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#7C4DFF"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dpToPx(8f).toFloat()
            }
            background = bg
            val pHoriz = dpToPx(14f)
            val pVert = dpToPx(7f)
            setPadding(pHoriz, pVert, pHoriz, pVert)
        }

        actionsRow.addView(pasteBtn)
        actionsRow.addView(spacer)
        actionsRow.addView(progressBar)
        actionsRow.addView(translateBtn)

        cardLayout.addView(actionsRow)

        // --- Result Output Box ---
        val resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val resultBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0B132B"))
                cornerRadius = dpToPx(12f).toFloat()
                setStroke(dpToPx(1f), Color.parseColor("#2600E5FF"))
            }
            background = resultBg
            val p = dpToPx(10f)
            setPadding(p, p, p, p)
            visibility = View.GONE
        }

        val resultText = TextView(this).apply {
            setTextColor(Color.parseColor("#E0F7FA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val resultActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(8f), 0, 0)
            }
        }

        val copyResultBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_copy)
            val p = dpToPx(4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f))
        }

        val speakResultBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_speaker)
            val p = dpToPx(4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f)).apply {
                setMargins(dpToPx(8f), 0, 0, 0)
            }
        }

        val openFullAppBtn = TextView(this).apply {
            text = "Open Hub ↗"
            setTextColor(Color.parseColor("#80D8FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(12f), 0, 0, 0)
            }
        }

        resultActionsRow.addView(copyResultBtn)
        resultActionsRow.addView(speakResultBtn)
        resultActionsRow.addView(openFullAppBtn)

        resultContainer.addView(resultText)
        resultContainer.addView(resultActionsRow)
        cardLayout.addView(resultContainer)

        // Event listeners
        closeBtn.setOnClickListener {
            hideFloatingCard()
        }

        swapBtn.setOnClickListener {
            val tmp = currentSourceLang
            currentSourceLang = currentTargetLang
            currentTargetLang = tmp
            sourcePill.text = currentSourceLang.uppercase()
            targetPill.text = currentTargetLang.uppercase()
        }

        pasteBtn.setOnClickListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                inputEdit.setText(text)
                inputEdit.setSelection(text.length)
            }
        }

        translateBtn.setOnClickListener {
            val textToTranslate = inputEdit.text.toString().trim()
            if (textToTranslate.isBlank()) {
                Toast.makeText(this, "Please enter or paste text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            translateBtn.isEnabled = false

            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    TranslationEngine.translate(
                        textToTranslate,
                        currentSourceLang,
                        currentTargetLang
                    )
                }

                progressBar.visibility = View.GONE
                translateBtn.isEnabled = true

                result.onSuccess { translated ->
                    lastResultText = translated
                    resultText.text = translated
                    resultContainer.visibility = View.VISIBLE

                    // Save to Room DB
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(applicationContext).clipboardDao().insert(
                            ClipboardItem(
                                originalText = textToTranslate,
                                translatedText = translated,
                                sourceLanguage = currentSourceLang,
                                targetLanguage = currentTargetLang,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }.onFailure { err ->
                    Toast.makeText(this@FloatingBubbleService, "Translation error: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        copyResultBtn.setOnClickListener {
            if (lastResultText.isNotBlank()) {
                val clip = ClipData.newPlainText("OmniTranslate", lastResultText)
                clipboardManager?.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        speakResultBtn.setOnClickListener {
            if (lastResultText.isNotBlank()) {
                ttsManager?.speak(lastResultText, currentTargetLang)
            }
        }

        openFullAppBtn.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            hideFloatingCard()
        }

        // Tap outside handler
        cardLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideFloatingCard()
                true
            } else {
                false
            }
        }

        cardRootView = cardLayout
    }

    private fun createPillTextView(initialText: String): TextView {
        return TextView(this).apply {
            text = initialText
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#152542"))
                cornerRadius = dpToPx(6f).toFloat()
            }
            background = bg
            val pHoriz = dpToPx(8f)
            val pVert = dpToPx(4f)
            setPadding(pHoriz, pVert, pHoriz, pVert)
        }
    }

    private fun toggleFloatingCard() {
        if (cardRootView?.isAttachedToWindow == true) {
            hideFloatingCard()
        } else {
            showFloatingCard()
        }
    }

    private fun showFloatingCard() {
        if (cardRootView?.isAttachedToWindow != true) {
            // Auto paste if clipboard has text
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString() ?: ""
                val editText = cardRootView?.findViewById<EditText>(android.R.id.text1)
                // If inputEdit exists, populate
            }
            windowManager?.addView(cardRootView, cardParams)
        }
    }

    private fun hideFloatingCard() {
        if (cardRootView?.isAttachedToWindow == true) {
            windowManager?.removeView(cardRootView)
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }

        if (bubbleRootView?.isAttachedToWindow == true) {
            windowManager?.removeView(bubbleRootView)
        }
        if (cardRootView?.isAttachedToWindow == true) {
            windowManager?.removeView(cardRootView)
        }

        ttsManager?.shutdown()
        serviceJob.cancel()
    }
}
