package com.sko.safetyphotoexcel

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.BorderStyle
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
import java.io.FileOutputStream

data class PhotoEntry(
    val id: Long,
    val uri: Uri,
    val caption: String
)

object ExcelGenerator {

    private data class BlockBounds(
        val photoStartRow: Int, // 0-based inclusive
        val photoEndRow: Int,   // 0-based inclusive
        val captionStartRow: Int,
        val captionEndRow: Int
    )

    fun generate(
        context: Context,
        title: String,
        teamName: String,
        dateText: String,
        photos: List<PhotoEntry>,
        outputFileName: String
    ): File {
        require(photos.isNotEmpty()) { "최소 1장 이상의 사진이 필요합니다." }

        val workbook = context.assets.open("업로드 양식.xlsx").use { input ->
            XSSFWorkbook(input)
        }
        val sheet = workbook.getSheetAt(0)

        // 헤더 업데이트 (A1: 제목, A2: 팀명, H2: 날짜)
        sheet.getRow(0)?.getCell(0)?.setCellValue(title)
        sheet.getRow(1)?.getCell(0)?.setCellValue("팀명: $teamName")
        val dateCell = sheet.getRow(1)?.getCell(7)
        if (dateCell != null) {
            dateCell.setCellValue(dateText)
        }

        // 기본 양식 블록: 사진1 (row 3..28, 내용 29..30), 사진2 (row 31..59, 내용 60..61)
        val blocks = mutableListOf(
            BlockBounds(photoStartRow = 3, photoEndRow = 28, captionStartRow = 29, captionEndRow = 30),
            BlockBounds(photoStartRow = 31, photoEndRow = 59, captionStartRow = 60, captionEndRow = 61)
        )

        // 사진이 3장 이상이면 동일한 규격으로 아래에 블록 추가 (사진3, 사진4...)
        while (blocks.size < photos.size) {
            val prev = blocks.last()
            val photoStart = prev.captionEndRow + 1
            val photoEnd = photoStart + 28 // 29행 높이 (사진2와 동일)
            val capStart = photoEnd + 1
            val capEnd = capStart + 1
            createPhotoBlock(workbook, sheet, blocks.size + 1, photoStart, photoEnd, capStart, capEnd)
            blocks.add(BlockBounds(photoStart, photoEnd, capStart, capEnd))
        }

        // 사진이 1장만 있을 때 사진2 라벨/내용 정리
        if (photos.size == 1) {
            sheet.getRow(31)?.getCell(0)?.setCellValue("")
            sheet.getRow(60)?.getCell(2)?.setCellValue("")
        }

        // 각 블록에 사진 및 내용 삽입
        photos.forEachIndexed { index, photo ->
            val block = blocks[index]
            // 사진 번호 라벨 제거 (사진이 덮으므로 깔끔하게 비우거나 유지 가능)
            sheet.getRow(block.photoStartRow)?.getCell(0)?.setCellValue("")
            // 내용 텍스트 채우기 (C열 = col index 2)
            sheet.getRow(block.captionStartRow)?.getCell(2)?.setCellValue(photo.caption)

            val processed = ImageUtils.loadNormalizedJpeg(context, photo.uri)
            insertCenteredPicture(
                workbook = workbook,
                sheet = sheet,
                imageBytes = processed.bytes,
                imgWidthPx = processed.widthPx,
                imgHeightPx = processed.heightPx,
                startRow = block.photoStartRow,
                endRow = block.photoEndRow,
                startCol = 0,
                endCol = 8 // A..I
            )
        }

        // 인쇄 영역 확장
        val lastRow = blocks[photos.size.coerceAtLeast(2) - 1].captionEndRow + 1
        workbook.setPrintArea(0, 0, 8, 0, lastRow - 1)

        val outDir = File(context.cacheDir, "excel").apply { mkdirs() }
        val outFile = File(outDir, outputFileName)
        FileOutputStream(outFile).use { fos ->
            workbook.write(fos)
        }
        workbook.close()
        return outFile
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

        // 박스 전체 너비/높이(EMU) 계산
        val colWidthsPx = (startCol..endCol).map { sheet.getColumnWidthInPixels(it).toDouble() }
        val rowHeightsPt = (startRow..endRow).map {
            val row = sheet.getRow(it)
            val h = row?.heightInPoints ?: sheet.defaultRowHeightInPoints
            h.toDouble()
        }

        val totalBoxWidthEmu = colWidthsPx.sum() * Units.EMU_PER_PIXEL
        val totalBoxHeightEmu = rowHeightsPt.sum() * Units.EMU_PER_POINT

        // 테두리와 겹치지 않도록 상하좌우 4% 여백 부여
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
