package fr.graynaud.journeymapspring;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Slf4j
@Component
@AllArgsConstructor
public class ZipService {

    private final ThreadPoolTaskExecutor executor;

    public void zipFiles(Path destPath, Path... sourcePaths) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(destPath.toFile()); ZipOutputStream zipOut = new ZipOutputStream(fos)) {
            for (Path path : sourcePaths) {
                try (FileInputStream fis = new FileInputStream(path.toFile())) {
                    ZipEntry zipEntry = new ZipEntry(path.getFileName().toString());

                    zipOut.putNextEntry(zipEntry);
                    byte[] bytes = new byte[1024];
                    int length;

                    while ((length = fis.read(bytes)) >= 0) {
                        zipOut.write(bytes, 0, length);
                    }
                }
            }
        }
    }

    public void zipFolder(Path sourcePath, Path destPath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destPath.toFile())))) {
            zipFile(sourcePath, sourcePath, zos);
        }
    }

    private void zipFile(Path sourcePath, Path path, ZipOutputStream zipOut) throws IOException {
        if (path.toFile().isHidden()) {
            return;
        }

        if (path.toFile().isDirectory()) {
            try (Stream<Path> stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    zipFile(sourcePath, child, zipOut);
                }
            }

            return;
        }

        ZipEntry zipEntry = new ZipEntry(FilenameUtils.separatorsToUnix(sourcePath.relativize(path).toString()));
        zipEntry.setLastModifiedTime(Files.getLastModifiedTime(path));
        zipOut.putNextEntry(zipEntry);

        try (InputStream stream = Files.newInputStream(path)) {
            IOUtils.copy(stream, zipOut, 1_000_000);
        }
    }

    public void unzip(Path zip, Path destination, Predicate<String> predicate) throws IOException, InterruptedException {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            CountDownLatch processedCount = new CountDownLatch(zipFile.size());

            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();

                if (predicate.test(zipEntry.getName())) {
                    this.executor.submit(() -> {
                        try {
                            File newFile = newFile(destination, zipEntry);
                            if (zipEntry.isDirectory()) {
                                FileUtils.forceMkdir(newFile);
                            } else {
                                // fix for Windows-created archives
                                FileUtils.forceMkdirParent(newFile);
                                FileUtils.copyToFile(zipFile.getInputStream(zipEntry), newFile);
                                Files.setLastModifiedTime(newFile.toPath(), zipEntry.getLastModifiedTime());
                            }
                        } catch (Exception e) {
                            log.error("An error occurred while unzipping entry {}: {}", zipEntry.getName(), e.getMessage());
                        } finally {
                            processedCount.countDown();
                        }
                    });
                } else {
                    processedCount.countDown();
                }
            }

            processedCount.await();
        }
    }

    public File newFile(Path destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = destinationDir.resolve(zipEntry.getName()).toFile();

        String destDirPath = destinationDir.toFile().getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }
}
