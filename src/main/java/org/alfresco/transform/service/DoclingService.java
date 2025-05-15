package org.alfresco.transform.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DoclingService {

    private final ChatModel chatModel;

    public String convert(File file) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("docling");

        // Sanitize the filename
        String safeFilename = Optional.ofNullable(file.getName())
                .map(name -> name.replaceAll("[^a-zA-Z0-9\\.\\-]", "_"))
                .orElse("input.pdf");

        // Copy the original file to the temp working directory
        Path pdf = workDir.resolve(safeFilename);
        Files.copy(file.toPath(), pdf, StandardCopyOption.REPLACE_EXISTING);

        // Build and start the Docling process
        ProcessBuilder pb = new ProcessBuilder(
                "docling",
                "--to", "md",
                "--image-export-mode", "embedded",
                "--output", workDir.toString(),
                pdf.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader r = p.inputReader()) {
            r.lines().forEach(log::info);
        }

        if (p.waitFor() != 0) {
            throw new IllegalStateException("Docling exited with " + p.exitValue());
        }

        Path md = workDir.resolve(safeFilename.replaceFirst("\\.pdf$", ".md"));
        String originalMarkdown = Files.readString(md, StandardCharsets.UTF_8);

        return replaceImagesWithDescriptions(originalMarkdown, UUID.randomUUID().toString(), safeFilename);
    }

    private String replaceImagesWithDescriptions(String markdown, String uuid, String filename) {
        StringBuilder newMarkdown = new StringBuilder();
        Pattern imgPattern = Pattern.compile("!\\[([^\\]]*)\\]\\((data:image/[^;]+;base64,([^)]*))\\)");
        Matcher matcher = imgPattern.matcher(markdown);

        int index = 0;
        int lastEnd = 0;

        while (matcher.find()) {
            String altText = matcher.group(1).trim();
            String base64 = matcher.group(3).trim();

            byte[] imageBytes;
            try {
                imageBytes = Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                log.warn("Skipping invalid Base64 image at index {}: {}", index, e.getMessage());
                continue;
            }

            String context = "Document: " + filename + ", altText: " + altText;
            String description = describeImage(imageBytes, index + 1, context);

            // Append text up to the image
            newMarkdown.append(markdown, lastEnd, matcher.start());

            // Replace image with description text
            newMarkdown.append("**[Image Description ").append(index + 1).append("]**\n");
            newMarkdown.append(description).append("\n\n");

            lastEnd = matcher.end();
            index++;
        }

        // Append the rest of the markdown after the last match
        newMarkdown.append(markdown.substring(lastEnd));

        return newMarkdown.toString();
    }

    private String describeImage(byte[] imageBytes, int pageNumber, String context) {
        try {
            String prompt = buildPrompt(pageNumber, context);

            String result = ChatClient.create(chatModel)
                    .prompt()
                    .user(user -> user.text(prompt)
                            .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes)))
                    .call()
                    .content();

            return (result == null || result.isBlank())
                    ? "Image on page " + pageNumber + " of the document."
                    : result;

        } catch (Exception e) {
            log.warn("LLM image description failed on page {}: {}", pageNumber, e.getMessage());
            return "Image on page " + pageNumber + " of the document.";
        }
    }

    private String buildPrompt(int pageNumber, String context) {
        return "You are analyzing an image from page " + pageNumber + " of a PDF document. " +
                "Provide a detailed description of what you see: subject, visual elements, visible text, charts, or diagrams. " +
                (context.isBlank() ? "" : "This image appears with the following context: \"" + context + "\". ") +
                "Your description should be 2–3 sentences, concise but informative, useful for future search or summarization.";
    }

}