package com.sko.safetyphotoexcel

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PremiumColorScheme = lightColorScheme(
    primary = Color(0xFF0F2942),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E6F8),
    onPrimaryContainer = Color(0xFF0A1D30),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    background = Color(0xFFF1F5F9),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PremiumColorScheme) {
                PremiumExcelApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumExcelApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager(context) }

    var currentTab by remember { mutableIntStateOf(0) } // 0: 작업실, 1: 양식 갤러리, 2: 보관함·설정
    var isGridView by remember { mutableStateOf(false) }
    var infoExpanded by remember { mutableStateOf(false) }

    var docTitle by remember { mutableStateOf(prefs.loadDocTitle()) }
    var teamName by remember { mutableStateOf(prefs.loadTeamName()) }
    var captionPrefix by remember { mutableStateOf(prefs.loadCaptionPrefix()) }
    var dateText by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date()))
    }
    var mailConfig by remember { mutableStateOf(prefs.loadMailConfig()) }
    var templateId by remember { mutableStateOf(prefs.loadTemplateId()) }
    var customTemplateName by remember { mutableStateOf(prefs.loadCustomTemplateName()) }
    var customSlotCount by remember {
        mutableIntStateOf(
            if (ExcelGenerator.customTemplateFile(context).exists())
                ExcelGenerator.countDetectedSlots(ExcelGenerator.customTemplateFile(context))
            else 0
        )
    }
    var addWatermark by remember { mutableStateOf(prefs.loadWatermarkEnabled()) }
    var maxDimPx by remember { mutableIntStateOf(prefs.loadMaxDimensionPx()) }

    val photos = remember { mutableStateListOf<PhotoEntry>() }
    var nextId by remember { mutableLongStateOf(1L) }

    var showLivePreviewDialog by remember { mutableStateOf(false) }
    var markupTargetIndex by remember { mutableStateOf<Int?>(null) }

    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var lastGeneratedFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val templatePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                var displayName = "사용자_양식.xlsx"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) {
                        displayName = cursor.getString(nameIdx) ?: displayName
                    }
                }
                if (!displayName.lowercase().endsWith(".xlsx")) {
                    Toast.makeText(context, ".xlsx 파일만 선택 가능합니다.", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }
                val dest = ExcelGenerator.customTemplateFile(context)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val slots = ExcelGenerator.countDetectedSlots(dest)
                customSlotCount = slots
                prefs.saveCustomTemplateName(displayName)
                prefs.saveTemplateId("custom")
                customTemplateName = displayName
                templateId = "custom"
                Toast.makeText(context, "양식 적용 ($displayName · 사진칸 ${slots}개 감지)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "양식 불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addPhotoUri(uri: Uri) {
        val num = photos.size + 1
        photos.add(
            PhotoEntry(
                id = nextId++,
                uri = uri,
                caption = "$captionPrefix 1-$num"
            )
        )
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) addPhotoUri(uri)
    }

    fun launchCamera() {
        val photoDir = File(context.cacheDir, "photos").apply { mkdirs() }
        val photoFile = File(photoDir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(context, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        uris.forEach { addPhotoUri(it) }
    }

    fun applyBatchCaptions() {
        photos.forEachIndexed { idx, item ->
            photos[idx] = item.copy(caption = "$captionPrefix 1-${idx + 1}")
        }
    }

    fun saveExcelToDownloads(file: File): String {
        val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("다운로드 폴더에 파일을 생성할 수 없습니다.")
            resolver.openOutputStream(itemUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            "내 파일 > 다운로드 > ${file.name}"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    fun openExcelViewer(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "엑셀 파일 열기"))
        } catch (_: Exception) {
            Toast.makeText(context, "엑셀 뷰어 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareExcelToNaverOrChooser(file: File, preferNaver: Boolean) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val subject = "[엑셀 사진] $docTitle ($dateText)"
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, "$dateText $docTitle 엑셀 파일입니다.")
            if (mailConfig.recipientEmail.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(mailConfig.recipientEmail))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (preferNaver) {
            val naverIntent = Intent(baseIntent).apply { setPackage("com.nhn.android.mail") }
            try {
                context.startActivity(naverIntent)
                return
            } catch (_: Exception) {
                Toast.makeText(context, "네이버 메일 앱이 없어 공유 창을 엽니다.", Toast.LENGTH_SHORT).show()
            }
        }
        context.startActivity(Intent.createChooser(baseIntent, "엑셀 파일 공유 / 메일 보내기"))
    }

    val currentTemplateLabel = if (templateId == "custom" && customTemplateName != null) {
        "내 양식: $customTemplateName"
    } else {
        ExcelGenerator.BuiltinTemplate.entries.firstOrNull { it.id == templateId }?.displayName
            ?: "세로 2단 기본형"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 6.dp) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("작업실") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                    label = { Text("양식 갤러리") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.FolderSpecial, contentDescription = null) },
                    label = { Text("보관함·설정") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 상단 프리미엄 그라데이션 배너
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0F2942), Color(0xFF1E4E79), Color(0xFF0284C7))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TemplatePreviewThumbnail(templateId = templateId)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "EXCEL PHOTO STUDIO",
                                color = Color(0xFF7DD3FC),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFF38BDF8).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "PRO",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            docTitle,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$currentTemplateLabel · 사진 ${photos.size}장 등록됨",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    }
                    FilledTonalButton(
                        onClick = { showLivePreviewDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.18f),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("시트 미리보기", fontSize = 12.sp)
                    }
                }
            }

            when (currentTab) {
                0 -> {
                    // 탭 0: 작업실
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        // 문서 기본 설정 접기/펼치기 바
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { infoExpanded = !infoExpanded },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "$teamName · $dateText · 기본문구: $captionPrefix",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        if (infoExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null
                                    )
                                }

                                AnimatedVisibility(visible = infoExpanded) {
                                    Column(
                                        modifier = Modifier.padding(top = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = docTitle,
                                            onValueChange = { docTitle = it },
                                            label = { Text("문서 제목") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = teamName,
                                                onValueChange = { teamName = it },
                                                label = { Text("팀명 / 작성자") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = dateText,
                                                onValueChange = { dateText = it },
                                                label = { Text("날짜") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = captionPrefix,
                                                onValueChange = { captionPrefix = it },
                                                label = { Text("기본 내용 문구") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            FilledTonalButton(onClick = { applyBatchCaptions() }) {
                                                Text("일괄적용", fontSize = 12.sp)
                                            }
                                        }
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            val presets = listOf("현장 사진", "점검 전·후", "장비 입고", "안전보건 교육", "불량/조치")
                                            items(presets.size) { idx ->
                                                AssistChip(
                                                    onClick = {
                                                        captionPrefix = presets[idx]
                                                        applyBatchCaptions()
                                                    },
                                                    label = { Text(presets[idx], fontSize = 12.sp) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 촬영 / 갤러리 / 보기모드 툴바
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) launchCamera()
                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("카메라 촬영")
                            }

                            OutlinedButton(
                                onClick = {
                                    galleryPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Collections, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("갤러리 다중선택")
                            }

                            IconButton(
                                onClick = { isGridView = !isGridView },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                            ) {
                                Icon(
                                    if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = "보기 전환",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 사진 목록 (리스트 or 격자)
                        if (photos.isEmpty()) {
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.AddAPhoto,
                                        contentDescription = null,
                                        modifier = Modifier.size(54.dp),
                                        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "사진을 추가해 엑셀 문서를 자동 완성하세요",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "사진 위 펜 아이콘을 누르면 불량·강조 부위에 빨간 원이나 화살표를 직접 그릴 수 있습니다.",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                gridItemsIndexed(photos, key = { _, item -> item.id }) { index, item ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(110.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { markupTargetIndex = index }
                                            ) {
                                                AsyncImage(
                                                    model = item.uri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .rotate(item.rotationDegrees.toFloat())
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(bottomEnd = 8.dp)
                                                ) {
                                                    Text(
                                                        "사진 ${index + 1}",
                                                        color = Color.White,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                item.caption,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                IconButton(
                                                    onClick = { markupTargetIndex = index },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Default.Brush, contentDescription = "마킹", modifier = Modifier.size(16.dp))
                                                }
                                                IconButton(
                                                    onClick = {
                                                        photos[index] = item.copy(
                                                            rotationDegrees = (item.rotationDegrees + 90) % 360
                                                        )
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Default.RotateRight, contentDescription = "회전", modifier = Modifier.size(16.dp))
                                                }
                                                IconButton(
                                                    onClick = { photos.removeAt(index) },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "삭제",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(photos, key = { _, item -> item.id }) { index, item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(82.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable { markupTargetIndex = index }
                                            ) {
                                                AsyncImage(
                                                    model = item.uri,
                                                    contentDescription = "사진 ${index + 1}",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .rotate(item.rotationDegrees.toFloat())
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(bottomEnd = 8.dp)
                                                ) {
                                                    Text(
                                                        "사진 ${index + 1}",
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.55f),
                                                    shape = RoundedCornerShape(topStart = 6.dp),
                                                    modifier = Modifier.align(Alignment.BottomEnd)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Brush,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(11.dp)
                                                        )
                                                        Spacer(Modifier.width(2.dp))
                                                        Text("그리기", color = Color.White, fontSize = 9.sp)
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.width(10.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                OutlinedTextField(
                                                    value = item.caption,
                                                    onValueChange = { newText ->
                                                        photos[index] = item.copy(caption = newText)
                                                    },
                                                    label = { Text("내용 (사진 ${index + 1})") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    maxLines = 2
                                                )
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    AssistChip(
                                                        onClick = { markupTargetIndex = index },
                                                        label = { Text("형광펜·마킹", fontSize = 11.sp) },
                                                        leadingIcon = {
                                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                                        }
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            photos[index] = item.copy(
                                                                rotationDegrees = (item.rotationDegrees + 90) % 360
                                                            )
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.RotateRight, contentDescription = "90도 회전", modifier = Modifier.size(18.dp))
                                                    }
                                                    IconButton(
                                                        enabled = index > 0,
                                                        onClick = {
                                                            val moved = photos.removeAt(index)
                                                            photos.add(index - 1, moved)
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "위로", modifier = Modifier.size(20.dp))
                                                    }
                                                    IconButton(
                                                        enabled = index < photos.lastIndex,
                                                        onClick = {
                                                            val moved = photos.removeAt(index)
                                                            photos.add(index + 1, moved)
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "아래로", modifier = Modifier.size(20.dp))
                                                    }
                                                    Spacer(Modifier.weight(1f))
                                                    IconButton(
                                                        onClick = { photos.removeAt(index) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = "삭제",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 하단 실행 바 (폰 저장 / 네이버 메일 / 자동 발송)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    enabled = photos.isNotEmpty() && !isWorking,
                                    onClick = {
                                        prefs.saveDocDefaults(docTitle, teamName, captionPrefix)
                                        isWorking = true
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    val file = ExcelGenerator.generate(
                                                        context = context,
                                                        title = docTitle,
                                                        teamName = teamName,
                                                        dateText = dateText,
                                                        photos = photos.toList(),
                                                        outputFileName = "${docTitle.replace(" ", "")}_${dateText}.xlsx",
                                                        templateId = templateId,
                                                        addWatermark = addWatermark,
                                                        maxDimensionPx = maxDimPx
                                                    )
                                                    file to saveExcelToDownloads(file)
                                                }
                                                lastGeneratedFile = result.first
                                                statusMessage = "폰에 저장되었습니다. (경로: ${result.second})"
                                            } catch (e: Exception) {
                                                statusMessage = "저장 오류: ${e.message}"
                                            } finally {
                                                isWorking = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("폰에 저장")
                                }

                                OutlinedButton(
                                    enabled = photos.isNotEmpty() && !isWorking,
                                    onClick = {
                                        prefs.saveDocDefaults(docTitle, teamName, captionPrefix)
                                        isWorking = true
                                        scope.launch {
                                            try {
                                                val file = withContext(Dispatchers.IO) {
                                                    ExcelGenerator.generate(
                                                        context = context,
                                                        title = docTitle,
                                                        teamName = teamName,
                                                        dateText = dateText,
                                                        photos = photos.toList(),
                                                        outputFileName = "${docTitle.replace(" ", "")}_${dateText}.xlsx",
                                                        templateId = templateId,
                                                        addWatermark = addWatermark,
                                                        maxDimensionPx = maxDimPx
                                                    )
                                                }
                                                lastGeneratedFile = file
                                                shareExcelToNaverOrChooser(file, preferNaver = true)
                                            } catch (e: Exception) {
                                                statusMessage = "공유 오류: ${e.message}"
                                            } finally {
                                                isWorking = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Mail, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("네이버 메일")
                                }
                            }

                            Button(
                                enabled = photos.isNotEmpty() && !isWorking,
                                onClick = {
                                    if (!mailConfig.isConfigured) {
                                        currentTab = 2
                                        Toast.makeText(context, "보관함·설정 탭에서 메일 설정을 먼저 등록해 주세요.", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    prefs.saveDocDefaults(docTitle, teamName, captionPrefix)
                                    isWorking = true
                                    scope.launch {
                                        try {
                                            val fileName = "${docTitle.replace(" ", "")}_${dateText}.xlsx"
                                            val file = withContext(Dispatchers.IO) {
                                                ExcelGenerator.generate(
                                                    context = context,
                                                    title = docTitle,
                                                    teamName = teamName,
                                                    dateText = dateText,
                                                    photos = photos.toList(),
                                                    outputFileName = fileName,
                                                    templateId = templateId,
                                                    addWatermark = addWatermark,
                                                    maxDimensionPx = maxDimPx
                                                )
                                            }
                                            lastGeneratedFile = file
                                            EmailSender.sendExcelAttachment(
                                                config = mailConfig,
                                                subject = "[엑셀 사진 첨부] $docTitle ($dateText)",
                                                bodyText = "$dateText $docTitle 엑셀 파일(총 ${photos.size}장)을 첨부합니다.",
                                                excelFile = file
                                            )
                                            statusMessage = "${mailConfig.recipientEmail} 로 메일 발송을 완료했습니다."
                                        } catch (e: Exception) {
                                            statusMessage = "발송 실패: ${e.message}"
                                        } finally {
                                            isWorking = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isWorking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(Icons.Default.Send, contentDescription = null)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("엑셀 생성 및 메일 자동 발송")
                            }
                        }
                    }
                }

                1 -> {
                    // 탭 1: 양식 갤러리
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("내장 범용 엑셀 양식 (4종)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        ExcelGenerator.BuiltinTemplate.entries.forEach { tpl ->
                            val selected = (templateId == tpl.id)
                            Card(
                                onClick = {
                                    templateId = tpl.id
                                    prefs.saveTemplateId(tpl.id)
                                    Toast.makeText(context, "${tpl.displayName} 양식이 선택되었습니다.", Toast.LENGTH_SHORT).show()
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selected) Color(0xFFE0F2FE) else Color.White
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TemplatePreviewThumbnail(tpl.id)
                                    Spacer(Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tpl.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                            if (selected) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "사용 중",
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(tpl.description, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                                    }
                                    RadioButton(selected = selected, onClick = null)
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text("사용자 지정 엑셀 양식 (.xlsx)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (templateId == "custom") Color(0xFFE0F2FE) else Color.White
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "직접 만든 엑셀 파일 안에 '사진1', '사진2', '사진3'… 텍스트를 넣어두면 해당 셀(또는 병합된 표 영역)을 자동으로 찾아 사진을 삽입합니다.",
                                    fontSize = 12.sp,
                                    color = Color.DarkGray
                                )
                                if (customTemplateName != null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(customTemplateName ?: "", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Text("자동 감지된 사진 영역: ${customSlotCount}개 (초과 시 자동 하단 확장)", fontSize = 11.sp)
                                            }
                                            if (templateId != "custom") {
                                                TextButton(onClick = {
                                                    templateId = "custom"
                                                    prefs.saveTemplateId("custom")
                                                }) {
                                                    Text("선택")
                                                }
                                            }
                                        }
                                    }
                                }
                                Button(
                                    onClick = {
                                        templatePickerLauncher.launch(
                                            arrayOf(
                                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                "application/octet-stream",
                                                "*/*"
                                            )
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("내 폰에서 엑셀 양식(.xlsx) 선택하기")
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // 탭 2: 보관함 · 설정
                    val excelDir = File(context.cacheDir, "excel")
                    var refreshTrigger by remember { mutableIntStateOf(0) }
                    val recentFiles = remember(currentTab, refreshTrigger) {
                        excelDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(15) ?: emptyList()
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 화질 및 워터마크 옵션 카드
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("사진 품질 · 워터마크 설정", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("사진 우측 하단 날짜·팀명 워터마크", modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    Switch(
                                        checked = addWatermark,
                                        onCheckedChange = {
                                            addWatermark = it
                                            prefs.saveWatermarkEnabled(it)
                                        }
                                    )
                                }
                                Text("엑셀 삽입 해상도 (용량 조절)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        1200 to "경량 (빠름)",
                                        1600 to "표준 (권장)",
                                        2000 to "고화질 (선명)"
                                    ).forEach { (px, label) ->
                                        FilterChip(
                                            selected = maxDimPx == px,
                                            onClick = {
                                                maxDimPx = px
                                                prefs.saveMaxDimensionPx(px)
                                            },
                                            label = { Text(label, fontSize = 12.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        // SMTP 자동 메일 발송 설정 카드
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            var host by remember(mailConfig) { mutableStateOf(mailConfig.smtpHost) }
                            var port by remember(mailConfig) { mutableStateOf(mailConfig.smtpPort.toString()) }
                            var sender by remember(mailConfig) { mutableStateOf(mailConfig.senderEmail) }
                            var password by remember(mailConfig) { mutableStateOf(mailConfig.appPassword) }
                            var recipient by remember(mailConfig) { mutableStateOf(mailConfig.recipientEmail) }

                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("자동 발송 메일(SMTP) 계정 설정", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = host == "smtp.gmail.com",
                                        onClick = { host = "smtp.gmail.com"; port = "587" },
                                        label = { Text("Gmail (smtp.gmail.com)") }
                                    )
                                    FilterChip(
                                        selected = host == "smtp.naver.com",
                                        onClick = { host = "smtp.naver.com"; port = "587" },
                                        label = { Text("Naver (smtp.naver.com)") }
                                    )
                                }
                                OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text("보내는 메일 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("앱 비밀번호 (구글 16자리 / 네이버 SMTP 비밀번호)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = recipient, onValueChange = { recipient = it }, label = { Text("받는 메일 주소 (PC 수신용)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Button(
                                    onClick = {
                                        val updated = MailConfig(
                                            smtpHost = host,
                                            smtpPort = port.toIntOrNull() ?: 587,
                                            useSsl = false,
                                            senderEmail = sender,
                                            appPassword = password,
                                            recipientEmail = recipient
                                        )
                                        mailConfig = updated
                                        prefs.saveMailConfig(updated)
                                        Toast.makeText(context, "메일 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("메일 설정 저장")
                                }
                            }
                        }

                        // 최근 생성된 엑셀 파일 보관함
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("최근 생성한 엑셀 보관함 (${recentFiles.size}개)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (recentFiles.isEmpty()) {
                                    Text("아직 생성된 엑셀 파일이 없습니다.", fontSize = 12.sp, color = Color.Gray)
                                } else {
                                    recentFiles.forEach { rf ->
                                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(rf.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    Text("${rf.length() / 1024} KB", fontSize = 11.sp, color = Color.Gray)
                                                }
                                                IconButton(onClick = { openExcelViewer(rf) }) {
                                                    Icon(Icons.Default.OpenInNew, contentDescription = "열기")
                                                }
                                                IconButton(onClick = { shareExcelToNaverOrChooser(rf, preferNaver = true) }) {
                                                    Icon(Icons.Default.Mail, contentDescription = "네이버 메일")
                                                }
                                                IconButton(onClick = {
                                                    rf.delete()
                                                    refreshTrigger++
                                                }) {
                                                    Icon(Icons.Default.Delete, contentDescription = "삭제", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 실시간 엑셀 시트 미리보기 모달
    if (showLivePreviewDialog) {
        LiveSheetPreviewDialog(
            title = docTitle,
            teamName = teamName,
            dateText = dateText,
            templateId = templateId,
            photos = photos,
            onDismiss = { showLivePreviewDialog = false }
        )
    }

    // 사진 형광펜 / 마킹 에디터 모달
    if (markupTargetIndex != null && markupTargetIndex!! in photos.indices) {
        val idx = markupTargetIndex!!
        val target = photos[idx]
        PhotoMarkupDialog(
            photo = target,
            onDismiss = { markupTargetIndex = null },
            onSaveAnnotated = { newUri ->
                photos[idx] = target.copy(uri = newUri, rotationDegrees = 0)
                markupTargetIndex = null
                Toast.makeText(context, "마킹이 사진에 적용되었습니다.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (statusMessage != null) {
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("완료 안내") },
            text = { Text(statusMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) { Text("확인") }
            },
            dismissButton = if (lastGeneratedFile != null) {
                {
                    TextButton(onClick = {
                        lastGeneratedFile?.let { openExcelViewer(it) }
                        statusMessage = null
                    }) { Text("엑셀 바로 열기") }
                }
            } else null
        )
    }
}

@Composable
fun LiveSheetPreviewDialog(
    title: String,
    teamName: String,
    dateText: String,
    templateId: String,
    photos: List<PhotoEntry>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFFF8FAFC)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("실시간 엑셀 시트 미리보기", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기")
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 종이 시트 컨테이너
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF94A3B8), RoundedCornerShape(6.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 시트 헤더
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE2E8F0))
                            .border(1.dp, Color(0xFF475569))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF475569))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("팀명: $teamName", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(dateText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (photos.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .border(1.dp, Color(0xFF94A3B8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("사진을 추가하면 실제 배치 모양이 여기에 표시됩니다.", fontSize = 12.sp, color = Color.Gray)
                        }
                    } else {
                        when (templateId) {
                            "grid_2x2", "before_after" -> {
                                photos.chunked(2).forEachIndexed { rowIdx, pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        pair.forEachIndexed { cIdx, p ->
                                            val num = rowIdx * 2 + cIdx + 1
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .border(1.dp, Color(0xFF475569))
                                            ) {
                                                AsyncImage(
                                                    model = p.uri,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(130.dp)
                                                        .background(Color(0xFFF8FAFC))
                                                        .rotate(p.rotationDegrees.toFloat())
                                                )
                                                Text(
                                                    p.caption.ifBlank { "사진 $num" },
                                                    fontSize = 11.sp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(Color(0xFFF1F5F9))
                                                        .padding(4.dp)
                                                )
                                            }
                                        }
                                        if (pair.size == 1) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                            "list_row" -> {
                                photos.forEachIndexed { idx, p ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(110.dp)
                                            .border(1.dp, Color(0xFF475569))
                                    ) {
                                        AsyncImage(
                                            model = p.uri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .weight(0.45f)
                                                .fillMaxHeight()
                                                .background(Color(0xFFF8FAFC))
                                                .rotate(p.rotationDegrees.toFloat())
                                        )
                                        Box(
                                            modifier = Modifier
                                                .weight(0.55f)
                                                .fillMaxHeight()
                                                .padding(8.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Text(p.caption.ifBlank { "사진 ${idx + 1}" }, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            else -> {
                                photos.forEachIndexed { idx, p ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color(0xFF475569))
                                    ) {
                                        AsyncImage(
                                            model = p.uri,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .background(Color(0xFFF8FAFC))
                                                .rotate(p.rotationDegrees.toFloat())
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color(0xFFF1F5F9))
                                                .padding(6.dp)
                                        ) {
                                            Text("내 용", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                                            Text(p.caption.ifBlank { "사진 ${idx + 1}" }, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoMarkupDialog(
    photo: PhotoEntry,
    onDismiss: () -> Unit,
    onSaveAnnotated: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedColor by remember { mutableStateOf(Color(0xFFEF4444)) } // 기본 빨간색
    val strokes = remember { mutableStateListOf<NormalizedStroke>() }
    var currentPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "사진 형광펜 · 마킹 편집",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = strokes.isNotEmpty(),
                        onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "실행취소", tint = Color.White)
                    }
                    IconButton(
                        enabled = strokes.isNotEmpty(),
                        onClick = { strokes.clear() }
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "모두지우기", tint = Color.White)
                    }
                }

                // 색상 선택 바
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("펜 색상:", color = Color.White, fontSize = 12.sp)
                    listOf(
                        Color(0xFFEF4444) to android.graphics.Color.rgb(239, 68, 68),
                        Color(0xFFFACC15) to android.graphics.Color.rgb(250, 204, 21),
                        Color(0xFF22C55E) to android.graphics.Color.rgb(34, 197, 94),
                        Color(0xFF38BDF8) to android.graphics.Color.rgb(56, 189, 248)
                    ).forEach { (composeColor, _) ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .border(
                                    width = if (selectedColor == composeColor) 3.dp else 1.dp,
                                    color = if (selectedColor == composeColor) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = composeColor }
                        )
                    }
                }

                // 캔버스 + 이미지 영역
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .onSizeChanged { boxSize = it }
                        .pointerInput(selectedColor, boxSize) {
                            if (boxSize.width <= 0 || boxSize.height <= 0) return@pointerInput
                            detectDragGestures(
                                onDragStart = { offset ->
                                    currentPoints = listOf(
                                        (offset.x / boxSize.width).coerceIn(0f, 1f) to
                                            (offset.y / boxSize.height).coerceIn(0f, 1f)
                                    )
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val pt = (change.position.x / boxSize.width).coerceIn(0f, 1f) to
                                        (change.position.y / boxSize.height).coerceIn(0f, 1f)
                                    currentPoints = currentPoints + pt
                                },
                                onDragEnd = {
                                    if (currentPoints.size >= 2) {
                                        val argb = android.graphics.Color.argb(
                                            255,
                                            (selectedColor.red * 255).toInt(),
                                            (selectedColor.green * 255).toInt(),
                                            (selectedColor.blue * 255).toInt()
                                        )
                                        strokes.add(NormalizedStroke(currentPoints, argb))
                                    }
                                    currentPoints = emptyList()
                                }
                            )
                        }
                ) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (s in strokes) {
                            if (s.points.size < 2) continue
                            val path = Path()
                            val first = s.points.first()
                            path.moveTo(first.first * size.width, first.second * size.height)
                            for (i in 1 until s.points.size) {
                                val pt = s.points[i]
                                path.lineTo(pt.first * size.width, pt.second * size.height)
                            }
                            drawPath(
                                path = path,
                                color = Color(s.colorArgb),
                                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        if (currentPoints.size >= 2) {
                            val path = Path()
                            val first = currentPoints.first()
                            path.moveTo(first.first * size.width, first.second * size.height)
                            for (i in 1 until currentPoints.size) {
                                val pt = currentPoints[i]
                                path.lineTo(pt.first * size.width, pt.second * size.height)
                            }
                            drawPath(
                                path = path,
                                color = selectedColor,
                                style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("취소")
                    }
                    Button(
                        enabled = !saving && strokes.isNotEmpty(),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val newUri = withContext(Dispatchers.IO) {
                                        ImageUtils.saveAnnotatedCopy(context, photo.uri, strokes.toList())
                                    }
                                    onSaveAnnotated(newUri)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "마킹 저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("마킹 적용 저장")
                    }
                }
            }
        }
    }
}

@Composable
fun TemplatePreviewThumbnail(templateId: String) {
    val lineColor = Color(0xFF475569)
    val headerBg = Color(0xFFBAE6FD)
    val photoBg = Color(0xFFF1F5F9)
    val isLandscape = (templateId == "before_after")
    val boxW = if (isLandscape) 60.dp else 46.dp
    val boxH = if (isLandscape) 44.dp else 60.dp

    Column(
        modifier = Modifier
            .size(width = boxW, height = boxH)
            .background(Color.White, RoundedCornerShape(4.dp))
            .border(1.dp, lineColor, RoundedCornerShape(4.dp))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(headerBg)
                .border(0.5.dp, lineColor)
        )
        when (templateId) {
            "vertical_2" -> {
                repeat(2) { idx ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(photoBg)
                            .border(0.5.dp, lineColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${idx + 1}", fontSize = 8.sp, color = lineColor, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .border(0.5.dp, lineColor)
                    )
                }
            }
            "grid_2x2" -> {
                repeat(2) { rowIdx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(2) { colIdx ->
                            val num = rowIdx * 2 + colIdx + 1
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(photoBg)
                                        .border(0.5.dp, lineColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$num", fontSize = 7.sp, color = lineColor, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .border(0.5.dp, lineColor)
                                )
                            }
                        }
                    }
                }
            }
            "before_after" -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("전", "후").forEach { label ->
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(photoBg)
                                    .border(0.5.dp, lineColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, fontSize = 8.sp, color = lineColor, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .border(0.5.dp, lineColor)
                            )
                        }
                    }
                }
            }
            else -> {
                repeat(3) { idx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                                .background(photoBg)
                                .border(0.5.dp, lineColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${idx + 1}", fontSize = 7.sp, color = lineColor, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                                .border(0.5.dp, lineColor)
                        )
                    }
                }
            }
        }
    }
}
