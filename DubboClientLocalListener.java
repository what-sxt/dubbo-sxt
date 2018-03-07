package com.api.common.dubbo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.api.common.util.NetUtils;

/**
 * 监听本地是否启动provider
 * 
 * @author sxt
 */
public class DubboClientLocalListener implements BeanPostProcessor, ApplicationListener<ApplicationContextEvent>, InitializingBean {
	
	private Logger logger = LoggerFactory.getLogger(DubboClientLocalListener.class);
	
	private List<String> excludeHost = new ArrayList<String>();
	
	private String excludeHostPattern;
	
	private Set<String> clusters = new HashSet<String>();
	
	private final String CLUSTER_KEY = "intercept";
	
	private boolean isOnlie = false;
	
	List<String> LOCAL_HOSTS = NetUtils.localAddresses();
	
	public static String notifyFilePath = System.getProperty("user.home") + File.separator + "barrier" + File.separator;
	{
		File file = new File(notifyFilePath);
		if(!file.exists()) {
			file.mkdirs();
		}
	}
	
	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if(!isOnlie && bean instanceof ReferenceBean //
				    && StringUtils.isBlank(((ReferenceBean<?>) bean).getUrl()) // 手动配置优先
				    && !this.isExcludeHost()) {
			ReferenceBean<?> referen = (ReferenceBean<?>) bean;
			String interceptCluster = CLUSTER_KEY + "-" + referen.getCluster();
			if(clusters.add(interceptCluster)) {
				ExtensionLoader.getExtensionLoader(Cluster.class).addExtension(interceptCluster, LocalCluster.class);
			}
			referen.setCluster(interceptCluster);
		}
		return bean;
	}
	
	private boolean isExcludeHost() {
		for(String host : LOCAL_HOSTS) {
			if(excludeHost.contains(host) //
					|| (StringUtils.isNotBlank(excludeHostPattern) && host.matches(excludeHostPattern))) {
				return true;
			}
		}
		return false;
	}
	
	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	public List<String> getExcludeHost() {
		return excludeHost;
	}

	public void setExcludeHost(List<String> excludeHost) {
		this.excludeHost = excludeHost;
	}

	public String getExcludeHostPattern() {
		return excludeHostPattern;
	}

	public void setExcludeHostPattern(String excludeHostPattern) {
		this.excludeHostPattern = excludeHostPattern;
	}

	public String getNotifyFilePath() {
		return notifyFilePath;
	}

	public void setNotifyFilePath(String notifyFilePath) {
		DubboClientLocalListener.notifyFilePath = notifyFilePath;
	}

	@Override
	public void onApplicationEvent(ApplicationContextEvent event) {
		if(event instanceof ContextStartedEvent) {
			// help gc
			this.clusters = null;
		}
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		this.isOnlie = this.isOnline();
		if(isOnlie) {
			this.logger.info(System.lineSeparator() + ">>>>> >>>>> 生产环境, 不做任何处理");
			return ;
		}
		this.logger.info(System.lineSeparator() + this.toString() //
								+ System.lineSeparator() + ">>>>> >>>>> " + (this.isExcludeHost()? "本机不做本地服务监听": "本机做本地服务监听"));
		if(!this.isExcludeHost()) {
			try {
				Thread.sleep(3000);
			} catch (Exception e) {
				// ignore
			}
		}
	}
	
	/**
	 * 是否是线上配置
	 * 
	 * @return
	 */
	private boolean isOnline() {
		// 校验注册中心
		try {
			Properties cfg = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
			String configIp = cfg.getProperty("configIp");
			if(configIp.startsWith("192")) {
				return false;
			}
		} catch (IOException e) {
			// ignore
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder builer = new StringBuilder("===== ===== DubboClientLocalListener  ===== =====" + System.lineSeparator());
		builer.append("本机IP: " + this.LOCAL_HOSTS + System.lineSeparator());
		builer.append("不监听本地服务地址: ");
		for(int i = 0; i < excludeHost.size(); i++) {
			builer.append(excludeHost.get(i));
			if(i < excludeHost.size() - 1) {
				builer.append(", ");
			}
		}
		builer.append(System.lineSeparator());
		builer.append("正则过滤IP表达式: " + this.excludeHostPattern).append(System.lineSeparator())
			  .append("本地服务通知目录: " + DubboClientLocalListener.notifyFilePath);
		return builer.toString();
	}
	
}
