package com.cdwy.permission.company;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Restricted XLSX reader for these tabular imports; no formulas or external entities. */
public final class XlsxReader {
    public record Row(int number, Map<String,String> cells) {
        public String get(String column) { return cells.getOrDefault(column, "").strip(); }
    }
    private static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public List<Row> read(byte[] bytes) {
        try {
            Map<String,byte[]> parts=new HashMap<>(); int total=0, entries=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(++entries>1000) throw new IllegalArgumentException("XLSX 包含过多文件");
                    byte[] part=zip.readNBytes(24_000_001); total+=part.length;
                    if(part.length>24_000_000 || total>48_000_000) throw new IllegalArgumentException("XLSX 解压大小超限");
                    if(parts.put(entry.getName(),part)!=null) throw new IllegalArgumentException("XLSX 含重复文件项");
                }
            }
            var strings=new ArrayList<String>();
            if(parts.containsKey("xl/sharedStrings.xml")) {
                var nodes=parse(parts.get("xl/sharedStrings.xml")).getElementsByTagNameNS(NS,"si");
                for(int i=0;i<nodes.getLength();i++) strings.add(text((Element)nodes.item(i),"t"));
            }
            // Source templates have exactly one worksheet; reject ambiguous multi-sheet imports.
            var sheets=parts.keySet().stream().filter(x->x.matches("xl/worksheets/sheet\\d+\\.xml")).sorted().toList();
            if(sheets.size()!=1) throw new IllegalArgumentException("每个导入文件必须包含且仅包含一个工作表");
            var doc=parse(parts.get(sheets.getFirst()));
            var nodes=doc.getElementsByTagNameNS(NS,"row");
            if(nodes.getLength()>20002) throw new IllegalArgumentException("单表最多 20000 条数据");
            var rows=new ArrayList<Row>();
            for(int i=0;i<nodes.getLength();i++) {
                var row=(Element)nodes.item(i); var cells=new LinkedHashMap<String,String>();
                var cs=row.getElementsByTagNameNS(NS,"c");
                for(int j=0;j<cs.getLength();j++) {
                    var c=(Element)cs.item(j); String ref=c.getAttribute("r");
                    if(c.getElementsByTagNameNS(NS,"f").getLength()>0) throw new IllegalArgumentException("不接受公式单元格: "+ref);
                    String value=text(c,"v");
                    if(c.getAttribute("t").equals("s")) value=strings.get(Integer.parseInt(value));
                    if(c.getAttribute("t").equals("inlineStr")) value=text(c,"t");
                    if(value.length()>2000) throw new IllegalArgumentException("单元格内容过长: "+ref);
                    cells.put(ref.replaceAll("[0-9]", ""),value);
                }
                if(cells.values().stream().anyMatch(v->!v.isBlank())) rows.add(new Row(Integer.parseInt(row.getAttribute("r")),cells));
            }
            return rows;
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new IllegalArgumentException("无法读取 XLSX，请使用原始三表格式且不要包含公式"); }
    }
    private Document parse(byte[] data) throws Exception {
        var f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }
    private String text(Element e,String tag) {
        var nodes=e.getElementsByTagNameNS(NS,tag); var b=new StringBuilder();
        for(int i=0;i<nodes.getLength();i++) b.append(nodes.item(i).getTextContent());
        return b.toString();
    }
}
