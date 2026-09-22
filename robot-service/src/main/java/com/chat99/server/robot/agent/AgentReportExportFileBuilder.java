package com.chat99.server.robot.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

final class AgentReportExportFileBuilder {

    private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final String[] UNIFIED_HEADERS = {
        "行类型", "日期", "编号", "名称", "直属代理编号",
        "代理人数", "玩家人数", "流水", "玩家输赢", "平台输赢",
        "上分", "下分", "余额", "已反水", "待反水"
    };

    /** 列宽（字符数）：中文表头与千万级金额（如 99,999,999.99）均可完整展示 */
    private static final int[] COLUMN_WIDTH_CHARS = {
        14, 12, 10, 18, 16,
        10, 10, 18, 18, 18,
        18, 18, 18, 18, 18
    };

    private AgentReportExportFileBuilder() {}

    static BuiltFile build(ExportPayload payload) {
        if ("TXT".equalsIgnoreCase(payload.fileType())) {
            return buildTxt(payload);
        }
        return buildSpreadsheet(payload);
    }

    private static BuiltFile buildSpreadsheet(ExportPayload payload) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("代理反水历史");
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle textStyle = createTextStyle(workbook);
            CellStyle numberStyle = createNumberStyle(workbook);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < UNIFIED_HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(UNIFIED_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (AgentRebateService.AgentHistoryDaySummary day : payload.days()) {
                rowIndex = writeSummaryRow(
                    sheet, rowIndex, textStyle, numberStyle, payload, day);
            }
            if (payload.includeAgentDetail()) {
                for (DetailRow row : payload.agentDetailRows()) {
                    rowIndex = writeAgentDetailRow(sheet, rowIndex, textStyle, numberStyle, row);
                }
            }
            if (payload.includePlayerDetail()) {
                for (DetailRow row : payload.playerDetailRows()) {
                    rowIndex = writePlayerDetailRow(sheet, rowIndex, textStyle, numberStyle, row);
                }
            }

            applyColumnWidths(sheet);
            sheet.createFreezePane(0, 1);

            workbook.write(out);
            String fileName = fileName(payload, "xlsx");
            return new BuiltFile(
                fileName,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray(),
                payload.rowCount());
        } catch (IOException e) {
            throw new IllegalStateException("failed to build export spreadsheet", e);
        }
    }

    private static int writeSummaryRow(
            Sheet sheet,
            int rowIndex,
            CellStyle textStyle,
            CellStyle numberStyle,
            ExportPayload payload,
            AgentRebateService.AgentHistoryDaySummary day) {
        Row row = sheet.createRow(rowIndex++);
        writeText(row, 0, "汇总", textStyle);
        writeDate(row, 1, day.businessDate(), textStyle);
        writeText(row, 2, payload.agentPlayerNo(), textStyle);
        writeText(row, 3, payload.agentName(), textStyle);
        writeText(row, 4, "", textStyle);
        writeNumber(row, 5, day.agentCount(), numberStyle);
        writeNumber(row, 6, day.playerCount(), numberStyle);
        writeMoney(row, 7, day.totalFlow(), numberStyle);
        writeMoney(row, 8, day.playerProfitLoss(), numberStyle);
        writeMoney(row, 9, day.platformProfitLoss(), numberStyle);
        writeMoney(row, 10, day.totalUp(), numberStyle);
        writeMoney(row, 11, day.totalDown(), numberStyle);
        writeMoney(row, 12, day.totalBalance(), numberStyle);
        writeMoney(row, 13, day.totalRebated(), numberStyle);
        writeMoney(row, 14, day.pendingRebate(), numberStyle);
        return rowIndex;
    }

    private static int writeAgentDetailRow(
            Sheet sheet, int rowIndex, CellStyle textStyle, CellStyle numberStyle, DetailRow detail) {
        Row row = sheet.createRow(rowIndex++);
        writeText(row, 0, "下级代理", textStyle);
        writeDate(row, 1, detail.businessDate(), textStyle);
        writeText(row, 2, detail.playerNo(), textStyle);
        writeText(row, 3, detail.displayName(), textStyle);
        writeText(row, 4, "", textStyle);
        writeBlank(row, 5, textStyle);
        writeBlank(row, 6, textStyle);
        writeMoney(row, 7, detail.totalFlow(), numberStyle);
        writeMoney(row, 8, detail.playerProfitLoss(), numberStyle);
        writeMoney(row, 9, detail.platformProfitLoss(), numberStyle);
        writeMoney(row, 10, detail.totalUp(), numberStyle);
        writeMoney(row, 11, detail.totalDown(), numberStyle);
        writeMoney(row, 12, detail.balance(), numberStyle);
        writeMoney(row, 13, detail.totalRebate(), numberStyle);
        writeMoney(row, 14, detail.pendingRebate(), numberStyle);
        return rowIndex;
    }

    private static int writePlayerDetailRow(
            Sheet sheet, int rowIndex, CellStyle textStyle, CellStyle numberStyle, DetailRow detail) {
        Row row = sheet.createRow(rowIndex++);
        writeText(row, 0, "玩家", textStyle);
        writeDate(row, 1, detail.businessDate(), textStyle);
        writeText(row, 2, detail.playerNo(), textStyle);
        writeText(row, 3, detail.displayName(), textStyle);
        writeText(row, 4, detail.directParentNo(), textStyle);
        writeBlank(row, 5, textStyle);
        writeBlank(row, 6, textStyle);
        writeMoney(row, 7, detail.totalFlow(), numberStyle);
        writeMoney(row, 8, detail.playerProfitLoss(), numberStyle);
        writeMoney(row, 9, detail.platformProfitLoss(), numberStyle);
        writeMoney(row, 10, detail.totalUp(), numberStyle);
        writeMoney(row, 11, detail.totalDown(), numberStyle);
        writeMoney(row, 12, detail.balance(), numberStyle);
        writeMoney(row, 13, detail.totalRebate(), numberStyle);
        writeMoney(row, 14, detail.pendingRebate(), numberStyle);
        return rowIndex;
    }

    private static void applyColumnWidths(Sheet sheet) {
        for (int i = 0; i < COLUMN_WIDTH_CHARS.length; i++) {
            sheet.setColumnWidth(i, COLUMN_WIDTH_CHARS[i] * 256);
        }
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createTextStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createNumberStyle(Workbook workbook) {
        CellStyle style = createTextStyle(workbook);
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private static void writeText(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void writeDate(Row row, int column, LocalDate value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value.toString());
        cell.setCellStyle(style);
    }

    private static void writeNumber(Row row, int column, Number value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static void writeMoney(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        if (value == null) {
            cell.setBlank();
        } else {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private static void writeBlank(Row row, int column, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setBlank();
        cell.setCellStyle(style);
    }

    private static BuiltFile buildTxt(ExportPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("【代理反水历史汇总】\n");
        sb.append("代理编号：").append(payload.agentPlayerNo()).append('\n');
        sb.append("代理名称：").append(payload.agentName()).append('\n');
        sb.append("日期范围：").append(payload.startDate()).append(" ~ ").append(payload.endDate()).append("\n\n");
        for (AgentRebateService.AgentHistoryDaySummary day : payload.days()) {
            sb.append("日期：").append(day.businessDate()).append('\n');
            sb.append("代理人数：").append(day.agentCount()).append('\n');
            sb.append("玩家人数：").append(day.playerCount()).append('\n');
            sb.append("总流水：").append(day.totalFlow()).append('\n');
            sb.append("玩家输赢：").append(day.playerProfitLoss()).append('\n');
            sb.append("平台输赢：").append(day.platformProfitLoss()).append('\n');
            sb.append("总上分：").append(day.totalUp()).append('\n');
            sb.append("总下分：").append(day.totalDown()).append('\n');
            sb.append("总余额：").append(day.totalBalance()).append('\n');
            sb.append("已反水：").append(day.totalRebated()).append('\n');
            sb.append("待反水：").append(day.pendingRebate()).append("\n\n");
        }
        if (payload.includeAgentDetail() && !payload.agentDetailRows().isEmpty()) {
            sb.append("【下级代理明细】\n");
            for (DetailRow row : payload.agentDetailRows()) {
                sb.append(row.businessDate()).append(' ')
                    .append(nullToEmpty(row.playerNo())).append(' ')
                    .append(nullToEmpty(row.displayName())).append(' ')
                    .append("流水=").append(row.totalFlow()).append(' ')
                    .append("待反水=").append(row.pendingRebate()).append('\n');
            }
            sb.append('\n');
        }
        if (payload.includePlayerDetail() && !payload.playerDetailRows().isEmpty()) {
            sb.append("【玩家明细】\n");
            for (DetailRow row : payload.playerDetailRows()) {
                sb.append(row.businessDate()).append(' ')
                    .append(nullToEmpty(row.playerNo())).append(' ')
                    .append(nullToEmpty(row.displayName())).append(' ')
                    .append("流水=").append(row.totalFlow()).append(' ')
                    .append("余额=").append(row.balance()).append('\n');
            }
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] data = concatBom(body);
        return new BuiltFile(fileName(payload, "txt"), "text/plain; charset=utf-8", data, payload.rowCount());
    }

    private static String fileName(ExportPayload payload, String ext) {
        return "代理反水历史_" + payload.agentPlayerNo() + "_"
            + payload.startDate() + "_" + payload.endDate() + "." + ext;
    }

    private static byte[] concatBom(byte[] body) {
        byte[] data = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, data, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, data, UTF8_BOM.length, body.length);
        return data;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record BuiltFile(String fileName, String contentType, byte[] data, int rowCount) {}

    record DetailRow(
        LocalDate businessDate,
        String playerNo,
        String displayName,
        String directParentNo,
        BigDecimal totalFlow,
        BigDecimal playerProfitLoss,
        BigDecimal platformProfitLoss,
        BigDecimal totalRebate,
        BigDecimal pendingRebate,
        BigDecimal balance,
        BigDecimal totalUp,
        BigDecimal totalDown) {}

    record ExportPayload(
        String fileType,
        String agentPlayerNo,
        String agentName,
        LocalDate startDate,
        LocalDate endDate,
        boolean includeAgentDetail,
        boolean includePlayerDetail,
        List<AgentRebateService.AgentHistoryDaySummary> days,
        List<DetailRow> agentDetailRows,
        List<DetailRow> playerDetailRows,
        int rowCount) {}
}
