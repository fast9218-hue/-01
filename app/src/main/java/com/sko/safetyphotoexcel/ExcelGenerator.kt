package com.sko.safetyphotoexcel

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.ClientAnchor
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.RegionUtil
import org.apache.poi.util.Units
import org.apache.poi.xssf.usermodel.XSSFClientAnchor
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class PhotoEntry(
    val id: Long,
    val uri: Uri,
    val caption: String
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

    private val photoTagRegex = Regex("^사진\s*(\d+)$")
    private val captionTagRegex = Regex("^내용\s*(\d+)$")

    fun customTemplateFile(context: Context): File =
        File(context.filesDir, "custom_template.xlsx")

    enum class BuiltinTemplate(
        val id: String,
        val displayName: String,
        val description: String,
        val assetFile: String
    ) {
        VERTICAL_2("vertical_2", "세로 2단 기본형", "큰 사진 위아래 2장 + 자동 확장 (A4 세로)", "template_vertical_2.xlsx"),
        GRID_2X2("grid_2x2", "2x2 격자형 (4장)", "한 페이지에 4장 나란히 배치 (A4 세로)", "template_grid_2x2.xlsx"),
        BEFORE_AFTER("before_after", "작업 전·후 비교형", "좌측 작업 전 / 우측 작업 후 비교 (A4 가로)", "template_before_after.xlsx"),
        LIST_ROW("list_row", "목록형 (좌 사진·우 설명)", "사진과 상세 설명을 좌우로 나란히 배치", "template_list_row.xlsx")
    }

    fun generate(
        context: Context,
        title: String,
        teamName: String,
        dateText: String,
        photos: List<PhotoEntry>,
        outputFileName: String,
        templateId: String = "vertical_2"
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

        // 모든 시트에서 '사진1', '사진2', '사진3'... 셀(및 병합 범위) 자동 탐색
        val detectedSlots = findPhotoSlots(workbook).toMutableList()
        val captionPlaceholders = findCaptionPlaceholders(workbook)

        if (!isCustom) {
            val sheet0 = workbook.getSheetAt(0)
            sheet0.getRow(0)?.getCell(0)?.setCellValue(title)
            updateTeamCellIfPresent(workbook, teamName)
            updateDateCellIfPresent(workbook, dateText)
        } else {
            updateTeamCellIfPresent(workbook, teamName)
        }

        // 양식에 있는 사진 칸보다 사진이 더 많으면 첫 시트 하단에 자동 확장
        if (detectedSlots.isNotEmpty() && detectedSlots.size < photos.size) {
            val lastSheet = workbook.getSheetAt(detectedSlots.last().sheetIndex)
            while (detectedSlots.size < photos.size) {
                val nextIdx = detectedSlots.size + 1
                val lastBottom = (lastSheet.lastRowNum).coerceAtLeast(detectedSlots.last().endRow + 2)
                val photoStart = lastBottom + 1
                val photoEnd = photoStart + 28
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

        // 사진 삽입 및 내용 반영
        photos.forEachIndexed { idx, photo ->
            if (idx >= detectedSlots.size) return@forEachIndexed
            val slot = detectedSlots[idx]
            val sheet = workbook.getSheetAt(slot.sheetIndex)

            // '사진N' 라벨 텍스트 비우기
            sheet.getRow(slot.labelRow)?.getCell(slot.labelCol)?.setCellValue("")

            // 내용(캡션) 반영: '내용N' 태그가 있으면 거기에, 없으면 사진 바로 아래 '내 용' 행에 기입
            val explicitCap = captionPlaceholders[slot.index]
            if (explicitCap != null) {
                val capSheet = workbook.getSheetAt(explicitCap.first)
                capSheet.getRow(explicitCap.second)?.getCell(explicitCap.third)?.setCellValue(photo.caption)
            } else {
                writeCaptionBelowSlotIfPresent(sheet, slot, photo.caption)
            }

            val processed = ImageUtils.loadNormalizedJpeg(context, photo.uri)
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

        val outDir = File(context.cacheDir, "excel").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)
        FileOutputStream(outFile).use { fos ->
            workbook.write(fos)
        }
        workbook.close()
        return outFile
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
        if (firstCell.cellType == CellType.STRING && firstCell.stringCellValue.replace(" ", "") == "내용") {
            val mergedLabel = sheet.mergedRegions.firstOrNull {
                it.isInRange(slot.endRow + 1, slot.startCol)
            }
            val targetCol = (mergedLabel?.lastColumn ?: slot.startCol) + 1
            val targetCell = checkRow.getCell(targetCol) ?: checkRow.createCell(targetCol)
            targetCell.setCellValue(caption)
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
        val labelFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 16
            bold = true
        }
        val captionFont = workbook.createFont().apply {
            fontName = "맑은 고딕"
            fontHeightInPoints = 14
            bold = true
        }
        val labelStyle = workbook.createCellStyle().apply {
            setFont(labelFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }
        val captionStyle = workbook.createCellStyle().apply {
            setFont(captionFont)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = true
        }

        for (r in photoStart..capEnd) {
            val row = sheet.getRow(r) ?: sheet.createRow(r)
            if (r == capStart || r == capEnd) {
                row.heightInPoints = 26.25f
            }
            for (c in 0..8) {
                if (row.getCell(c) == null) row.createCell(c)
            }
        }

        val photoRegion = CellRangeAddress(photoStart, photoEnd, 0, 8)
        sheet.addMergedRegion(photoRegion)
        sheet.getRow(photoStart).getCell(0).setCellValue("사진$photoIndex")
        applyBoxBorder(photoRegion, sheet)

        val labelRegion = CellRangeAddress(capStart, capEnd, 0, 1)
        sheet.addMergedRegion(labelRegion)
        sheet.getRow(capStart).getCell(0).apply {
            setCellValue("내  용")
            cellStyle = labelStyle
        }
        applyBoxBorder(labelRegion, sheet)

        val capRegion = CellRangeAddress(capStart, capEnd, 2, 8)
        sheet.addMergedRegion(capRegion)
        sheet.getRow(capStart).getCell(2).cellStyle = captionStyle
        applyBoxBorder(capRegion, sheet)
    }

    private fun applyBoxBorder(region: CellRangeAddress, sheet: XSSFSheet) {
        RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet)
        RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet)
        RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet)
        RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet)
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

        val availW = totalBoxWidthEmu * 0.92
        val availH = totalBoxHeightEmu * 0.92

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
