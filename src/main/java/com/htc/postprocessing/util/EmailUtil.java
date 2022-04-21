package com.htc.postprocessing.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.ui.freemarker.FreeMarkerTemplateUtils;

import com.htc.postprocessing.email.api.dto.MailRequest;
import com.htc.postprocessing.email.api.dto.MailResponse;

import freemarker.template.Configuration;
import freemarker.template.Template;

@Component
public class EmailUtil {

	Logger logger = LoggerFactory.getLogger(FTPServerUtility.class);

	@Autowired
	private JavaMailSender sender;

	@Autowired
	private Configuration config;

	public MailResponse sendEmail(MailRequest request, Map<String, Object> model) {
		MailResponse response = new MailResponse();
		MimeMessage message = sender.createMimeMessage();
		try {
			MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
					StandardCharsets.UTF_8.name());
			helper.addAttachment(request.getFileName(), new File(request.getFileName()));
			Template t = config.getTemplate("email-template.ftl");
			String html = FreeMarkerTemplateUtils.processTemplateIntoString(t, model);

			helper.setTo(request.getTo());
			helper.setText(html, true);
			helper.setSubject(request.getSubject());
			helper.setFrom(request.getFrom());
			sender.send(message);

			response.setMessage("mail send to : " + request.getTo());
			response.setStatus(Boolean.TRUE);
		} catch (Exception exception) {
			logger.info("exception:");
		}

		return response;
	}

	public void emailProcess(String fileName) {

		MailRequest mailRequest = new MailRequest();
		mailRequest.setFrom("kumarswain367@gmail.com");
		mailRequest.setTo("swainkumar10@gmail.com");
		mailRequest.setSubject("post processing mail");
		mailRequest.setFileName(fileName);
		mailRequest.setName("smart comm post processing");

		Map<String, Object> model = new HashMap<>();
		model.put("Name", mailRequest.getName());
		model.put("location", "Bangalore,India");
		model.put("fileName", mailRequest.getFileName());
		sendEmail(mailRequest, model);
	}

}
