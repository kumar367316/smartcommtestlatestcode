package com.htc.postprocessing.util;

import static com.htc.postprocessing.constant.PostProcessingConstant.DATETIME_INTERVAL;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FTPServerUtility {

	Logger logger = LoggerFactory.getLogger(FTPServerUtility.class);

	// file transfer to ftp server
	public void fileTranserToFTPServer(FTPClient ftpClient, List<String> fileList)
			throws IOException {
		FileInputStream fileInputStream = null;
		try {
			for (String fileName : fileList) {
				fileInputStream = new FileInputStream(fileName);
				ftpClient.storeFile(fileName, fileInputStream);
				fileInputStream.close();
				File archiveFile = new File(fileName);
				archiveFile.delete();
			}
		} catch (Exception exception) {
			logger.info("Exception:" + exception.getMessage());
		} finally {
			ftpClient.disconnect();
		}
	}

	public String currentDateTime() {
		Date date = new Date();
		SimpleDateFormat dateFormat = new SimpleDateFormat(DATETIME_INTERVAL);
		return dateFormat.format(date);
	}

	public FTPClient getFtpClient(String server, int port, String userName, String password) {
		FTPClient ftpClient = new FTPClient();
		try {
			ftpClient.connect(server, port);
			ftpClient.login(userName, password);
			ftpClient.enterLocalPassiveMode();
			ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
			ftpClient.makeDirectory(currentDateTime());
			ftpClient.changeWorkingDirectory(currentDateTime());
		} catch (Exception exception) {
			logger.info("exception:" + exception.getMessage());
		}
		return ftpClient;
	}
}
