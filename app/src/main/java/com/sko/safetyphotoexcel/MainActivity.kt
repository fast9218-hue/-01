package com.sko.safetyphotoexcel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SafetyPhotoExcelScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyPhotoExcelScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PrefsManager(context) }

    var docTitle by remember { mutableStateOf(prefs.loadDocTitle()) }
    var teamName by remember { mutableStateOf(prefs.loadTeamName()) }
    var captionPrefix by remember { mutableStateOf(prefs.loadCaptionPrefix()) }
    var dateText by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date()))
    }
    var mailConfig by remember { mutableStateOf(prefs.loadMailConfig()) }
    var templateId by remember { mutableStateOf(prefs.loadTemplateId()) }
    var customTemplateName by remember { mutableStateOf(prefs.loadCustomTemplateName()) }
    var showTemplateDialog by remember { mutableStateOf(false) }

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
                prefs.saveCustomTemplateName(displayName)
                prefs.saveTemplateId("custom")
                customTemplateName = displayName
                templateId = "custom"
                Toast.makeText(context, "양식 적용: $displayName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "양식 불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val photos = remember { mutableStateListOf<PhotoEntry>() }
    var nextId by remember { mutableLongStateOf(1L) }

    var showMailDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

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
        if (success && uri != null) {
            addPhotoUri(uri)
        }
    }

    fun launchCamera() {
        val photoDir = File(context.cacheDir, "photos").apply { mkdirs() }
        val photoFile = File(photoDir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(context, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        uris.forEach { addPhotoUri(it) }
    }

    fun refreshCaptionsIfDefault() {
        photos.forEachIndexed { idx, item ->
            if (item.caption.startsWith(captionPrefix) || item.caption.isBlank()) {
                photos[idx] = item.copy(caption = "$captionPrefix 1-${idx + 1}")
            }
        }
    }

    fun shareExcelFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${docTitle.replace(" ", "")}_$dateText")
            if (mailConfig.recipientEmail.isNotBlank()) {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(mailConfig.recipientEmail))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "엑셀 파일 공유 / 보내기"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("엑셀 사진 자동 삽입", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showMailDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "메일 설정"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // 문서 기본 정보 카드
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = teamName,
                            onValueChange = { teamName = it },
                            label = { Text("팀명") },
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
                    OutlinedTextField(
                        value = captionPrefix,
                        onValueChange = { captionPrefix = it },
                        label = { Text("기본 내용 문구 (뒤에 1-1, 1-2 자동 추가)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val currentLabel = if (templateId == "custom" && customTemplateName != null) {
                            "내 파일 ($customTemplateName)"
                        } else {
                            ExcelGenerator.BuiltinTemplate.entries.firstOrNull { it.id == templateId }?.displayName
                                ?: "세로 2단 기본형"
                        }
                        Text(
                            text = "양식: $currentLabel",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showTemplateDialog = true }) {
                            Text("양식 선택")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 사진 추가 버튼 영역
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("갤러리 추가")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 사진 순서 리스트
            if (photos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "카메라로 찍거나 갤러리에서 사진을 추가하세요.\n위에서부터 사진 1, 사진 2, 사진 3 순서대로 들어갑니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(photos, key = { _, item -> item.id }) { index, item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    AsyncImage(
                                        model = item.uri,
                                        contentDescription = "사진 ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(bottomEnd = 8.dp)
                                    ) {
                                        Text(
                                            "사진 ${index + 1}",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                OutlinedTextField(
                                    value = item.caption,
                                    onValueChange = { newText ->
                                        photos[index] = item.copy(caption = newText)
                                    },
                                    label = { Text("내용") },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    IconButton(
                                        enabled = index > 0,
                                        onClick = {
                                            val moved = photos.removeAt(index)
                                            photos.add(index - 1, moved)
                                            refreshCaptionsIfDefault()
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "위로")
                                    }
                                    IconButton(
                                        enabled = index < photos.lastIndex,
                                        onClick = {
                                            val moved = photos.removeAt(index)
                                            photos.add(index + 1, moved)
                                            refreshCaptionsIfDefault()
                                        }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "아래로")
                                    }
                                    IconButton(
                                        onClick = {
                                            photos.removeAt(index)
                                            refreshCaptionsIfDefault()
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "삭제",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 하단 실행 버튼
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                                        templateId = templateId
                                    )
                                }
                                shareExcelFile(file)
                            } catch (e: Exception) {
                                statusMessage = "생성 오류: ${e.message}"
                            } finally {
                                isWorking = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("엑셀 공유")
                }

                Button(
                    enabled = photos.isNotEmpty() && !isWorking,
                    onClick = {
                        if (!mailConfig.isConfigured) {
                            showMailDialog = true
                            Toast.makeText(context, "먼저 메일 설정을 등록해 주세요.", Toast.LENGTH_SHORT).show()
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
                                        templateId = templateId
                                    )
                                }
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
                    modifier = Modifier.weight(1.3f)
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("생성 및 자동 발송")
                }
            }
        }
    }

    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("엑셀 양식 선택") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExcelGenerator.BuiltinTemplate.entries.forEach { tpl ->
                        val selected = (templateId == tpl.id)
                        OutlinedCard(
                            onClick = {
                                templateId = tpl.id
                                prefs.saveTemplateId(tpl.id)
                                showTemplateDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(tpl.displayName, fontWeight = FontWeight.Bold)
                                    Text(tpl.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Button(
                        onClick = {
                            showTemplateDialog = false
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
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("내 엑셀 파일(.xlsx) 불러오기")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("닫기") }
            }
        )
    }

    if (showMailDialog) {
        MailSettingsDialog(
            initial = mailConfig,
            onDismiss = { showMailDialog = false },
            onSave = { updated ->
                mailConfig = updated
                prefs.saveMailConfig(updated)
                showMailDialog = false
                Toast.makeText(context, "메일 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (statusMessage != null) {
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            title = { Text("알림") },
            text = { Text(statusMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { statusMessage = null }) {
                    Text("확인")
                }
            }
        )
    }
}

@Composable
fun MailSettingsDialog(
    initial: MailConfig,
    onDismiss: () -> Unit,
    onSave: (MailConfig) -> Unit
) {
    var host by remember { mutableStateOf(initial.smtpHost) }
    var port by remember { mutableStateOf(initial.smtpPort.toString()) }
    var useSsl by remember { mutableStateOf(initial.useSsl) }
    var sender by remember { mutableStateOf(initial.senderEmail) }
    var password by remember { mutableStateOf(initial.appPassword) }
    var recipient by remember { mutableStateOf(initial.recipientEmail) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 발송 메일 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = host == "smtp.gmail.com",
                        onClick = { host = "smtp.gmail.com"; port = "587"; useSsl = false },
                        label = { Text("Gmail") }
                    )
                    FilterChip(
                        selected = host == "smtp.naver.com",
                        onClick = { host = "smtp.naver.com"; port = "587"; useSsl = false },
                        label = { Text("Naver") }
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("SMTP 서버") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text("포트 (기본 587)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = sender,
                    onValueChange = { sender = it },
                    label = { Text("보내는 메일 주소") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("앱 비밀번호 (2단계 인증용)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text("받는 메일 주소 (PC 확인용)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    MailConfig(
                        smtpHost = host,
                        smtpPort = port.toIntOrNull() ?: 587,
                        useSsl = useSsl,
                        senderEmail = sender,
                        appPassword = password,
                        recipientEmail = recipient
                    )
                )
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}
