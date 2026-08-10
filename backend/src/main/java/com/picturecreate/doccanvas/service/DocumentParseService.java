package com.picturecreate.doccanvas.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * 通用文档解析：用 Apache Tika 提取 PDF/DOCX/TXT/MD 文本。
 */
@Service
public class DocumentParseService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);

    /** 单个文件提取文本上限 5MB，防止超大文件拖垮内存 */
    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024;

    /** 解析输入流为纯文本；失败返回 null（不抛出，调用方决定降级） */
    public String parse(InputStream inputStream, String filename) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);
            // 不提取嵌入资源（图片/附件），只取正文
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                @Override
                public boolean shouldParseEmbedded(Metadata metadata) {
                    return false;
                }

                @Override
                public void parseEmbedded(InputStream inputStream, org.xml.sax.ContentHandler contentHandler,
                        Metadata metadata, boolean outputHtml) {
                    // 忽略嵌入内容
                }
            });
            // PDF 专用：关闭图片提取、按位置排序文本（改善多栏）
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(false);
            pdfConfig.setSortByPosition(true);
            context.set(PDFParserConfig.class, pdfConfig);

            parser.parse(inputStream, handler, metadata, context);
            String content = handler.toString();
            log.info("文档解析成功：{}，{} 字符", filename, content.length());
            return content;
        } catch (IOException | TikaException | SAXException exception) {
            log.warn("文档解析失败：{}，{}", filename, exception.getMessage());
            return null;
        }
    }
}
