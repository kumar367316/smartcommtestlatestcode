package com.htc.postprocessing.service;

import static com.htc.postprocessing.constant.PostProcessingConstant.ARCHIVE_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.ARCHIVE_VALUE;
import static com.htc.postprocessing.constant.PostProcessingConstant.BANNER_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.BANNER_PAGE;
import static com.htc.postprocessing.constant.PostProcessingConstant.DATETIME_INTERVAL;
import static com.htc.postprocessing.constant.PostProcessingConstant.EMPTY_SPACE;
import static com.htc.postprocessing.constant.PostProcessingConstant.EMPTY_VALUE;
import static com.htc.postprocessing.constant.PostProcessingConstant.PCL_EXTENSION;
import static com.htc.postprocessing.constant.PostProcessingConstant.PDF_EXTENSION;
import static com.htc.postprocessing.constant.PostProcessingConstant.PRINT_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.PROCESSED_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.SPACE_DOT_PATTERN;
import static com.htc.postprocessing.constant.PostProcessingConstant.SPACE_VALUE;
import static com.htc.postprocessing.constant.PostProcessingConstant.TRANSIT_BACKUP_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.TRANSIT_DIRECTORY;
import static com.htc.postprocessing.constant.PostProcessingConstant.XML_EXTENSION;
import static com.htc.postprocessing.constant.PostProcessingConstant.XML_TYPE;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.htc.postprocessing.constant.PostProcessingConstant;
import com.htc.postprocessing.scheduler.PostProcessingScheduler;
import com.htc.postprocessing.util.EmailUtil;
import com.htc.postprocessing.util.FTPServerUtility;
import com.htc.postprocessing.util.ZipUtility;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;
import com.microsoft.azure.storage.blob.CloudBlockBlob;
import com.microsoft.azure.storage.blob.ListBlobItem;

/**
 * @author kumar.charanswain
 *
 */

@Service
public class PostProcessingService {

	Logger logger = LoggerFactory.getLogger(PostProcessingScheduler.class);

	@Value("${blob.accont.name.key}")
	private String connectionNameKey;

	@Value("${blob.container.name}")
	private String containerName;

	@Value("#{'${state.allow.type}'.split(',')}")
	private List<String> stateAllowType;

	@Value("#{'${page.type}'.split(',')}")
	private List<String> pageTypeList;

	@Value("${sheet.number.type}")
	private String sheetNbrType;

	@Value("${ftp.server.name}")
	private String ftpHostName;

	@Value("${ftp.server.port}")
	private int ftpPort;

	@Value("${ftp.server.username}")
	private String ftpUserName;

	@Value("${ftp.server.password}")
	private String ftpPassword;

	@Autowired
	FTPServerUtility ftpServerUtility;

	@Autowired
	EmailUtil emailUtil;

	List<String> pclFileList = new LinkedList<String>();

	public String smartComPostProcessing() {
		String messageInfo = "smart comm post processing successfully";
		try {
			CloudBlobContainer container = containerInfo();
			String currentDate = currentDateTime();
			CloudBlobDirectory transitDirectory = getDirectoryName(container, TRANSIT_DIRECTORY,
					currentDate + "-" + PRINT_DIRECTORY);
			String transitTargetDirectory = TRANSIT_BACKUP_DIRECTORY + currentDate + "-";
			if (moveFileToTargetDirectory(currentDate, PRINT_DIRECTORY, transitTargetDirectory)) {
				messageInfo = processMetaDataInputFile(container, transitDirectory, currentDate);
			} else {
				messageInfo = "no file for post processing";
			}
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
			messageInfo = "error in copy file to blob directory";
		}
		return messageInfo;
	}

	public void archivePostProcessing() {
		try {
			String currentDate = currentDateTime();
			String targetDirectory = TRANSIT_BACKUP_DIRECTORY + currentDate + "-";
			if (moveFileToTargetDirectory(currentDate, ARCHIVE_DIRECTORY, targetDirectory)) {
				zipFileTransferToFTPServer(ARCHIVE_DIRECTORY, targetDirectory + ARCHIVE_DIRECTORY, currentDate);
				logger.info("file archive successfully");
			} else {
				logger.info("no file for archive to FTP server");
			}
		} catch (Exception exception) {
			logger.info("error in archive file:" + exception.getMessage());
		}
	}

	private boolean moveFileToTargetDirectory(String currentDate, String sourceDirectory, String targetDirectory) {
		boolean moveSuccess = false;
		BlobContainerClient blobContainerClient = getBlobContainerClient(connectionNameKey, containerName);
		Iterable<BlobItem> listBlobs = blobContainerClient.listBlobsByHierarchy(sourceDirectory);
		for (BlobItem blobItem : listBlobs) {
			BlobClient dstBlobClient = blobContainerClient.getBlobClient(targetDirectory + blobItem.getName());
			BlobClient srcBlobClient = blobContainerClient.getBlobClient(blobItem.getName());
			dstBlobClient.copyFromUrl(srcBlobClient.getBlobUrl());
			srcBlobClient.delete();
			moveSuccess = true;
		}
		return moveSuccess;
	}

	public void zipFileTransferToFTPServer(String directoryName, String targetDirectory, String currentDate)
			throws IOException {
		FTPClient ftpClient = new FTPClient();
		try {
			CloudBlobContainer container = containerInfo();
			BlobContainerClient blobContainerClient = getBlobContainerClient(connectionNameKey, containerName);
			CloudBlobDirectory transitDirectory = getDirectoryName(container, TRANSIT_BACKUP_DIRECTORY,
					currentDate + "-" + ARCHIVE_VALUE);
			Iterable<BlobItem> listBlobs = blobContainerClient.listBlobsByHierarchy(directoryName);
			List<String> files = new LinkedList<String>();
			String archiveZipFileName = currentDate + "-" + ARCHIVE_VALUE;
			List<String> archiveZipFileNames = new LinkedList<String>();
			for (BlobItem blobItem : listBlobs) {
				String fileNames[] = StringUtils.split(blobItem.getName(), "/");
				String fileName = fileNames[fileNames.length - 1];
				File file = new File(fileName);
				CloudBlockBlob blob = transitDirectory.getBlockBlobReference(fileName);
				blob.downloadToFile(file.getPath());
				files.add(fileName);
			}
			ZipUtility zipUtility = new ZipUtility();
			zipUtility.zipProcessing(files, archiveZipFileName);
			archiveZipFileNames.add(archiveZipFileName);
			ftpClient = ftpServerUtility.getFtpClient(ftpHostName, ftpPort, ftpUserName, ftpPassword);
			ftpServerUtility.fileTranserToFTPServer(ftpClient, archiveZipFileNames);
			deleteFiles(files);
		} catch (Exception exception) {
			logger.info("exception:" + exception.getMessage());
		}finally {
			ftpClient.disconnect();
		}
	}

	public String processMetaDataInputFile(CloudBlobContainer container, CloudBlobDirectory transitDirectory,
			String currentDate) throws Exception {
		ConcurrentHashMap<String, List<String>> postProcessMap = new ConcurrentHashMap<String, List<String>>();
		String message = "smart comm post processing successfully";
		try {
			Iterable<ListBlobItem> blobList = transitDirectory.listBlobs();

			for (ListBlobItem blobItem : blobList) {
				String fileName = getFileNameFromBlobURI(blobItem.getUri()).replace(SPACE_VALUE, EMPTY_SPACE);
				System.out.println("fileName:"+fileName);
				boolean stateType = checkStateType(fileName);
				if (stateType) {
					if (StringUtils.equalsIgnoreCase(FilenameUtils.getExtension(fileName), XML_TYPE)) {
						continue;
					}
					String fileNameNoExt = FilenameUtils.removeExtension(fileName);
					String[] stateAndSheetNameList = StringUtils.split(fileNameNoExt, "_");
					String stateAndSheetName = stateAndSheetNameList.length > 0
							? stateAndSheetNameList[stateAndSheetNameList.length - 1]
							: "";
					prepareMap(postProcessMap, stateAndSheetName, fileName);
				} else if (checkPageType(fileName)) {
					if (PostProcessingConstant.PDF_TYPE.equals(FilenameUtils.getExtension(fileName))) {
						continue;
					}
					prepareMap(postProcessMap, getSheetNumber(fileName, blobItem),
							StringUtils.replace(fileName, XML_EXTENSION, PDF_EXTENSION));
				} else {
					logger.info("unable to process:invalid document type ");
				}
			}
			if (postProcessMap.size() > 0) {
				message = mergePDF(postProcessMap, currentDate);
			} else {
				message = "unable to process :invalid state/document name";
			}
		} catch (Exception exception) {
			logger.info("Exception found:" + exception.getMessage());
		}
		return message;
	}

	private String getSheetNumber(String fileName, ListBlobItem blobItem) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			File file = new File(fileName);
			CloudBlob cloudBlob = (CloudBlob) blobItem;
			cloudBlob.downloadToFile(file.getPath());
			Document document = builder.parse(file);
			document.getDocumentElement().normalize();
			Element root = document.getDocumentElement();
			int sheetNumber = Integer.parseInt(root.getAttribute(sheetNbrType));
			if (sheetNumber <= 10) {
				file.delete();
				return String.valueOf(sheetNumber);
			}
			file.delete();
		} catch (Exception exception) {
			logger.info("Exception found:" + exception.getMessage());
		}
		return PostProcessingConstant.MULTIPAGE;
	}

	public BlobContainerClient getBlobContainerClient(String connectionNameKey, String containerName) {
		BlobServiceClient blobServiceClient = new BlobServiceClientBuilder().connectionString(connectionNameKey)
				.buildClient();
		BlobContainerClient blobContainerClient = blobServiceClient.getBlobContainerClient(containerName);
		return blobContainerClient;
	}

	// post merge PDF
	public String mergePDF(ConcurrentHashMap<String, List<String>> postProcessMap, String currentDate)
			throws IOException {
		String message = "smart comm post processing successfully";
		List<String> fileNameList = new LinkedList<String>();
		CloudBlobContainer container = containerInfo();
		for (String key : postProcessMap.keySet()) {
			try {
				PDFMergerUtility PDFMerger = new PDFMergerUtility();
				String claimNumber = "";
				String mergeClaimNbrPdfFile = "";
				fileNameList = postProcessMap.get(key);
				String bannerFileName = getBannerPage(key);
				File bannerFile = new File(bannerFileName);
				PDFMerger.addSource(bannerFileName);
				Collections.sort(fileNameList);
				CloudBlobDirectory transitDirectory = getDirectoryName(container, TRANSIT_BACKUP_DIRECTORY,
						currentDate + "-" + PRINT_DIRECTORY);
				for (String fileName : fileNameList) {
					File file = new File(fileName);
					CloudBlockBlob blob = transitDirectory.getBlockBlobReference(fileName);
					blob.downloadToFile(file.getAbsolutePath());
					PDFMerger.addSource(file.getPath());
				}
				String fileNoExt = fileNameList.get(fileNameList.size() - 1).replaceFirst(SPACE_DOT_PATTERN,
						EMPTY_VALUE);
				if (fileNoExt.length() >= 24) {
					claimNumber = fileNoExt.substring(14, 24);
				} else {
					claimNumber = fileNoExt;
				}
				mergeClaimNbrPdfFile = claimNumber + PDF_EXTENSION;
				PDFMerger.setDestinationFileName(mergeClaimNbrPdfFile);
				PDFMerger.mergeDocuments();
				emailUtil.emailProcess(mergeClaimNbrPdfFile);
				copyFileToProcessedDirectory(mergeClaimNbrPdfFile);
				//new File(mergeClaimNbrPdfFile).delete();
				convertPDFToPCL(claimNumber, fileNameList, currentDate);
				bannerFile.delete();
				deleteFiles(fileNameList);
			} catch (StorageException storageException) {
				logger.info("file not found for processing");
				if (fileNameList.size() > 0) {
					deleteFiles(fileNameList);
				}
				continue;
			} catch (Exception exception) {
				logger.info("Exception:" + exception.getMessage());
			}
		}
		if (pclFileList.size() > 0) {
			FTPClient ftpClient = ftpServerUtility.getFtpClient(ftpHostName, ftpPort, ftpUserName, ftpPassword);
			ftpServerUtility.fileTranserToFTPServer(ftpClient, pclFileList);
		}
		return message;
	}

	// post processing PDF to PCL conversion
	public void convertPDFToPCL(String claimNumber, List<String> fileNameList, String currentDate) throws IOException {
		try {
			String outputPclFile = claimNumber + PCL_EXTENSION;
			PDDocument document = new PDDocument();
			document.save(outputPclFile);

			copyFileToProcessedDirectory(outputPclFile);
			document.close();
			pclFileList.add(outputPclFile);

		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
		}
	}

	public void copyFileToProcessedDirectory(String fileName) {
		try {
			CloudBlobContainer container = containerInfo();
			CloudBlobDirectory processDirectory = getDirectoryName(container, TRANSIT_DIRECTORY, PROCESSED_DIRECTORY);
			File outputFileName = new File(fileName);
			CloudBlockBlob processSubDirectoryBlob = processDirectory.getBlockBlobReference(fileName);
			FileInputStream fileInputStream = new FileInputStream(outputFileName);
			processSubDirectoryBlob.upload(fileInputStream, outputFileName.length());
			fileInputStream.close();
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
		}
	}

	public boolean checkStateType(String fileName) {
		for (String state : stateAllowType) {
			if (fileName.contains(state)) {
				return true;
			}
		}
		return false;
	}

	public int totalNumberPages(String fileName) throws IOException {
		PDDocument pdfDocument = PDDocument.load(new File(fileName));
		return pdfDocument.getPages().getCount();
	}

	public boolean checkPageType(String fileName) {
		for (String pageType : pageTypeList) {
			if (fileName.contains(pageType)) {
				return true;
			}
		}
		return false;
	}

	public void deleteFiles(List<String> fileNameList) throws IOException {
		for (String fileName : fileNameList) {
			File file = new File(fileName);
			file.delete();
		}
	}

	public void prepareMap(ConcurrentHashMap<String, List<String>> postProcessMap, String key, String fileName)
			throws IOException {
		if (postProcessMap.containsKey(key)) {
			List<String> existingFileNameList = postProcessMap.get(key);
			existingFileNameList.add(fileName);
			postProcessMap.put(key, existingFileNameList);
		} else {
			List<String> existingFileNameList = new ArrayList<String>();
			existingFileNameList.add(fileName);
			postProcessMap.put(key, existingFileNameList);
		}
	}

	public String getBannerPage(String key)
			throws URISyntaxException, StorageException, FileNotFoundException, IOException {
		CloudBlobContainer container = containerInfo();
		CloudBlobDirectory transitDirectory = getDirectoryName(container, BANNER_DIRECTORY, "");
		String bannerFileName = BANNER_PAGE + key + PDF_EXTENSION;
		CloudBlockBlob blob = transitDirectory.getBlockBlobReference(bannerFileName);
		File source = new File(bannerFileName);
		blob.downloadToFile(source.getAbsolutePath());
		return bannerFileName;
	}

	public CloudBlobContainer containerInfo() {
		CloudBlobContainer container = null;
		try {
			CloudStorageAccount account = CloudStorageAccount.parse(connectionNameKey);
			CloudBlobClient serviceClient = account.createCloudBlobClient();
			container = serviceClient.getContainerReference(containerName);
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
		}
		return container;
	}

	public String currentDateTime() {
		Date date = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat(DATETIME_INTERVAL);
		return dateFormat.format(date);
	}

	public CloudBlobDirectory getDirectoryName(CloudBlobContainer container, String directoryName,
			String subDirectoryName) throws URISyntaxException {
		CloudBlobDirectory cloudBlobDirectory = container.getDirectoryReference(directoryName);
		if (StringUtils.isBlank(subDirectoryName)) {
			return cloudBlobDirectory;
		}
		return cloudBlobDirectory.getDirectoryReference(subDirectoryName);
	}

	private String getFileNameFromBlobURI(URI uri) {
		String[] fileNameList = uri.toString().split(PostProcessingConstant.FILE_SEPARATION);
		Optional<String> fileName = Optional.empty();
		if (fileNameList.length > 1)
			fileName = Optional.ofNullable(fileNameList[fileNameList.length - 1]);
		return fileName.get();
	}
}