package com.necromagik.purehub

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.necromagik.purehub.service.CaffeinePreferences
import com.necromagik.purehub.service.CaffeineService
import com.necromagik.purehub.service.DiscoveredSound
import com.necromagik.purehub.service.NotificationMuteController
import com.necromagik.purehub.service.ProcessManager
import com.necromagik.purehub.service.SoundCategory
import com.necromagik.purehub.service.SoundController
import com.necromagik.purehub.service.TableType
import com.necromagik.purehub.service.UpdateManager
import com.necromagik.purehub.service.UpdateUiState
import com.necromagik.purehub.ui.animation.bounceClick
import com.necromagik.purehub.ui.components.PureSwitch
import com.necromagik.purehub.ui.components.SwitchIconType
import com.necromagik.purehub.ui.theme.LocalPureHubConfig
import com.necromagik.purehub.ui.theme.PureHUBTheme
import com.necromagik.purehub.ui.theme.pure3DEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PureHUBTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = Color(0xFF2B2B2B),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkShizukuStatus()
        viewModel.syncServiceStateWithReality(this)
        ProcessManager.logRunningProcessesSnapshot("MainActivity onResume")
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val appContext = LocalContext.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var selectedSoundTarget by remember { mutableStateOf<Pair<String, TableType>?>(null) }

    val discoveredSounds by produceState(initialValue = emptyList<DiscoveredSound>(), state.hasShizukuPermission, refreshTrigger) {
        value = if (state.hasShizukuPermission) {
            SoundController.scanAndLogSystemSoundsAsync(appContext)
        } else {
            emptyList()
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val target = selectedSoundTarget
        if (uri != null && target != null) {
            val (key, table) = target
            val success = SoundController.installCustomSound(appContext, key, uri, table)
            if (success) {
                refreshTrigger++
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Тихая фоновая проверка обновлений при запуске приложения
        UpdateManager.checkForUpdates(appContext, showUserFeedback = false)
    }

    MainScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        discoveredSounds = discoveredSounds,
        appContext = appContext,
        onRequestShizuku = { viewModel.requestShizukuPermission() },
        onToggleService = { viewModel.toggleService(appContext) },
        onForceStopMusic = { viewModel.executeForceStop(appContext) },
        onSelectSoundFile = { sound ->
            val itemKey = sound.settingsKey ?: sound.fileName
            selectedSoundTarget = itemKey to sound.tableType
            audioPickerLauncher.launch("audio/*")
        },
        onResetSound = { sound ->
            val itemKey = sound.settingsKey ?: sound.fileName
            if (SoundController.resetCustomSound(appContext, itemKey, sound.tableType)) {
                refreshTrigger++
            }
        },
        modifier = modifier
    )
}

@Composable
fun MainScreenContent(
    state: PureHubUiState,
    snackbarHostState: SnackbarHostState,
    discoveredSounds: List<DiscoveredSound>,
    appContext: Context,
    onRequestShizuku: () -> Unit,
    onToggleService: () -> Unit,
    onForceStopMusic: () -> Unit,
    onSelectSoundFile: (DiscoveredSound) -> Unit,
    onResetSound: (DiscoveredSound) -> Unit,
    modifier: Modifier = Modifier
) {
    val themeConfig = LocalPureHubConfig.current
    val coroutineScope = rememberCoroutineScope()
    val cardShape = remember(themeConfig.cardCornerRadius) {
        RoundedCornerShape(themeConfig.cardCornerRadius)
    }

    val groupedSounds = remember(discoveredSounds) {
        discoveredSounds.groupBy { it.category }
    }

    var isSoundSectionExpanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (isSoundSectionExpanded) 180f else 0f,
        label = "ArrowRotation"
    )

    val isShizukuReady = state.isShizukuRunning && state.hasShizukuPermission
    val errorColor = Color(0xFFFF5252)
    val warningColor = Color(0xFFFFB300)
    val dynamicAccent = if (isShizukuReady) themeConfig.accentColor else errorColor

    var isCaffeineRunning by remember { mutableStateOf(CaffeineService.isRunning) }
    var caffeineSecondsLeft by remember { mutableIntStateOf(CaffeineService.remainingSecondsTotal) }

    DisposableEffect(Unit) {
        val listener = {
            isCaffeineRunning = CaffeineService.isRunning
            caffeineSecondsLeft = CaffeineService.remainingSecondsTotal
        }
        CaffeineService.addTickListener(listener)
        onDispose {
            CaffeineService.removeTickListener(listener)
        }
    }

    var isMutedState by remember { mutableStateOf(NotificationMuteController.isMuted) }

    DisposableEffect(Unit) {
        val muteListener: (Boolean) -> Unit = { muted ->
            isMutedState = muted
        }
        NotificationMuteController.onMuteChangedListener = muteListener
        onDispose {
            if (NotificationMuteController.onMuteChangedListener === muteListener) {
                NotificationMuteController.onMuteChangedListener = null
            }
        }
    }

    // Состояние автообновлятора
    val updateState by UpdateManager.updateState.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    val activeReleaseInfo = when (val st = updateState) {
        is UpdateUiState.Available -> st.info
        is UpdateUiState.Downloading -> st.info
        is UpdateUiState.ReadyToInstall -> st.info
        else -> null
    }

    // Диалог скачивания и установки обновления
    if (showUpdateDialog && activeReleaseInfo != null) {
        AlertDialog(
            onDismissRequest = {
                if (updateState !is UpdateUiState.Downloading) {
                    showUpdateDialog = false
                }
            },
            title = {
                Text(
                    text = "Доступно обновление ${activeReleaseInfo.versionTag}",
                    fontWeight = FontWeight.Bold,
                    color = themeConfig.accentColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Текущая версия: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = activeReleaseInfo.changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )

                    if (updateState is UpdateUiState.Downloading) {
                        val progress = (updateState as UpdateUiState.Downloading).progress
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = themeConfig.accentColor,
                            trackColor = Color.White.copy(alpha = 0.15f)
                        )
                        Text(
                            text = "Скачивание APK: $progress%",
                            fontSize = 12.sp,
                            color = themeConfig.accentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = updateState !is UpdateUiState.Downloading,
                    onClick = {
                        coroutineScope.launch {
                            UpdateManager.downloadAndInstall(
                                appContext,
                                activeReleaseInfo
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeConfig.accentColor,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (updateState is UpdateUiState.Downloading) "Загрузка..." else "Скачать и установить",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                if (updateState !is UpdateUiState.Downloading) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("Позже", color = Color.Gray)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp)
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Заголовок PureHUB + значок со стрелочкой при наличии обновления
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(style = SpanStyle(color = Color.White)) {
                                append("Pure")
                            }
                            withStyle(style = SpanStyle(color = dynamicAccent)) {
                                append("HUB")
                            }
                        },
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Значок со стрелочкой, сигнализирующий о новом обновлении
                    if (activeReleaseInfo != null) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(themeConfig.accentColor.copy(alpha = 0.2f))
                                .clickable { showUpdateDialog = true }
                                .bounceClick(),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val stroke = 2.2.dp.toPx()
                                // Вертикальная линия стрелочки
                                drawLine(
                                    color = themeConfig.accentColor,
                                    start = Offset(size.width * 0.5f, size.height * 0.12f),
                                    end = Offset(size.width * 0.5f, size.height * 0.75f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                                // Наконечник стрелочки вниз
                                val arrowHead = Path().apply {
                                    moveTo(size.width * 0.22f, size.height * 0.50f)
                                    lineTo(size.width * 0.5f, size.height * 0.78f)
                                    lineTo(size.width * 0.78f, size.height * 0.50f)
                                }
                                drawPath(
                                    path = arrowHead,
                                    color = themeConfig.accentColor,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                                )
                                // Нижняя полочка загрузки
                                drawLine(
                                    color = themeConfig.accentColor,
                                    start = Offset(size.width * 0.2f, size.height * 0.94f),
                                    end = Offset(size.width * 0.8f, size.height * 0.94f),
                                    strokeWidth = stroke,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Статус Shizuku
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = dynamicAccent,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusItem(
                        isCheck = state.isShizukuRunning,
                        label = "Статус Shizuku",
                        value = if (state.isShizukuRunning) "Активен" else "Остановлен",
                        isActive = state.isShizukuRunning,
                        accentColor = themeConfig.accentColor
                    )

                    HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))

                    StatusItem(
                        isCheck = state.hasShizukuPermission,
                        label = "Доступ Shell",
                        value = if (state.hasShizukuPermission) "Предоставлен" else "Ожидание",
                        isActive = state.hasShizukuPermission,
                        accentColor = themeConfig.accentColor
                    )
                }
            }
        }

        if (state.isShizukuRunning && !state.hasShizukuPermission) {
            item {
                Button(
                    onClick = onRequestShizuku,
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClick(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = themeConfig.accentColor,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Запросить права Shizuku", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. Управление треками кнопками громкости
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = dynamicAccent,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dynamicAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(20.dp)) {
                                val strokeWidth = 2.dp.toPx()
                                drawCircle(
                                    color = dynamicAccent,
                                    radius = 3.dp.toPx(),
                                    center = Offset(size.width * 0.3f, size.height * 0.75f)
                                )
                                drawCircle(
                                    color = dynamicAccent,
                                    radius = 3.dp.toPx(),
                                    center = Offset(size.width * 0.8f, size.height * 0.6f)
                                )
                                drawLine(
                                    color = dynamicAccent,
                                    start = Offset(size.width * 0.38f, size.height * 0.75f),
                                    end = Offset(size.width * 0.38f, size.height * 0.25f),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = dynamicAccent,
                                    start = Offset(size.width * 0.88f, size.height * 0.6f),
                                    end = Offset(size.width * 0.88f, size.height * 0.15f),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = dynamicAccent,
                                    start = Offset(size.width * 0.38f, size.height * 0.25f),
                                    end = Offset(size.width * 0.88f, size.height * 0.15f),
                                    strokeWidth = strokeWidth * 1.5f,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "Управление треками",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isShizukuReady) "Удержание Vol+ / Vol- для Next / Prev" else "Требуется запущенный Shizuku",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = if (isShizukuReady) Color.Gray else errorColor
                            )
                        }
                    }

                    PureSwitch(
                        checked = state.isServiceRunning && isShizukuReady,
                        enabled = isShizukuReady,
                        iconType = SwitchIconType.MUSIC,
                        trackColorOverride = if (!isShizukuReady) errorColor.copy(alpha = 0.35f) else null,
                        onCheckedChange = { onToggleService() },
                        onDisabledClick = {
                            coroutineScope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("Шизуку выключен или нет прав доступа")
                            }
                        }
                    )
                }
            }
        }

        // Блок точечной остановки процессов перехвата музыки (Force Stop)
        item {
            var showForceStopDialog by remember { mutableStateOf(false) }

            if (showForceStopDialog) {
                AlertDialog(
                    onDismissRequest = { showForceStopDialog = false },
                    title = {
                        Text(
                            text = "Сбросить процессы музыки?",
                            fontWeight = FontWeight.Bold,
                            color = errorColor
                        )
                    },
                    text = {
                        Text(
                            text = "Все фоновые демоны :service и зависшие слушатели кнопок громкости будут принудительно убиты через Shell. Само приложение останется открытым.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showForceStopDialog = false
                                onForceStopMusic()
                                coroutineScope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("Фоновые процессы музыки остановлены")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = errorColor,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Очистить демоны", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showForceStopDialog = false }) {
                            Text("Отмена", color = Color.Gray)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = errorColor,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Сброс зависших процессов",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Точечное убийство зомби-потоков медиа-сервиса без закрытия UI",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }

                    Button(
                        onClick = { showForceStopDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = errorColor.copy(alpha = 0.2f),
                            contentColor = errorColor
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.bounceClick()
                    ) {
                        Text("Force Stop", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Кофеин с динамическим таймером и кнопкой включения
        item {
            var caffeineSteps by remember { mutableStateOf(CaffeinePreferences.getTimerSteps(appContext)) }
            var showStepDialog by remember { mutableStateOf(false) }
            var newMinutesInput by remember { mutableStateOf("") }

            if (showStepDialog) {
                AlertDialog(
                    onDismissRequest = { showStepDialog = false },
                    title = { Text("Добавить таймер", fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = newMinutesInput,
                            onValueChange = { input -> newMinutesInput = input.filter { it.isDigit() } },
                            label = { Text("Время в минутах") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val mins = newMinutesInput.toIntOrNull()
                                if (mins != null && mins > 0 && !caffeineSteps.contains(mins)) {
                                    val updated = (caffeineSteps + mins).sorted()
                                    caffeineSteps = updated
                                    CaffeinePreferences.saveTimerSteps(appContext, updated)
                                }
                                newMinutesInput = ""
                                showStepDialog = false
                            }
                        ) {
                            Text("Добавить", color = themeConfig.accentColor)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showStepDialog = false }) {
                            Text("Отмена", color = Color.Gray)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = dynamicAccent,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .padding(18.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dynamicAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(22.dp)) {
                                val strokeWidth = 1.8.dp.toPx()
                                val cupPath = Path().apply {
                                    moveTo(size.width * 0.2f, size.height * 0.35f)
                                    lineTo(size.width * 0.23f, size.height * 0.72f)
                                    quadraticTo(
                                        size.width * 0.25f, size.height * 0.88f,
                                        size.width * 0.45f, size.height * 0.88f
                                    )
                                    lineTo(size.width * 0.55f, size.height * 0.88f)
                                    quadraticTo(
                                        size.width * 0.75f, size.height * 0.88f,
                                        size.width * 0.77f, size.height * 0.72f
                                    )
                                    lineTo(size.width * 0.8f, size.height * 0.35f)
                                    close()
                                }
                                drawPath(
                                    path = cupPath,
                                    color = dynamicAccent,
                                    style = Stroke(width = strokeWidth)
                                )

                                val handlePath = Path().apply {
                                    moveTo(size.width * 0.78f, size.height * 0.45f)
                                    quadraticTo(
                                        size.width * 0.98f, size.height * 0.45f,
                                        size.width * 0.95f, size.height * 0.65f
                                    )
                                    quadraticTo(
                                        size.width * 0.92f, size.height * 0.72f,
                                        size.width * 0.75f, size.height * 0.72f
                                    )
                                }
                                drawPath(
                                    path = handlePath,
                                    color = dynamicAccent,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )

                                drawLine(
                                    color = dynamicAccent,
                                    start = Offset(size.width * 0.38f, size.height * 0.25f),
                                    end = Offset(size.width * 0.38f, size.height * 0.12f),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = dynamicAccent,
                                    start = Offset(size.width * 0.62f, size.height * 0.25f),
                                    end = Offset(size.width * 0.62f, size.height * 0.12f),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Кофеин",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = dynamicAccent.copy(alpha = 0.15f)
                                ) {
                                    val timeLabel = if (isCaffeineRunning) {
                                        if (caffeineSecondsLeft < 60) "${caffeineSecondsLeft}с" else "${(caffeineSecondsLeft + 59) / 60}м"
                                    } else {
                                        "Панель управления"
                                    }
                                    Text(
                                        text = timeLabel,
                                        color = dynamicAccent,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (!isShizukuReady) {
                                    "Требуется запущенный Shizuku для удержания экрана"
                                } else if (isCaffeineRunning) {
                                    "Экран удерживается активным. Осталось $caffeineSecondsLeft сек"
                                } else {
                                    "Добавьте плитку в шторку. Клик до 10с переключает время, после 10с — отключает."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = if (isShizukuReady) Color.Gray else errorColor,
                                lineHeight = 16.sp
                            )
                        }

                        PureSwitch(
                            checked = isCaffeineRunning && isShizukuReady,
                            enabled = isShizukuReady,
                            iconType = SwitchIconType.NONE,
                            trackColorOverride = if (!isShizukuReady) errorColor.copy(alpha = 0.35f) else null,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val firstStep = caffeineSteps.firstOrNull() ?: 3
                                    val intent = android.content.Intent(appContext, CaffeineService::class.java).apply {
                                        action = CaffeineService.ACTION_START
                                        putExtra(CaffeineService.EXTRA_MINUTES, firstStep)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        appContext.startForegroundService(intent)
                                    } else {
                                        appContext.startService(intent)
                                    }
                                } else {
                                    val intent = android.content.Intent(appContext, CaffeineService::class.java).apply {
                                        action = CaffeineService.ACTION_STOP
                                    }
                                    appContext.startService(intent)
                                }
                                CaffeineService.requestTileUpdate(appContext)
                            },
                            onDisabledClick = {
                                coroutineScope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar("Шизуку выключен или нет прав доступа")
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        caffeineSteps.forEach { step ->
                            InputChip(
                                selected = false,
                                onClick = {
                                    if (caffeineSteps.size > 1) {
                                        val updated = caffeineSteps.filter { it != step }
                                        caffeineSteps = updated
                                        CaffeinePreferences.saveTimerSteps(appContext, updated)
                                    }
                                },
                                label = { Text("${step}м ✕", fontSize = 11.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }

                        AssistChip(
                            onClick = { showStepDialog = true },
                            label = { Text("+ Время", fontSize = 11.sp, color = dynamicAccent) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }

        // 4. Глушение уведомлений (heads-up баннеры, звук и вибрация)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = dynamicAccent,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(dynamicAccent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_notification_mute),
                                contentDescription = null,
                                tint = dynamicAccent,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "Глушение уведомлений",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (!isShizukuReady) {
                                    "Требуется запущенный Shizuku"
                                } else if (isMutedState) {
                                    "Всплывающие окна и звуки отключены"
                                } else {
                                    "Обычный прием уведомлений"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = if (isShizukuReady) Color.Gray else errorColor
                            )
                        }
                    }

                    PureSwitch(
                        checked = isMutedState && isShizukuReady,
                        enabled = isShizukuReady,
                        iconType = SwitchIconType.NOTIFICATIONS,
                        trackColorOverride = if (!isShizukuReady) errorColor.copy(alpha = 0.35f) else null,
                        onCheckedChange = {
                            val next = !isMutedState
                            NotificationMuteController.setNotificationsMuted(next)
                            isMutedState = next
                        },
                        onDisabledClick = {
                            coroutineScope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("Шизуку выключен или нет прав доступа")
                            }
                        }
                    )
                }
            }
        }

        // 5. Плашка "Смена звука системы" (оранжевый акцент)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pure3DEffect(
                        shape = cardShape,
                        accentColor = warningColor,
                        depthDp = themeConfig.depthIntensityDp,
                        is3dEnabled = themeConfig.is3dEnabled,
                        isGlowEnabled = themeConfig.isGlowEnabled,
                        surfaceColor = MaterialTheme.colorScheme.surface
                    )
                    .clip(cardShape)
                    .clickable { isSoundSectionExpanded = !isSoundSectionExpanded }
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Смена звука системы",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = warningColor.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "в разработке",
                                    color = warningColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isSoundSectionExpanded) "Нажмите, чтобы свернуть список" else "Прямая замена через Settings (${discoveredSounds.size})",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(arrowRotation),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(14.dp)) {
                            val stroke = 2.dp.toPx()
                            val path = Path().apply {
                                moveTo(0f, size.height * 0.35f)
                                lineTo(size.width * 0.5f, size.height * 0.75f)
                                lineTo(size.width, size.height * 0.35f)
                            }
                            drawPath(
                                path = path,
                                color = warningColor,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
            }
        }

        // 6. Раскрывающийся список звуков
        item {
            AnimatedVisibility(
                visible = isSoundSectionExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Settings Audio Engine",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Gray
                        )
                        TextButton(
                            onClick = {
                                SoundController.restartSystemUIWithResult()
                            },
                            modifier = Modifier.bounceClick()
                        ) {
                            Text("Перезапуск UI", color = warningColor, fontSize = 12.sp)
                        }
                    }

                    SoundCategory.entries.forEach { category ->
                        val soundsInCategory = groupedSounds[category] ?: emptyList()
                        if (soundsInCategory.isNotEmpty()) {
                            Text(
                                text = category.title.uppercase(),
                                color = warningColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pure3DEffect(
                                        shape = cardShape,
                                        accentColor = warningColor,
                                        depthDp = themeConfig.depthIntensityDp,
                                        is3dEnabled = themeConfig.is3dEnabled,
                                        isGlowEnabled = themeConfig.isGlowEnabled,
                                        surfaceColor = MaterialTheme.colorScheme.surface
                                    )
                                    .padding(16.dp)
                            ) {
                                Column {
                                    soundsInCategory.forEachIndexed { index, sound ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { onSelectSoundFile(sound) }
                                                .padding(vertical = 8.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = sound.localizedTitle,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = sound.fileName + if (sound.isCustom) " • Заменен" else " • По умолчанию",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (sound.isCustom) warningColor else Color.Gray,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            if (sound.isCustom) {
                                                TextButton(
                                                    onClick = { onResetSound(sound) },
                                                    modifier = Modifier.bounceClick()
                                                ) {
                                                    Text("Сброс", color = Color(0xFFFF5252), fontSize = 11.sp)
                                                }
                                            } else {
                                                Text(
                                                    text = "Заменить",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = warningColor
                                                )
                                            }
                                        }

                                        if (index < soundsInCategory.size - 1) {
                                            HorizontalDivider(color = Color.Gray.copy(alpha = 0.1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Подвал (Footer) с текущей версией из Gradle и проверкой обновлений по тапу
        item {
            val isChecking = updateState is UpdateUiState.Checking
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !isChecking) {
                        if (activeReleaseInfo != null) {
                            showUpdateDialog = true
                        } else {
                            coroutineScope.launch {
                                UpdateManager.checkForUpdates(
                                    context = appContext,
                                    showUserFeedback = true
                                )
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PureHUB v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        isChecking -> "Проверка обновлений на GitHub..."
                        activeReleaseInfo != null -> "Доступна версия ${activeReleaseInfo.versionTag} • Нажмите для установки"
                        else -> "Нажмите для проверки обновлений"
                    },
                    color = if (activeReleaseInfo != null) themeConfig.accentColor else Color.Gray.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    isCheck: Boolean,
    label: String,
    value: String,
    isActive: Boolean,
    accentColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val stroke = 2.dp.toPx()
                val iconColor = if (isActive) accentColor else Color.Gray
                if (isCheck) {
                    drawCircle(color = iconColor, style = Stroke(width = stroke))
                    val path = Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.72f, size.height * 0.36f)
                    }
                    drawPath(path = path, color = iconColor, style = Stroke(width = stroke, cap = StrokeCap.Round))
                } else {
                    drawCircle(color = iconColor, style = Stroke(width = stroke))
                    drawLine(
                        color = iconColor,
                        start = Offset(size.width * 0.5f, size.height * 0.25f),
                        end = Offset(size.width * 0.5f, size.height * 0.55f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = iconColor,
                        radius = 1.2.dp.toPx(),
                        center = Offset(size.width * 0.5f, size.height * 0.72f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isActive) accentColor else Color(0xFFFF5252))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (isActive) accentColor else Color.Gray
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenPreview() {
    PureHUBTheme {
        MainScreenContent(
            state = PureHubUiState(
                isShizukuRunning = true,
                hasShizukuPermission = true,
                isServiceRunning = true
            ),
            snackbarHostState = remember { SnackbarHostState() },
            discoveredSounds = emptyList(),
            appContext = LocalContext.current,
            onRequestShizuku = {},
            onToggleService = {},
            onForceStopMusic = {},
            onSelectSoundFile = {},
            onResetSound = {}
        )
    }
}