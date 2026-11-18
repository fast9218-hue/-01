package com.sko.safetyphotoexcel

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.ClientAnchor
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.PrintSetup
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.RegionUtil
import org.apache.poi.util.Units
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class PhotoEntry(
    val id: Long,
    val uri: Uri,
    val caption: String,
    val rotationDegrees: Int = 0
)

object ExcelGenerator {

    private data class PhotoSlot(
        val index: Int,
        val sheetIndex: Int,
        val startRow: Int,
        val endRow: Int,
        val startCol: Int,
        val endCol: Int,
        val labelRow: Int,
        val labelCol: Int
    )

    private val photoTagRegex = Regex("""^사진\s*(\d+)$""")
    private val captionTagRegex = Regex("""^내용\s*(\d+)$""")

    fun extractTemplateTitle(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                val wb = XSSFWorkbook(fis)
                val sheet = wb.getSheetAt(0)
                val cell = sheet?.getRow(0)?.getCell(0)
                val raw = if (cell != null && cell.cellType == CellType.STRING) {
                    cell.stringCellValue?.trim()
                } else null
                wb.close()
                if (!raw.isNullOrBlank()) raw else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun countDetectedSlots(file: File): Int {
        return try {
            FileInputStream(file).use { fis ->
                val wb = XSSFWorkbook(fis)
                val count = findPhotoSlots(wb).size
                wb.close()
                count
            }
        } catch (_: Exception) {
            0
        }
    }

    fun customTemplateFile(context: Context): File =
        File(context.filesDir, "custom_template.xlsx")

    enum class BuiltinTemplate(
        val id: String,
        val displayName: String,
        val description: String,
        val assetFile: String
    ) {
        VERTICAL_2("vertical_2", "세로 2단 기본형", "큰 사진 위아래 2장 + 1페이지 출력 최적화 (A4 세로)", "template_vertical_2.xlsx"),
        GRID_2X2("grid_2x2", "2x2 격자형 (4장)", "한 페이지에 4장 바둑판 배치 (A4 세로)", "template_grid_2x2.xlsx"),
        BEFORE_AFTER("before_after", "작업 전·후 비교형", "좌측 작업 전 / 우측 작업 후 비교 (A4 가로)", "template_before_after.xlsx"),
        LIST_ROW("list_row", "목록형 (좌 사진·우 설명)", "사진과 상세 설명을 좌우로 나란히 배치 (A4 세로)", "template_list_row.xlsx")
    }

    fun generate(
        context: Context,
        title: String,
        teamName: String,
        dateText: String,
        photos: List<PhotoEntry>,
        outputFileName: String,
        templateId: String = "vertical_2",
        addWatermark: Boolean = false,
        maxDimensionPx: Int = 1600
    ): File {
        require(photos.isNotEmpty()) { "최소 1장 이상의 사진이 필요합니다." }

        val customFile = customTemplateFile(context)
        val isCustom = (templateId == "custom" && customFile.exists())
        val builtin = BuiltinTemplate.entries.firstOrNull { it.id == templateId } ?: BuiltinTemplate.VERTICAL_2

        val workbook = if (isCustom) {
            FileInputStream(customFile).use { XSSFWorkbook(it) }
        } else {
            context.assets.open(builtin.assetFile).use { XSSFWorkbook(it) }
        }

        // 불필요한 시트 외곽 쓰레기 값(예: K15 'ㄹ') 정리
        cleanStrayCellsOutsideTable(workbook.getSheetAt(0), maxAllowedCol = 9)

        // 모든 시트에서 '사진1', '사진2', '사진3'... 셀(및 병합 범위) 자동 탐색
        val detectedSlots = findPhotoSlots(workbook).toMutableList()
        val captionPlaceholders = findCaptionPlaceholders(workbook)

        // 제목, 팀명, 날짜 업데이트
        val sheet0 = workbook.getSheetAt(0)
        sheet0.getRow(0)?.getCell(0)?.setCellValue(title)
        updateTeamCellIfPresent(workbook, teamName)
        updateDateCellIfPresent(workbook, dateText)

        // 양식에 있는 사진 칸보다 사진이 더 많으면 첫 시트 하단에 자동 확장
        if (detectedSlots.isNotEmpty() && detectedSlots.size < photos.size) {
            val lastSheet = workbook.getSheetAt(detectedSlots.last().sheetIndex)
            while (detectedSlots.size < photos.size) {
                val nextIdx = detectedSlots.size + 1
                val lastBottom = (lastSheet.lastRowNum).coerceAtLeast(detectedSlots.last().endRow + 2)
                val photoStart = lastBottom + 1
                val photoEnd = photoStart + 25
                val capStart = photoEnd + 1
                val capEnd = capStart + 1
                createPhotoBlock(workbook, lastSheet, nextIdx, photoStart, photoEnd, capStart, capEnd)
                detectedSlots.add(
                    PhotoSlot(
                        index = nextIdx,
                        sheetIndex = detectedSlots.last().sheetIndex,
                        startRow = photoStart,
                        endRow = photoEnd,
                        startCol = 0,
                        endCol = 8,
                        labelRow = photoStart,
                        labelCol = 0
                    )
                )
            }
        }

        require(detectedSlots.isNotEmpty()) {
            "엑셀 파일에서 '사진1', '사진2' 등의 표시 셀을 찾지 못했습니다."
        }

        // 업로드된 양식(업로드 양식.xlsx 등)도 고급 보고서 스타일로 자동 꾸미기
        beautifySheetHeaderAndBlocks(workbook, sheet0, detectedSlots)

        // 사용하지 않는 빈 슬롯의 '사진N', '내용N' 플레이스홀더 텍스트 정리
        for (i in photos.size until detectedSlots.size) {
            val unused = detectedSlots[i]
            val s = workbook.getSheetAt(unused.sheetIndex)
            s.getRow(unused.labelRow)?.getCell(unused.labelCol)?.setCellValue("")
            val capLoc = captionPlaceholders[unused.index]
            if (capLoc != null) {
                workbook.getSheetAt(capLoc.first).getRow(capLoc.second)?.getCell(capLoc.third)?.setCellValue("")
            } else {
                writeCaptionBelowSlotIfPresent(s, unused, "")
            }
        }

        // 사진 삽입 및 내용 반영
        photos.forEachIndexed { idx, photo ->
            if (idx >= detectedSlots.size) return@forEachIndexed
            val slot = detectedSlots[idx]
            val sheet = workbook.getSheetAt(slot.sheetIndex)

            sheet.getRow(slot.labelRow)?.getCell(slot.labelCol)?.setCellValue("")

            val explicitCap = captionPlaceholders[slot.index]
            if (explicitCap != null) {
                val capSheet = workbook.getSheetAt(explicitCap.first)
                capSheet.getRow(explicitCap.second)?.getCell(explicitCap.third)?.setCellValue(photo.caption)
            } else {
                writeCaptionBelowSlotIfPresent(sheet, slot, photo.caption)
            }

            val wm = if (addWatermark) "$dateText · $teamName" else null
            val processed = ImageUtils.loadNormalizedJpeg(
                context = context,
                uri = photo.uri,
                extraRotationDegrees = photo.rotationDegrees,
                watermarkText = wm,
                maxDimensionPx = maxDimensionPx
            )
            insertCenteredPicture(
                workbook = workbook,
                sheet = sheet,
                imageBytes = processed.bytes,
                imgWidthPx = processed.widthPx,
                imgHeightPx = processed.heightPx,
                startRow = slot.startRow,
                endRow = slot.endRow,
                startCol = slot.startCol,
                endCol = slot.endCol
            )
        }

        // 모든 시트에 1페이지 출력 설정 강제 적용
        val maxColIndex = detectedSlots.maxOfOrNull { it.endCol } ?: 8
        val maxRowIndex = (detectedSlots.maxOfOrNull { it.endRow + 2 } ?: sheet0.lastRowNum).coerceAtMost(sheet0.lastRowNum)
        val isLandscape = (templateId == "before_after")
        configureOnePagePrint(workbook, sheet0, 0, maxColIndex, maxRowIndex, isLandscape)

        val outDir = File(context.cacheDir, "excel").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)
        FileOutputStream(outFile).use { fos ->
            workbook.write(fos)
        }
        workbook.close()
        return outFile
    }

    private fun cleanStrayCellsOutsideTable(sheet: XSSFSheet, maxAllowedCol: Int) {
        for (r in 0..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            for (c in (maxAllowedCol + 1) until row.lastCellNum.toInt().coerceAtLeast(0)) {
                row.getCell(c)?.let { row.removeCell(it) }
            }
        }
    }

    private fun beautifySheetHeaderAndBlocks(
        workbook: XSSFWorkbook,
        sheet: XSSFSheet,
        slots: List<PhotoSlot>
    ) {
        val navyDark = XSSFColor(byteArrayOf(0x1B.toByte(), 0x36.toByte(), 0x5D.toByte()), null)
        val subBg = XSSFColor(byteArrayOf(0xE8.toByte(), 0xEE.toByte(), 0xF5.toByte()), null)
        val labelBg = XSSFColor(byteArrayOf(0xD9.toByte(), 0xE2.toByte(), 0xEC.toByte()), null)
        val capBg = XSSFColor(byteArrayOf(0xF8.toByte(), 0xFA.toByte(), 0xFC.toByte()), null)

        // 1) 상단 A1 제목 배너 스타일링
        val titleMerged = sheet.mergedRegions.firstOrNull { it.isInRange(0, 0) }
        if (titleMerged != null) {
            val titleFont = workbook.createFont().apply {
                fontName = "맑은 고딕"
                fontHeightInPoints = 22
                bold = true
                color = IndexedColors.WHITE.index
            }
            val titleStyle = workbook.createCellStyle().apply {
                setFont(titleFont)
                alignment = HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                fillPattern = FillPatternType.SOLID_FOREGROUND
                setFillForegroundColor(navyDark)
            }
            sheet.getRow(0)?.let { row0 ->
                if (row0.heightInPoints < 42f) row0.heightInPoints = 45f
                row0.getCell(0)?.cellStyle = titleStyle
            }
            applyBoxBorder(titleMerged, sheet, BorderStyle.MEDIUM)
        }

        // 2) 2~3행 팀명 / 날짜 바 스타일링
        val subFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 12
            bold = true
        }
        sheet.mergedRegions.filter { it.firstRow == 1 && it.lastRow <= 2 }.forEach { reg ->
            val isLeft = (reg.firstColumn == 0)
            val st = workbook.createCellStyle().apply {
                setFont(subFont)
                alignment = if (isLeft) HorizontalAlignment.LEFT else HorizontalAlignment.CENTER
                verticalAlignment = VerticalAlignment.CENTER
                fillPattern = FillPatternType.SOLID_FOREGROUND
                setFillForegroundColor(subBg)
            }
            for (r in reg.firstRow..reg.lastRow) {
                val row = sheet.getRow(r) ?: sheet.createRow(r)
                for (c in reg.firstColumn..reg.lastColumn) {
                    val cell = row.getCell(c) ?: row.createCell(c)
                    cell.cellStyle = st
                }
            }
            applyBoxBorder(reg, sheet, BorderStyle.MEDIUM)
        }

        // 3) 각 사진 박스 및 아래 내용 박스 테두리/배경 정리
        val labelFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 12
            bold = true
        }
        val capFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 12
            bold = true
        }
        val labelStyle = workbook.createCellStyle().apply {
            setFont(labelFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(labelBg)
        }
        val capStyle = workbook.createCellStyle().apply {
            setFont(capFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(capBg)
        }

        slots.filter { it.sheetIndex == 0 }.forEach { slot ->
            val photoReg = CellRangeAddress(slot.startRow, slot.endRow, slot.startCol, slot.endCol)
            applyBoxBorder(photoReg, sheet, BorderStyle.MEDIUM)

            // 바로 아래 행에 '내 용' 병합 셀이 있으면 스타일 입히기
            val capRowIdx = slot.endRow + 1
            val labelReg = sheet.mergedRegions.firstOrNull { it.isInRange(capRowIdx, slot.startCol) }
            if (labelReg != null) {
                sheet.getRow(labelReg.firstRow)?.getCell(labelReg.firstColumn)?.cellStyle = labelStyle
                applyBoxBorder(labelReg, sheet, BorderStyle.MEDIUM)

                val textCol = labelReg.lastColumn + 1
                val textReg = sheet.mergedRegions.firstOrNull { it.isInRange(capRowIdx, textCol) }
                if (textReg != null) {
                    sheet.getRow(textReg.firstRow)?.getCell(textReg.firstColumn)?.cellStyle = capStyle
                    applyBoxBorder(textReg, sheet, BorderStyle.MEDIUM)
                }
            }
        }
    }

    private fun configureOnePagePrint(
        workbook: XSSFWorkbook,
        sheet: XSSFSheet,
        sheetIdx: Int,
        maxCol: Int,
        maxRow: Int,
        landscape: Boolean
    ) {
        workbook.setPrintArea(sheetIdx, 0, maxCol, 0, maxRow)
        sheet.autobreaks = true
        sheet.fitToPage = true
        sheet.isPrintGridlines = false
        sheet.isDisplayGridlines = true
        sheet.horizontallyCenter = true
        sheet.verticallyCenter = true

        sheet.setMargin(Sheet.LeftMargin, 0.35)
        sheet.setMargin(Sheet.RightMargin, 0.35)
        sheet.setMargin(Sheet.TopMargin, 0.4)
        sheet.setMargin(Sheet.BottomMargin, 0.4)

        sheet.printSetup.apply {
            paperSize = PrintSetup.A4_PAPERSIZE
            this.landscape = landscape
            fitWidth = 1
            fitHeight = 1
        }
    }

    private fun findPhotoSlots(workbook: XSSFWorkbook): List<PhotoSlot> {
        val result = mutableListOf<PhotoSlot>()
        for (sIdx in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(sIdx)
            for (r in 0..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                for (c in 0 until row.lastCellNum) {
                    val cell = row.getCell(c) ?: continue
                    if (cell.cellType != CellType.STRING) continue
                    val text = cell.stringCellValue.trim()
                    val match = photoTagRegex.matchEntire(text) ?: continue
                    val num = match.groupValues[1].toIntOrNull() ?: continue

                    val merged = sheet.mergedRegions.firstOrNull {
                        it.isInRange(r, c)
                    }
                    result.add(
                        PhotoSlot(
                            index = num,
                            sheetIndex = sIdx,
                            startRow = merged?.firstRow ?: r,
                            endRow = merged?.lastRow ?: r,
                            startCol = merged?.firstColumn ?: c,
                            endCol = merged?.lastColumn ?: c,
                            labelRow = r,
                            labelCol = c
                        )
                    )
                }
            }
        }
        return result.sortedBy { it.index }
    }

    private fun findCaptionPlaceholders(workbook: XSSFWorkbook): Map<Int, Triple<Int, Int, Int>> {
        val map = mutableMapOf<Int, Triple<Int, Int, Int>>()
        for (sIdx in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(sIdx)
            for (r in 0..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                for (c in 0 until row.lastCellNum) {
                    val cell = row.getCell(c) ?: continue
                    if (cell.cellType != CellType.STRING) continue
                    val match = captionTagRegex.matchEntire(cell.stringCellValue.trim()) ?: continue
                    val num = match.groupValues[1].toIntOrNull() ?: continue
                    map[num] = Triple(sIdx, r, c)
                }
            }
        }
        return map
    }

    private fun writeCaptionBelowSlotIfPresent(sheet: XSSFSheet, slot: PhotoSlot, caption: String) {
        val checkRow = sheet.getRow(slot.endRow + 1) ?: return
        val firstCell = checkRow.getCell(slot.startCol) ?: return
        if (firstCell.cellType == CellType.STRING) {
            val label = firstCell.stringCellValue.replace(" ", "")
            if (label == "내용" || label.startsWith("NO.")) {
                val mergedLabel = sheet.mergedRegions.firstOrNull {
                    it.isInRange(slot.endRow + 1, slot.startCol)
                }
                val targetCol = (mergedLabel?.lastColumn ?: slot.startCol) + 1
                val targetCell = checkRow.getCell(targetCol) ?: checkRow.createCell(targetCol)
                targetCell.setCellValue(caption)
            }
        }
    }

    private fun updateDateCellIfPresent(workbook: XSSFWorkbook, dateText: String) {
        val sheet = workbook.getSheetAt(0)
        val row1 = sheet.getRow(1) ?: return
        for (c in 0 until row1.lastCellNum) {
            val cell = row1.getCell(c) ?: continue
            if (cell.cellType == CellType.STRING && cell.stringCellValue.trim().startsWith("202")) {
                cell.setCellValue(dateText)
                return
            } else if (cell.cellType == CellType.NUMERIC && c >= 5) {
                cell.setCellValue(dateText)
                return
            }
        }
    }

    private fun updateTeamCellIfPresent(workbook: XSSFWorkbook, teamName: String) {
        for (sIdx in 0 until workbook.numberOfSheets) {
            val sheet = workbook.getSheetAt(sIdx)
            for (r in 0..minOf(10, sheet.lastRowNum)) {
                val row = sheet.getRow(r) ?: continue
                for (c in 0 until row.lastCellNum) {
                    val cell = row.getCell(c) ?: continue
                    if (cell.cellType == CellType.STRING && cell.stringCellValue.trim().startsWith("팀명:")) {
                        cell.setCellValue("팀명: $teamName")
                        return
                    }
                }
            }
        }
    }

    private fun createPhotoBlock(
        workbook: XSSFWorkbook,
        sheet: XSSFSheet,
        photoIndex: Int,
        photoStart: Int,
        photoEnd: Int,
        capStart: Int,
        capEnd: Int
    ) {
        val labelBg = XSSFColor(byteArrayOf(0xD9.toByte(), 0xE2.toByte(), 0xEC.toByte()), null)
        val capBg = XSSFColor(byteArrayOf(0xF8.toByte(), 0xFA.toByte(), 0xFC.toByte()), null)

        val labelFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 12
            bold = true
        }
        val captionFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 12
            bold = true
        }
        val labelStyle = workbook.createCellStyle().apply {
            setFont(labelFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(labelBg)
        }
        val captionStyle = workbook.createCellStyle().apply {
            setFont(captionFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
            fillPattern = FillPatternType.SOLID_FOREGROUND
            setFillForegroundColor(capBg)
        }

        for (r in photoStart..capEnd) {
            val row = sheet.getRow(r) ?: sheet.createRow(r)
            row.heightInPoints = if (r == capStart || r == capEnd) 19f else 13.5f
            for (c in 0..8) {
                if (row.getCell(c) == null) row.createCell(c)
            }
        }

        val photoRegion = CellRangeAddress(photoStart, photoEnd, 0, 8)
        sheet.addMergedRegion(photoRegion)
        sheet.getRow(photoStart).getCell(0).setCellValue("사진$photoIndex")
        applyBoxBorder(photoRegion, sheet, BorderStyle.MEDIUM)

        val labelRegion = CellRangeAddress(capStart, capEnd, 0, 1)
        sheet.addMergedRegion(labelRegion)
        sheet.getRow(capStart).getCell(0).apply {
            setCellValue("내  용")
            cellStyle = labelStyle
        }
        applyBoxBorder(labelRegion, sheet, BorderStyle.MEDIUM)

        val capRegion = CellRangeAddress(capStart, capEnd, 2, 8)
        sheet.addMergedRegion(capRegion)
        sheet.getRow(capStart).getCell(2).cellStyle = captionStyle
        applyBoxBorder(capRegion, sheet, BorderStyle.MEDIUM)
    }

    private fun applyBoxBorder(
        region: CellRangeAddress,
        sheet: XSSFSheet,
        style: BorderStyle = BorderStyle.THIN
    ) {
        RegionUtil.setBorderTop(style, region, sheet)
        RegionUtil.setBorderBottom(style, region, sheet)
        RegionUtil.setBorderLeft(style, region, sheet)
        RegionUtil.setBorderRight(style, region, sheet)
    }

    private fun insertCenteredPicture(
        workbook: XSSFWorkbook,
        sheet: XSSFSheet,
        imageBytes: ByteArray,
        imgWidthPx: Int,
        imgHeightPx: Int,
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int
    ) {
        val pictureIdx = workbook.addPicture(imageBytes, Workbook.PICTURE_TYPE_JPEG)
        val drawing = sheet.createDrawingPatriarch()

        val colWidthsPx = (startCol..endCol).map { sheet.getColumnWidthInPixels(it).toDouble() }
        val rowHeightsPt = (startRow..endRow).map {
            val row = sheet.getRow(it)
            val h = row?.heightInPoints ?: sheet.defaultRowHeightInPoints
            h.toDouble()
        }

        val totalBoxWidthEmu = colWidthsPx.sum() * Units.EMU_PER_PIXEL
        val totalBoxHeightEmu = rowHeightsPt.sum() * Units.EMU_PER_POINT

        val availW = totalBoxWidthEmu * 0.94
        val availH = totalBoxHeightEmu * 0.94

        val imgRatio = imgWidthPx.toDouble() / imgHeightPx.toDouble()
        val boxRatio = availW / availH

        val drawW: Double
        val drawH: Double
        if (imgRatio > boxRatio) {
            drawW = availW
            drawH = availW / imgRatio
        } else {
            drawH = availH
            drawW = availH * imgRatio
        }

        val leftOffsetEmu = (totalBoxWidthEmu - drawW) / 2.0
        val topOffsetEmu = (totalBoxHeightEmu - drawH) / 2.0
        val rightEndEmu = leftOffsetEmu + drawW
        val bottomEndEmu = topOffsetEmu + drawH

        val (c1, dx1) = findColOffset(colWidthsPx, startCol, leftOffsetEmu)
        val (c2, dx2) = findColOffset(colWidthsPx, startCol, rightEndEmu)
        val (r1, dy1) = findRowOffset(rowHeightsPt, startRow, topOffsetEmu)
        val (r2, dy2) = findRowOffset(rowHeightsPt, startRow, bottomEndEmu)

        val anchor = XSSFClientAnchor(dx1, dy1, dx2, dy2, c1, r1, c2, r2).apply {
            anchorType = ClientAnchor.AnchorType.MOVE_AND_RESIZE
        }
        drawing.createPicture(anchor, pictureIdx)
    }

    private fun findColOffset(colWidthsPx: List<Double>, baseCol: Int, targetEmu: Double): Pair<Int, Int> {
        var acc = 0.0
        for (i in colWidthsPx.indices) {
            val wEmu = colWidthsPx[i] * Units.EMU_PER_PIXEL
            if (acc + wEmu >= targetEmu || i == colWidthsPx.lastIndex) {
                return (baseCol + i) to (targetEmu - acc).toInt().coerceAtLeast(0)
            }
            acc += wEmu
        }
        return baseCol to 0
    }

    private fun findRowOffset(rowHeightsPt: List<Double>, baseRow: Int, targetEmu: Double): Pair<Int, Int> {
        var acc = 0.0
        for (i in rowHeightsPt.indices) {
            val hEmu = rowHeightsPt[i] * Units.EMU_PER_POINT
            if (acc + hEmu >= targetEmu || i == rowHeightsPt.lastIndex) {
                return (baseRow + i) to (targetEmu - acc).toInt().coerceAtLeast(0)
            }
            acc += hEmu
        }
        return baseRow to 0
    }
}
