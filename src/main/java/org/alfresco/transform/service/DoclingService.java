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

    public static final String IMAGE_MODE_DESCRIBED = "described";
    public static final String IMAGE_MODE_EMBEDDED = "embedded";

    private final ChatModel chatModel;

    public String convert(File file, String targetLanguage, String imageMode) throws IOException, InterruptedException {
        Path workDir = Files.createTempDirectory("docling");

        // Sanitize the filename
        String safeFilename = Optional.of(file.getName())
                .map(name -> name.replaceAll("[^a-zA-Z0-9\\.\\-]", "_"))
                .orElse("input.pdf");

        // Copy the original file to the temp working directory
        Path pdf = workDir.resolve(safeFilename);
        Files.copy(file.toPath(), pdf, StandardCopyOption.REPLACE_EXISTING);

        // Image mode
        String imageModeParam = IMAGE_MODE_DESCRIBED.equalsIgnoreCase(imageMode) ? IMAGE_MODE_EMBEDDED : imageMode.toLowerCase();

        // Build and start the Docling process
        ProcessBuilder pb = new ProcessBuilder(
                "docling",
                "--to", "md",
                "--image-export-mode", imageModeParam,
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

        if (imageMode.equalsIgnoreCase(IMAGE_MODE_DESCRIBED)) {
            return replaceImagesWithDescriptions(originalMarkdown, UUID.randomUUID().toString(), safeFilename, targetLanguage);
        } else {
            return originalMarkdown;
        }
    }

    private String replaceImagesWithDescriptions(String markdown, String uuid, String filename, String language) {
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
            String description = describeImage(imageBytes, index + 1, context, language);

            // Append text up to the image
            newMarkdown.append(markdown, lastEnd, matcher.start());

            // Replace image with description text
            String descriptionLabel = getDescriptionLabel(language, index + 1);
            newMarkdown.append(descriptionLabel).append("\n");
            newMarkdown.append(description).append("\n\n");

            lastEnd = matcher.end();
            index++;
        }

        // Append the rest of the markdown after the last match
        newMarkdown.append(markdown.substring(lastEnd));

        return newMarkdown.toString();
    }

    private String getDescriptionLabel(String language, int imageNumber) {
        return switch (language.toLowerCase()) {
            case "spanish" -> "**[Descripción de Imagen " + imageNumber + "]**";
            case "french" -> "**[Description d'Image " + imageNumber + "]**";
            case "german" -> "**[Bildbeschreibung " + imageNumber + "]**";
            case "italian" -> "**[Descrizione Immagine " + imageNumber + "]**";
            case "portuguese" -> "**[Descrição da Imagem " + imageNumber + "]**";
            default -> "**[Image Description " + imageNumber + "]**";
        };
    }

    private String describeImage(byte[] imageBytes, int pageNumber, String context, String language) {
        try {
            String prompt = buildPrompt(pageNumber, context, language);

            String result = ChatClient.create(chatModel)
                    .prompt()
                    .user(user -> user.text(prompt)
                            .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes)))
                    .call()
                    .content();

            return (result == null || result.isBlank())
                    ? getDefaultImageText(pageNumber, language)
                    : result;

        } catch (Exception e) {
            log.warn("LLM image description failed on page {}: {}", pageNumber, e.getMessage());
            return getDefaultImageText(pageNumber, language);
        }
    }

    private String getDefaultImageText(int pageNumber, String language) {
        return switch (language.toLowerCase()) {
            case "spanish" -> "Imagen en la página " + pageNumber + " del documento.";
            case "french" -> "Image à la page " + pageNumber + " du document.";
            case "german" -> "Bild auf Seite " + pageNumber + " des Dokuments.";
            case "italian" -> "Immagine a pagina " + pageNumber + " del documento.";
            case "portuguese" -> "Imagem na página " + pageNumber + " do documento.";
            default -> "Image on page " + pageNumber + " of the document.";
        };
    }

    private String buildPrompt(int pageNumber, String context, String language) {
        String basePrompt = getLocalizedPrompt(language, pageNumber);

        if (!context.isBlank()) {
            String contextPrompt = getContextPrompt(language, context);
            basePrompt += " " + contextPrompt;
        }

        return basePrompt + " " + getInstructionPrompt(language);
    }

    private String getLocalizedPrompt(String language, int pageNumber) {
        return switch (language.toLowerCase()) {
            case "spanish" -> "Estás analizando una imagen de la página " + pageNumber + " de un documento PDF. " +
                    "Proporciona una descripción detallada de lo que ves: sujeto, elementos visuales, texto visible, gráficos o diagramas.";
            case "french" -> "Vous analysez une image de la page " + pageNumber + " d'un document PDF. " +
                    "Fournissez une description détaillée de ce que vous voyez : sujet, éléments visuels, texte visible, graphiques ou diagrammes.";
            case "german" -> "Sie analysieren ein Bild von Seite " + pageNumber + " eines PDF-Dokuments. " +
                    "Geben Sie eine detaillierte Beschreibung dessen, was Sie sehen: Thema, visuelle Elemente, sichtbarer Text, Diagramme oder Grafiken.";
            case "italian" -> "Stai analizzando un'immagine dalla pagina " + pageNumber + " di un documento PDF. " +
                    "Fornisci una descrizione dettagliata di ciò che vedi: soggetto, elementi visivi, testo visibile, grafici o diagrammi.";
            case "portuguese" -> "Você está analisando uma imagem da página " + pageNumber + " de um documento PDF. " +
                    "Forneça uma descrição detalhada do que você vê: assunto, elementos visuais, texto visível, gráficos ou diagramas.";
            default -> "You are analyzing an image from page " + pageNumber + " of a PDF document. " +
                    "Provide a detailed description of what you see: subject, visual elements, visible text, charts, or diagrams.";
        };
    }

    private String getContextPrompt(String language, String context) {
        return switch (language.toLowerCase()) {
            case "spanish" -> "Esta imagen aparece con el siguiente contexto: \"" + context + "\".";
            case "french" -> "Cette image apparaît avec le contexte suivant : \"" + context + "\".";
            case "german" -> "Dieses Bild erscheint mit folgendem Kontext: \"" + context + "\".";
            case "italian" -> "Questa immagine appare con il seguente contesto: \"" + context + "\".";
            case "portuguese" -> "Esta imagem aparece com o seguinte contexto: \"" + context + "\".";
            default -> "This image appears with the following context: \"" + context + "\".";
        };
    }

    private String getInstructionPrompt(String language) {
        return switch (language.toLowerCase()) {
            case "spanish" -> "Tu descripción debe tener 2-3 oraciones, concisa pero informativa, útil para búsquedas futuras o resúmenes.";
            case "french" -> "Votre description doit comporter 2-3 phrases, concise mais informative, utile pour les recherches futures ou les résumés.";
            case "german" -> "Ihre Beschreibung sollte 2-3 Sätze umfassen, prägnant aber informativ, nützlich für zukünftige Suchen oder Zusammenfassungen.";
            case "italian" -> "La tua descrizione dovrebbe essere di 2-3 frasi, concisa ma informativa, utile per ricerche future o riassunti.";
            case "portuguese" -> "Sua descrição deve ter 2-3 frases, concisa mas informativa, útil para buscas futuras ou resumos.";
            default -> "Your description should be 2–3 sentences, concise but informative, useful for future search or summarization.";
        };
    }
}