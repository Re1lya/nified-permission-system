package com.cdwy.permission;

import com.cdwy.permission.company.XlsxReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class XlsxBoundaryTests {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private String sheet(String rows) { return "<worksheet xmlns='" + NS + "'><sheetData>" + rows + "</sheetData></worksheet>"; }
    private byte[] archive(Map<String,String> entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
    @Test void sharedStringsInlineTextNumbersAndBlankRowsAreDecoded() throws Exception {
        var rows = new XlsxReader().read(archive(Map.of(
            "xl/sharedStrings.xml", "<sst xmlns='" + NS + "'><si><r><t>张</t></r><r><t>三</t></r></si></sst>",
            "xl/worksheets/sheet1.xml", sheet("<row r='1'><c r='A1' t='s'><v>0</v></c><c r='B1'><v>123</v></c><c r='C1' t='inlineStr'><is><t> 空格 </t></is></c></row><row r='2'><c r='A2' t='inlineStr'><is><t> </t></is></c></row>"))));
        assertEquals(1, rows.size()); assertEquals(1, rows.get(0).number());
        assertEquals("张三", rows.get(0).get("A")); assertEquals("123", rows.get(0).get("B"));
        assertEquals("空格", rows.get(0).get("C")); assertEquals("", rows.get(0).get("D"));
    }
    @ParameterizedTest @ValueSource(strings = {
        "<c r='A1'><f>1+1</f><v>2</v></c>",
        "<c r='A1' t='s'><v>9</v></c>",
        "<c r='A1' t='s'><v>invalid</v></c>"})
    void formulasAndInvalidSharedStringReferencesAreRejected(String cell) throws Exception {
        byte[] file = archive(Map.of("xl/worksheets/sheet1.xml", sheet("<row r='1'>" + cell + "</row>")));
        assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(file));
    }
    @Test void ambiguousOrMissingWorksheetIsRejected() throws Exception {
        var file = archive(Map.of("xl/worksheets/sheet1.xml", sheet(""), "xl/worksheets/sheet2.xml", sheet("")));
        assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(file));
        assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(new byte[0]));
    }
    @Test void oversizedCellAndRowCountAreRejected() throws Exception {
        var cell = archive(Map.of("xl/worksheets/sheet1.xml", sheet("<row r='1'><c r='A1'><v>" + "x".repeat(2001) + "</v></c></row>")));
        assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(cell));
        var rows = archive(Map.of("xl/worksheets/sheet1.xml", sheet("<row r='1'/>".repeat(20003))));
        assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(rows));
    }
    @Test void excessiveZipEntryCountIsRejectedBeforeXmlParsing() throws Exception {
        var entries = new LinkedHashMap<String,String>();
        for (int i = 0; i < 1001; i++) entries.put("part" + i, "x");
        var file = archive(entries);
        var exception = assertThrows(IllegalArgumentException.class, () -> new XlsxReader().read(file));
        assertTrue(exception.getMessage().contains("过多文件"));
    }
}
