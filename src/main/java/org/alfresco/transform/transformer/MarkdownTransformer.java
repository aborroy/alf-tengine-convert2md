package org.alfresco.transform.transformer;

import lombok.RequiredArgsConstructor;
import org.alfresco.transform.service.DoclingService;
import org.alfresco.transform.base.CustomTransformer;
import org.alfresco.transform.base.TransformManager;
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

        // Create a temporary file
        File tempFile = File.createTempFile("input-", ".pdf");

        // Copy the InputStream to the temporary file
        try (OutputStream tempOut = new FileOutputStream(tempFile)) {
            inputStream.transferTo(tempOut);
        }

        try {
            // Call the File-based convert method
            String markdown = doclingService.convert(tempFile);

            // Write the result to the output stream
            outputStream.write(markdown.getBytes(StandardCharsets.UTF_8));
        } finally {
            // Always delete the temp file
            tempFile.delete();
        }
    }

}
