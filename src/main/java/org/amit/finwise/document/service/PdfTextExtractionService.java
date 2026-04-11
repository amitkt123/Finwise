package org.amit.finwise.document.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
public class PdfTextExtractionService {

    public String extract(File file, String password) {
        try {
            PDDocument doc = (password != null && !password.isBlank())
                    ? Loader.loadPDF(file, password)
                    : Loader.loadPDF(file);
            try (doc) {
                return new PDFTextStripper().getText(doc);
            }
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            log.warn("PDF is password-protected: {}", file.getName());
            return null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract PDF text", e);
        }
    }

    public String extract(byte[] bytes, String password) {
        try {
            PDDocument doc = (password != null && !password.isBlank())
                    ? Loader.loadPDF(bytes, password)
                    : Loader.loadPDF(bytes);
            try (doc) {
                return new PDFTextStripper().getText(doc);
            }
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            log.warn("Downloaded PDF is password-protected");
            return null;
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract PDF text", e);
        }
    }

    public String extract(InputStream inputStream, String password) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            return extract(bytes, password);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read PDF stream", e);
        }
    }
}
