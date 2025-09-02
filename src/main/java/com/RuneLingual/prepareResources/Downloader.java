package com.RuneLingual.prepareResources;

import com.RuneLingual.LangCodeSelectableList;
import com.RuneLingual.RuneLingualPlugin;
import com.RuneLingual.SQL.SqlActions;
import com.RuneLingual.commonFunctions.FileActions;
import com.RuneLingual.commonFunctions.FileNameAndPath;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


@Slf4j
public class Downloader {//downloads translations and japanese char images to external file
    private static final File localBaseFolder = FileNameAndPath.getLocalBaseFolder();
    @Inject
    private RuneLingualPlugin plugin;
    @Getter
    private File localLangFolder;
    private String GITHUB_BASE_URL;
    @Setter
    @Getter
    private String langCode;
    @Inject
    private DataFormater dataFormater;


    @Inject
    public Downloader(RuneLingualPlugin plugin) {
        this.plugin = plugin;
    }

    // returns if char image changed
    public void initDownloader() {
        LangCodeSelectableList langCodeSelectableList = plugin.getConfig().getSelectedLanguage();
        if (langCodeSelectableList == LangCodeSelectableList.ENGLISH) {
            return;
        }
        final List<String> extensions_to_download = new ArrayList<>(); // will download all files with these extensions
        if (langCodeSelectableList.needsCharImages()){
            extensions_to_download.add("zip");
        }
        if (langCodeSelectableList.hasLocalTranscript()){
            extensions_to_download.add("png");//for swapping sprites
            extensions_to_download.add("tsv");
        }
        if (extensions_to_download.isEmpty()) {
            return;
        }
        final List<String> file_name_to_download = List.of("char_" + langCode + ".zip",
                "latin2foreign_" + langCode + ".txt",
                "foreign2foreign_" + langCode + ".txt"); // will download all files with these names, no error if it doesnt exist
        localLangFolder = new File(FileNameAndPath.getLocalLangFolder(langCodeSelectableList));

        createDir(localLangFolder.getPath());
        String LOCAL_HASH_NAME = "hashListLocal_" + langCode + ".txt";
        String remote_sub_folder = "public"; //todo: this value is "draft" if reading from draft folder, "public" if reading from the public folder
        GITHUB_BASE_URL = "https://raw.githubusercontent.com/YS-jack/Runelingual-Transcripts/original-main/" +
                remote_sub_folder + "/" + langCode + "/";

        // if the plugin is configured to use custom data, use that instead of the default GitHub URL
        // example of customDataUrl: https://raw.githubusercontent.com/YS-jack/Runelingual-Transcripts/original-main/draft/
        String customDataUrl = plugin.getConfig().getCustomDataUrl();
        if (!customDataUrl.endsWith("/")) {
            customDataUrl = customDataUrl + "/";
        }
        customDataUrl = customDataUrl + langCode + "/";
        if(plugin.getConfig().useCustomData() && isURLReachable(customDataUrl + "hashList_" + langCode + ".txt")) {
            GITHUB_BASE_URL = customDataUrl;
            //log.info("Using custom data URL: " + GITHUB_BASE_URL);
        } else {
            log.error("Custom data URL is not reachable or not configured, using default GitHub URL: " + GITHUB_BASE_URL);
        }

        String REMOTE_HASH_FILE = GITHUB_BASE_URL + "hashList_" + langCode + ".txt";

        try {
            Path dirPath = Paths.get(localBaseFolder.getPath());
            if (!Files.exists(dirPath)) {
                try {
                    // Attempt to create the directory
                    Files.createDirectories(dirPath);
                } catch (IOException e) {
                    log.error("Error creating directory", e);
                }
            }

            Map<String, String> localHashes = readHashFile(Paths.get(localLangFolder.getPath(), LOCAL_HASH_NAME));
            Map<String, String> remoteHashes = readHashFile(new URL(REMOTE_HASH_FILE));

            boolean dataChanged = false;
            boolean transcriptChanged = false;
            List<String> remoteTsvFileNames = new ArrayList<>(); // list of tsv files to include in the sql database


            for (Map.Entry<String, String> entry : remoteHashes.entrySet()) {
                String localHash = localHashes.get(entry.getKey());
                String remoteHash = entry.getValue();
                String remote_full_path = entry.getKey();
                Connection conn = plugin.getH2Manager().getConn(plugin.getTargetLanguage());

                if ((localHash == null || !localHash.equals(remoteHash) || SqlActions.noTableExistsOrIsEmpty(conn)) // if the file is not in the local hash file OR the hash value is different OR the table TRANSCRIPT exists but empty
                        && (fileExtensionIncludedIn(remote_full_path, extensions_to_download) // and if the file extension is in the list of extensions to download
                        || same_file_included(remote_full_path, file_name_to_download))) { // or if the file name is in the list of file names to download

                    dataChanged = true;
                    downloadAndUpdateFile(remote_full_path);
                    if (fileExtensionIncludedIn(remote_full_path, List.of("zip"))) { // if its a zip file, unzip it
                        updateCharDir(Paths.get(localLangFolder.getPath(), "char_" + langCode + ".zip")); // currently only supports char images, which should suffice
                    } else {
                        transcriptChanged = true; // if the file is not a zip file, then one of the transcripts has changed
                    }
                }

                if (fileExtensionIncludedIn(remote_full_path, List.of("tsv"))) {
                    remoteTsvFileNames.add(remote_full_path);
                }
            }
            String[] tsvFileNames = remoteTsvFileNames.toArray(new String[0]);
            this.plugin.setTsvFileNames(tsvFileNames);

            if (dataChanged) {
                // Overwrite local hash file with the updated remote hash file
                Files.copy(new URL(REMOTE_HASH_FILE).openStream(), Paths.get(localLangFolder.getPath(), LOCAL_HASH_NAME), StandardCopyOption.REPLACE_EXISTING);
                if (transcriptChanged) {
                    dataFormater.updateSqlFromTsv(localLangFolder.getPath(), tsvFileNames);
                }
            } else {
                //log.info("All files are up to date.");
            }
        } catch (IOException e) {
            log.error("An error occurred: {}", e.getMessage(), e);
        }
    }

    public void createDir(String path) {
        Path dirPath = Paths.get(path);
        if (!Files.exists(dirPath)) {
            try {
                // Attempt to create the directory
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                log.error("Error creating directory", e);
            }
        }
    }

    private Boolean fileExtensionIncludedIn(String file_full_path, List<String> extensions) {
        String extension = get_file_extension(file_full_path);
        return extensions.contains(extension);
    }

    private String get_file_extension(String file_full_path) {
        String[] parts = file_full_path.split("\\.");
        return parts[parts.length - 1];
    }

    private Boolean same_file_included(String file_full_path, List<String> file_names) {
        Path fullPath = Paths.get(file_full_path);
        Path fileName = fullPath.getFileName();
        String fileNameString = fileName.toString();
        return file_names.contains(fileNameString);
    }

    private Map<String, String> readHashFile(Path filePath) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        if (!Files.exists(filePath)) {
            Files.createDirectories(filePath.getParent());
            Files.createFile(filePath);
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2) {
                    hashes.put(parts[0], parts[1]);
                }
            }
        }
        return hashes;
    }

    private Map<String, String> readHashFile(URL fileUrl) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fileUrl.openStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2) {
                    hashes.put(parts[0], parts[1]);
                }
            }
        }
        return hashes;
    }

    private void downloadAndUpdateFile(String remoteFullPath) throws IOException {
        // filePath example: "draft\ja\actions_ja.xliff" this is the location of files relative to the GitHub repo root of RuneLite-Transcripts
        URL fileUrl = new URL(GITHUB_BASE_URL + remoteFullPath.replace("\\", "/"));
        Path localPath = Paths.get(localLangFolder.getPath(), remoteFullPath.replace("draft\\", ""));
        //log.info("updating file " + localPath);


        // Check if the language directory exists, if not, create it
        if (Files.notExists(localPath.getParent())) {
            Files.createDirectories(localPath.getParent());
        }

        Files.copy(fileUrl.openStream(), localPath, StandardCopyOption.REPLACE_EXISTING);
    }

    public void updateCharDir(Path localPath) throws IOException {
        URL fileUrl3 = new URL(GITHUB_BASE_URL + "/char_" + langCode + ".zip");
        Files.copy(fileUrl3.openStream(), localPath, StandardCopyOption.REPLACE_EXISTING);
        unzip(String.valueOf(localPath), localLangFolder.getPath());
        Files.delete(localPath);
    }

    public void unzip(String zipFilePath, String destDir) {
        FileActions.deleteFolder(destDir + File.separator + "char_" + langCode);
        //log.info("unzipping " + zipFilePath + " to " + destDir);
        File dir = new File(destDir);
        // create output directory if it doesn't exist
        if (!dir.exists()) dir.mkdirs();
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while (ze != null) {
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                //create directories for sub directories in zip
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            log.error("Error unzipping file", e);
        }
    }

    public static boolean isURLReachable(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000); // 5 seconds timeout
            connection.setReadTimeout(5000);
            int responseCode = connection.getResponseCode();
            return (200 <= responseCode && responseCode < 400);
        } catch (Exception e) {
            return false;
        }
    }
}
