package com.hriday.convertFileSize.service;

import com.hriday.convertFileSize.common.Const;
import com.hriday.convertFileSize.dao.ArchiveDetails;
import com.hriday.convertFileSize.dto.ArchiveDetailsDto;
import com.hriday.convertFileSize.exception.CustomException;
import com.hriday.convertFileSize.factory.ArchiveService;
import com.hriday.convertFileSize.repository.ArchiveRepo;
import com.hriday.convertFileSize.utils.ErrorMessage;
import com.hriday.convertFileSize.utils.FileType;
import com.hriday.convertFileSize.utils.Status;
import lombok.extern.slf4j.Slf4j;
import org.hriday.archiveFile.ArchiveFile;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Slf4j
@EnableScheduling
public class FileStorageService implements ArchiveService {

    @Autowired
    protected ArchiveRepo archiveRepo;

    @Value("${fileStorage}")
    protected String fileStoragePath;

    @Value("${tempStorage}")
    public String tempStoragePath;

    @Autowired
    protected ArchiveFile archiveFile;

    @Override
    public String compress(MultipartFile[] file) throws IOException {

        String fileName = StringUtils.cleanPath(Objects.requireNonNull(file[0].getOriginalFilename()));

        String[] fileNameSep = fileName.split("\\.");

        try {
            FileType fileTypeName = FileType.valueOf(fileNameSep[fileNameSep.length - 1].toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorMessage.TYPE_NOT_FOUND);
        }

        String tempFilePath = tempStoragePath + File.separator + fileNameSep[0];
        File folder = new File(tempFilePath);
        folder.mkdir();

        convertMultipartFileToFile(file, tempFilePath);

        String compressedFilePath = fileStoragePath + File.separator + fileName;

        archiveFile.compress(tempFilePath, compressedFilePath);

        return logs(fileNameSep[0] + Const.ZIP);
    }

    public String logs(String fileName) {

        ArchiveDetailsDto archiveDetailsDto = ArchiveDetailsDto
                .builder()
                .fileName(fileName)
                .uid(String.valueOf(UUID.randomUUID()))
                .uploadedAt(System.currentTimeMillis())
                .status(Status.UPLOADED)
                .build();

        ArchiveDetails archiveDetails = new ArchiveDetails();
        BeanUtils.copyProperties(archiveDetailsDto, archiveDetails);

        archiveRepo.save(archiveDetails);
        return archiveDetailsDto.getUid();
    }

    @Override
    public String decompress(MultipartFile[] file) throws IOException {

        String fileName = StringUtils.cleanPath(Objects.requireNonNull(file[0].getOriginalFilename()));

        String tempFilePath = tempStoragePath + File.separator + fileName;

        convertMultipartFileToFile(file, tempFilePath);

        String decompressedFilepath = fileStoragePath;

        archiveFile.decompress(tempFilePath, decompressedFilepath);

        FileInputStream fileInputStream = new FileInputStream(tempFilePath);
        ZipInputStream zis = new ZipInputStream(fileInputStream);
        ZipEntry zipEntry = zis.getNextEntry();

        return logs(zipEntry.getName());
    }

    @Override
    public void convertMultipartFileToFile(MultipartFile[] multipartFiles, String tempFilePath) throws IOException {

        for (MultipartFile file : multipartFiles) {
            file.getName();
            String fileName = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));

            File tempFile = new File(tempFilePath + File.separator + fileName);

            file.transferTo(tempFile);

        }
    }

    @Scheduled(fixedDelay = 5000)
    void deletion() {

        log.info("SCHEDULED FUNCTION");
        Long currentTime = System.currentTimeMillis();
        Long delayMillis = 5000L;

        List<ArchiveDetails> archiveDetails1 =
                archiveRepo.findByStatusAndUploadedAtLessThan(Status.DOWNLOADED, currentTime - delayMillis);

        archiveDetails1.forEach(archiveDetails2 ->
        {
            try {
                fileDeletion(fileStoragePath + File.separator + archiveDetails2.getFileName(), archiveDetails2);
            } catch (IOException e) {
                throw new CustomException(e.getMessage());
            }
        });
    }

    void fileDeletion(String tempPath, ArchiveDetails archiveDetails) throws IOException {

        Path directory = Paths.get(tempPath);
        Files
                .walk(directory)
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        archiveDetails.setStatus(Status.DELETED);
        archiveRepo.save(archiveDetails);
    }

    public Resource downloadFile(String uid) throws IOException {

        ArchiveDetails archiveDetails = archiveRepo.findByUid(uid);

        Path path = Paths.get(fileStoragePath).toAbsolutePath().resolve(archiveDetails.getFileName() + Const.ZIP);
        Resource resource;

        try {
            resource = new UrlResource(path.toUri());
            if (!resource.exists() && resource.isReadable()) throw new CustomException(ErrorMessage.FILE_NOT_EXIST);
            logAfterDownload(archiveDetails);
            return resource;

        } catch (MalformedURLException e) {
            throw new CustomException(ErrorMessage.NOT_READABLE);
        }
    }

    private void logAfterDownload(ArchiveDetails archiveDetails) {
        archiveDetails.setStatus(Status.DOWNLOADED);
        archiveDetails.setDownloadedAt(System.currentTimeMillis());
        archiveRepo.save(archiveDetails);
    }

}
