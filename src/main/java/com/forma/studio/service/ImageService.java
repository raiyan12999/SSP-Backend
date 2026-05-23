package com.forma.studio.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles all image upload, resize, and deletion operations.
 *
 * WHY this approach matters for performance:
 * A photographer's raw image can be 8-12MB. If 12 project cards on a page each
 * load a full-size image, that's 96-144MB just to display the grid. By automatically
 * creating three smaller versions on upload, the grid page loads ~300KB instead.
 *
 * The three versions we create for every image:
 *   LARGE   - max 1920px wide, 85% quality  →  ~200KB  (detail page, lightbox)
 *   MEDIUM  - max 800px wide,  80% quality  →  ~80KB   (carousels, about page)
 *   THUMB   - exactly 400x300px cropped,    →  ~25KB   (project grid cards)
 *              75% quality
 *
 * =====================================================================
 * HOW TO UPGRADE TO CLOUDINARY (when you're ready to deploy to production):
 * =====================================================================
 * Currently this service saves files to local disk. To switch to Cloudinary:
 *
 * 1. Add the Cloudinary SDK to pom.xml:
 *    <dependency>
 *        <groupId>com.cloudinary</groupId>
 *        <artifactId>cloudinary-http5</artifactId>
 *        <version>2.x.x</version>
 *    </dependency>
 *
 * 2. Add to application.properties:
 *    app.cloudinary.cloud-name=your_cloud_name
 *    app.cloudinary.api-key=your_api_key
 *    app.cloudinary.api-secret=your_api_secret
 *
 * 3. Replace the saveToLocalDisk() calls inside processAndSave() with:
 *    cloudinary.uploader().upload(inputStream, ObjectUtils.asMap(
 *        "public_id", filename + "_large",
 *        "transformation", new Transformation().width(1920).quality(85)
 *    ));
 *    Then store the returned secure_url in the ImageResult.
 *
 * 4. Replace the deleteFromLocalDisk() call inside deleteImage() with:
 *    cloudinary.uploader().destroy(filename + "_large", ObjectUtils.emptyMap());
 *    cloudinary.uploader().destroy(filename + "_medium", ObjectUtils.emptyMap());
 *    cloudinary.uploader().destroy(filename + "_thumb", ObjectUtils.emptyMap());
 *
 * Nothing else in the codebase needs to change — only this file.
 * =====================================================================
 */

@Service
public class ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageService.class);

    // The same allowed types as before
    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg", "image/jpg", "image/png", "image/webp"
    );

    private final Cloudinary cloudinary;

    // Constructor — Spring injects the @Value properties
    public ImageService(
            @Value("${cloudinary.cloud-name}") String cloudName,
            @Value("${cloudinary.api-key}") String apiKey,
            @Value("${cloudinary.api-secret}") String apiSecret) {

        // Cloudinary is configured with a Map of credentials
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", cloudName,
                "api_key",    apiKey,
                "api_secret", apiSecret
        ));
    }

    // This is the same return type — nothing else in the codebase changes
    public static class ImageResult {
        public final String filename;
        public final String largeUrl;
        public final String mediumUrl;
        public final String thumbnailUrl;

        public ImageResult(String filename, String largeUrl, String mediumUrl, String thumbnailUrl) {
            this.filename = filename;
            this.largeUrl = largeUrl;
            this.mediumUrl = mediumUrl;
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    public ImageResult processAndSave(MultipartFile file) throws IOException {
        validateFileType(file);

        String publicId = UUID.randomUUID().toString();

        // Step 1: Compress the image locally BEFORE sending to Cloudinary.
        // We resize to max 2000px wide and 80% quality — this brings a 12MB raw
        // photo down to roughly 1-2MB, well within Cloudinary's 10MB free limit.
        ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
        Thumbnails.of(file.getInputStream())
                .width(2000)
                .keepAspectRatio(true)
                .outputQuality(0.80)
                .outputFormat("jpg")
                .toOutputStream(compressedOutput);

        byte[] compressedBytes = compressedOutput.toByteArray();

        // Step 2: Upload the compressed bytes to Cloudinary with eager transformations
        Map uploadResult = cloudinary.uploader().upload(compressedBytes, ObjectUtils.asMap(
                "public_id", publicId,
                "overwrite",  true,
                "eager", Arrays.asList(
                        new Transformation().width(1920).quality(85).fetchFormat("jpg"),
                        new Transformation().width(800).quality(80).fetchFormat("jpg"),
                        new Transformation().width(400).height(400).crop("fill").gravity("center").quality(75).fetchFormat("jpg")
                )
        ));

        List<Map> eagerResults = (List<Map>) uploadResult.get("eager");
        String largeUrl  = (String) eagerResults.get(0).get("secure_url");
        String mediumUrl = (String) eagerResults.get(1).get("secure_url");
        String thumbUrl  = (String) eagerResults.get(2).get("secure_url");

        logger.info("Uploaded image '{}' to Cloudinary", publicId);
        return new ImageResult(publicId, largeUrl, mediumUrl, thumbUrl);
    }


    public void deleteImage(String publicId) {
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            logger.info("Deleted image '{}' from Cloudinary", publicId);
        } catch (IOException e) {
            logger.warn("Could not delete image '{}' from Cloudinary: {}", publicId, e.getMessage());
        }
    }

    private void validateFileType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Invalid file type: " + contentType + ". Only JPG, PNG, and WEBP are allowed."
            );
        }
    }
}

