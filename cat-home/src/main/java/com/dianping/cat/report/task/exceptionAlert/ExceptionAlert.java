package com.dianping.cat.report.task.exceptionAlert;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.Constants;
import com.dianping.cat.consumer.company.model.entity.Domain;
import com.dianping.cat.consumer.company.model.entity.ProductLine;
import com.dianping.cat.consumer.metric.ProductLineConfigManager;
import com.dianping.cat.consumer.top.TopAnalyzer;
import com.dianping.cat.consumer.top.model.entity.TopReport;
import com.dianping.cat.helper.TimeUtil;
import com.dianping.cat.home.dependency.exception.entity.ExceptionLimit;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.dianping.cat.report.page.top.TopMetric;
import com.dianping.cat.report.page.top.TopMetric.Item;
import com.dianping.cat.report.task.metric.AlertConfig;
import com.dianping.cat.service.ModelRequest;
import com.dianping.cat.service.ModelResponse;
import com.dianping.cat.system.config.ExceptionThresholdConfigManager;
import com.dianping.cat.system.tool.MailSMS;

public class ExceptionAlert implements Task, LogEnabled {

	@Inject
	private AlertConfig m_alertConfig;

	@Inject
	private MailSMS m_mailSms;

	@Inject
	protected ProductLineConfigManager m_productLineConfigManager;

	@Inject
	private ExceptionThresholdConfigManager m_configManager;

	@Inject(type = ModelService.class, value = TopAnalyzer.ID)
	private ModelService<TopReport> m_topService;

	private static final long DURATION = TimeUtil.ONE_MINUTE;

	private static final int ALERT_PERIOD = 1;

	private static final int WARN_FLAG = 1;

	private static final int ERROR_FLAG = 2;

	private Logger m_logger;

	private TopMetric buildTopMetric(Date date) {
		TopReport topReport = queryTopReport(date);
		TopMetric topMetric = new TopMetric(ALERT_PERIOD, Integer.MAX_VALUE, m_configManager);

		topMetric.setStart(date).setEnd(new Date(date.getTime() + TimeUtil.ONE_MINUTE));
		topMetric.visitTopReport(topReport);
		return topMetric;
	}

	private TopReport queryTopReport(Date start) {
		String domain = Constants.CAT;
		String date = String.valueOf(start.getTime());
		ModelRequest request = new ModelRequest(domain, start.getTime()).setProperty("date", date);

		if (m_topService.isEligable(request)) {
			ModelResponse<TopReport> response = m_topService.invoke(request);
			TopReport report = response.getModel();

			return report;
		} else {
			throw new RuntimeException("Internal error: no eligable top service registered for " + request + "!");
		}
	}

	@Override
	public void run() {
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		boolean active = true;
		while (active) {
			int minute = Calendar.getInstance().get(Calendar.MINUTE);
			String minuteStr = String.valueOf(minute);

			if (minute < 10) {
				minuteStr = '0' + minuteStr;
			}
			Transaction t = Cat.newTransaction("ExceptionAlert", "M" + minuteStr);
			long current = System.currentTimeMillis();

			try {
				TopMetric topMetric = buildTopMetric(new Date(current - TimeUtil.ONE_MINUTE));
				Collection<List<Item>> items = topMetric.getError().getResult().values();
				Map<String, List<AlertException>> alertExceptions = getAlertExceptions(items);

				for (Entry<String, List<AlertException>> entry : alertExceptions.entrySet()) {
					sendAlertForDomain(entry.getKey(), entry.getValue());
				}
				t.setStatus(Transaction.SUCCESS);
			} catch (Exception e) {
				t.setStatus(e);
			} finally {
				t.complete();
			}
			long duration = System.currentTimeMillis() - current;

			try {
				if (duration < DURATION) {
					Thread.sleep(TimeUtil.ONE_MINUTE);
				}
			} catch (InterruptedException e) {
				active = false;
			}
		}
	}

	private List<AlertException> findOrCreateDomain(Map<String, List<AlertException>> exceptions, String domain) {
		if (exceptions.get(domain) == null) {
			List<AlertException> exception = new ArrayList<AlertException>();

			exceptions.put(domain, exception);
			return exception;
		}
		return exceptions.get(domain);
	}

	private Map<String, List<AlertException>> getAlertExceptions(Collection<List<Item>> items) {
		Map<String, List<AlertException>> alertExceptions = new LinkedHashMap<String, List<AlertException>>();

		for (List<Item> item : items) {
			for (Item i : item) {
				String domain = i.getDomain();

				for (Entry<String, Double> entry : i.getException().entrySet()) {
					double value = entry.getValue().doubleValue();
					double warnLimit = -1;
					double errorLimit = -1;

					if (m_configManager != null) {
						ExceptionLimit exceptionLimit = m_configManager.queryDomainExceptionLimit(domain, entry.getKey());
						
						if (exceptionLimit == null) {
							exceptionLimit = m_configManager.queryDomainTotalLimit(domain);
						}
						if (exceptionLimit != null) {
							warnLimit = exceptionLimit.getWarning();
							errorLimit = exceptionLimit.getError();
						}
					}
					if (errorLimit > 0 && value > errorLimit) {
						findOrCreateDomain(alertExceptions, domain).add(new AlertException(domain, ERROR_FLAG, value));
					} else if (warnLimit > 0 && value > warnLimit) {
						findOrCreateDomain(alertExceptions, domain).add(new AlertException(domain, WARN_FLAG, value));
					}
				}
			}
		}
		return alertExceptions;
	}

	private ProductLine getProductLineByDomain(String domain) {
		Collection<ProductLine> productLines = m_productLineConfigManager.queryAllProductLines().values();
		for (ProductLine product : productLines) {
			Map<String, Domain> domains = product.getDomains();
			if (domains.containsKey(domain)) {
				return product;
			}
		}
		return null;
	}

	private void sendAlertForDomain(String domain, List<AlertException> exceptions) {

		ProductLine productLine = getProductLineByDomain(domain);
		List<String> emails = m_alertConfig.buildMailReceivers(productLine);
		List<String> phones = m_alertConfig.buildSMSReceivers(productLine);
		String title = "[ " + productLine.getId() + ":" + domain + " ] " + "exception alert !";
		List<String> errorExceptions = new ArrayList<String>();
		List<String> warnExceptions = new ArrayList<String>();

		for (AlertException exception : exceptions) {
			if (exception.getAlertFlag() == WARN_FLAG) {
				warnExceptions.add(exception.getName());
			} else if (exception.getAlertFlag() == ERROR_FLAG) {
				errorExceptions.add(exception.getName());
			}
		}

		String mailContent = "Exception Alert! [" + domain + "] : " + exceptions.toString();

		m_logger.info(title + " " + mailContent + " " + emails);
		m_mailSms.sendEmail(title, mailContent, emails);

		String smsContent = "Exception Alert! [" + domain + "] : " + errorExceptions.toString();

		m_mailSms.sendSms(title + " " + smsContent, smsContent, phones);
		Cat.logEvent("MetricAlert", productLine.getId(), Event.SUCCESS, title + "  " + mailContent);
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public String getName() {
		return "exception-alert";
	}

	@Override
	public void shutdown() {
	}

	public class AlertException {

		private String m_name;

		private int m_alertFlag;

		private double m_count;

		public AlertException(String name, int alertFlag, double count) {
			m_name = name;
			m_alertFlag = alertFlag;
			m_count = count;
		}

		@Override
		public String toString() {
			return "{exception_name=" + m_name + ", exception_count=" + m_count + "}";
		}

		public int getAlertFlag() {
			return m_alertFlag;
		}

		public String getName() {
			return m_name;
		}
	}
}
