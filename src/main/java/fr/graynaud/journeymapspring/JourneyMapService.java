package fr.graynaud.journeymapspring;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.coyote.BadRequestException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AllArgsConstructor
@Component
public class JourneyMapService {

    private static final Path WORKING_DIR = Path.of("").resolve("maps");

    private static final List<String> CONTENT_TYPES = List.of("application/zip", "application/x-zip-compressed");

    private static final List<String> VALID_FOLDERS = List.of("overworld", "the_nether", "the_end", "waypoints");

    private static final Path TMP_ZIP = WORKING_DIR.resolveSibling("maps_tmp.zip");

    private static final Predicate<String> PATTERN = Pattern.compile("^(overworld|the_nether|the_end)/(-?\\d+|biome|day|night|topo)/-?\\d+,-?\\d+.png$")
                                                            .asMatchPredicate();

    public static final Path WORKING_ZIP = WORKING_DIR.resolveSibling("maps.zip");

    private final ThreadPoolTaskExecutor executor;

    private final ZipService zipService;

    @PostConstruct
    public void postConstruct() throws IOException {
        FileUtils.forceMkdir(WORKING_DIR.toFile());
    }

    public Optional<ZonedDateTime> getLastModif() throws IOException {
        if (!Files.exists(WORKING_ZIP)) {
            return Optional.empty();
        }

        return Optional.of(Files.getLastModifiedTime(WORKING_ZIP).toInstant().atZone(ZoneId.of("Europe/Paris")));
    }

    public synchronized void merge(MultipartFile file) throws IOException, InterruptedException {
        if (!CONTENT_TYPES.contains(file.getContentType()) || file.getOriginalFilename() == null ||
            !file.getOriginalFilename().endsWith(".zip")) {
            throw new BadRequestException("not-valid");
        }

        log.info("Try merge for {}", file.getOriginalFilename());

        Path sourceZip = FileUtils.getTempDirectory().toPath().resolve(UUID.randomUUID().toString());
        Path source = FileUtils.getTempDirectory().toPath().resolve(UUID.randomUUID().toString());

        try {
            file.transferTo(sourceZip.toFile());

            Instant instant = Instant.now();
            this.zipService.unzip(sourceZip, source, PATTERN);
            log.info("Unzip duration: {}ms", Duration.between(instant, Instant.now()).toMillis());

            checkSource(source);

            Map<Path, List<Path>> imagesPaths = getAllImagesPaths(List.of(source, WORKING_DIR));

            log.info("There are " + imagesPaths.size() + " different images!");
            log.info("Starting the merge!");

            AtomicInteger errors = new AtomicInteger();

            AtomicInteger count = new AtomicInteger(0);
            CountDownLatch processedCount = new CountDownLatch(imagesPaths.entrySet().size());
            imagesPaths.forEach((key, value) -> this.executor.submit(() -> {
                try {
                    if (mergeImages(key, value)) {
                        count.addAndGet(1);
                    }
                } catch (IOException e) {
                    errors.getAndIncrement();
                    log.error("An error occurred while processing image {}, ignoring this image: {}", key, e.getMessage());
                } finally {
                    processedCount.countDown();
                }
            }));

            processedCount.await();

            if (errors.get() > 0) {
                log.info("Got " + errors.get() + " errors!");
            }

            log.info("Modified {} files", count.get());

            if (count.get() > 0) {
                this.zipService.zipFolder(WORKING_DIR, TMP_ZIP, path -> true);
                FileUtils.copyFile(TMP_ZIP.toFile(), WORKING_ZIP.toFile());
            }
        } finally {
            FileUtils.deleteQuietly(sourceZip.toFile());
            FileUtils.deleteQuietly(source.toFile());
            FileUtils.deleteQuietly(TMP_ZIP.toFile());
        }
    }

    private void checkSource(Path source) throws IOException {
        try (Stream<Path> stream = Files.list(source)) {
            for (Path path : stream.toList()) {
                if (!VALID_FOLDERS.contains(path.getFileName().toString())) {
                    log.error("Invalid folder {}", path.getFileName());
                    throw new BadRequestException("invalid-folder");
                }
            }
        }
    }

    private static Map<Path, List<Path>> getAllImagesPaths(List<Path> sources) {
        Map<Path, List<Path>> images = new HashMap<>(); //<Image relative path, All sources folders the image is present in>

        sources.forEach(source -> {
            try (Stream<Path> stream = Files.walk(source).filter(path -> path.toString().endsWith(".png"))) {
                stream.forEach(image -> {
                    Path imageRelativePath = source.relativize(image);
                    List<Path> sourcesPresentIn = images.getOrDefault(imageRelativePath, new ArrayList<>());
                    sourcesPresentIn.add(source);
                    images.put(imageRelativePath, sourcesPresentIn);
                });
            } catch (IOException e) {
                log.error("An error occurred while reading content of " + source.toString() + "! Ignore this source!");
            }
        });

        return images;
    }

    private static boolean mergeImages(Path imagePath, List<Path> sources) throws IOException {
        SortedSet<File> files = sources.stream()
                                       .map(source -> source.resolve(imagePath).toFile())
                                       .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(File::lastModified).reversed())));

        if (files.size() == 1) { //New file or uploaded is already the last version
            if (!Files.exists(JourneyMapService.WORKING_DIR.resolve(imagePath))) { //If not exists new file
                log.info("Created: {}", imagePath);
                FileUtils.copyFile(sources.getFirst().resolve(imagePath).toFile(), JourneyMapService.WORKING_DIR.resolve(imagePath).toFile(), true);
                return true;
            }

            return false;
        }

        if (files.first().lastModified() != files.last().lastModified()) { //Uploaded is already the last version
            Iterator<File> iterator = files.iterator();
            File file = iterator.next();
            BufferedImage destImage = ImageIO.read(file);

            Graphics2D graphics = destImage.createGraphics();
            graphics.setComposite(AlphaComposite.DstOver);
            FileTime lastModif = Files.getLastModifiedTime(file.toPath());

            while (iterator.hasNext()) {
                file = iterator.next();
                graphics.drawImage(ImageIO.read(file), null, 0, 0);
                lastModif = Files.getLastModifiedTime(file.toPath());
            }

            graphics.dispose();

            File destFile = JourneyMapService.WORKING_DIR.resolve(imagePath).toFile();
            FileUtils.forceMkdirParent(destFile);
            FileUtils.deleteQuietly(destFile);

            ImageIO.write(destImage, "PNG", destFile);
            Files.setLastModifiedTime(destFile.toPath(), lastModif);
            log.info("Modified: {}", destFile);
            return true;
        }

        return false;
    }
}
