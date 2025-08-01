package org.alfresco.transform.transformer;

import lombok.RequiredArgsConstructor;
import org.alfresco.transform.service.DoclingService;
import org.alfresco.transform.base.CustomTransformer;
import org.alfresco.transform.base.TransformManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MarkdownTransformer implements CustomTransformer {

    private final DoclingService doclingService;

    @Value("${transform.language.default}")
    String defaultLanguage;

    @Value("${transform.image.default}")
    String defaultImage;

    @Override
    public String getTransformerName() {
        return "markdown";
    }

    @Override
    public void transform(String sourceMimetype,
                          InputStream inputStream,
                          String targetMimetype,
                          OutputStream outputStream,
                          Map<String, String> transformOptions,
                          TransformManager transformManager) throws Exception {

        File tempFile = File.createTempFile("input-", ".pdf");

        try (OutputStream tempOut = new FileOutputStream(tempFile)) {
            inputStream.transferTo(tempOut);
        }

        try {
            String markdown = doclingService.convert(tempFile,
                    transformOptions.getOrDefault("language", defaultLanguage),
                    transformOptions.getOrDefault("image", defaultImage));
            outputStream.write(markdown.getBytes(StandardCharsets.UTF_8));
        } finally {
            tempFile.deleteOnExit();
        }
    }

}
