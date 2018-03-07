package com.api.common.dubbo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextStartedEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.api.common.util.IdGen;
import com.api.common.util.NetUtils;

/**
 * dubbo服务注册拦截, 方便本地调试
 * 
 * @author sxt
 */
public class DubboProviderRegisteBarrier implements BeanPostProcessor, ApplicationListener<ApplicationContextEvent>, InitializingBean {
	
	private Logger logger = LoggerFactory.getLogger(DubboProviderRegisteBarrier.class);

	private List<String> registerHost = new ArrayList<String>();
	
	private String registerHostPattern;
	
	List<String> LOCAL_HOSTS = NetUtils.localAddresses();
	
	private boolean isOnlie = false;
	
	private String notifyFilePath = System.getProperty("user.home") + File.separator + "barrier" + File.separator;
	{
		File file = new File(this.notifyFilePath);
		if(!file.exists()) {
			file.mkdirs();
		}
	}
	
	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		if(!isOnlie && bean instanceof RegistryConfig && !this.isRegisteHost()) {
			RegistryConfig cf = (RegistryConfig) bean;
			// 手动配置优先
			if(cf.isRegister() != null) {
				return bean;
			}
			cf.setRegister(false);
		}
		return bean;
	}
	
	@Override
	public void onApplicationEvent(ApplicationContextEvent event) {
		if(!isOnlie && event instanceof ContextStartedEvent //
							&& !this.isRegisteHost()) {
			this.notify(((ContextStartedEvent)event).getApplicationContext());
		}
//		if(event instanceof ContextClosedEvent //
//						&& !this.isRegisteHost()) {
//		}
	}
	
	@SuppressWarnings("rawtypes")
	private void notify(ApplicationContext context) {
		ApplicationConfig app = context.getBean(ApplicationConfig.class);
		Map<String, ServiceBean> providers = context.getBeansOfType(ServiceBean.class);
		Properties prop = new Properties();
		for(String key : providers.keySet()) {
			ServiceBean bean = providers.get(key);
			prop.put(this.notifyKey(bean, bean.getProtocol()), key);
		}
		if(prop.size() > 0) {
			try {
				delHistoryIfNecessary(app.getName());
				FileOutputStream out = new FileOutputStream(notifyFilePath + File.separator + app.getName() + "-" + IdGen.get().nextId() + ".properties");
				prop.store(out, "");
			} catch (Exception e) {
				logger.error("保存通知文件", e);
			}
		}
	}
	
	private void delHistoryIfNecessary(String prefixName) {
		File dir = new File(this.notifyFilePath);
		File[] fs = dir.listFiles(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.startsWith(prefixName);
			}
		});
		for(File file : fs) {
			file.delete();
		}
	}
	
	private String notifyKey(ServiceBean<?> bean, ProtocolConfig cfg) {
		return StringUtils.isBlank(bean.getGroup())? bean.getInterface() + "$" + cfg.getPort(): bean.getInterface() + "-" + bean.getGroup() + "$" + cfg.getPort();
	}
	
	/**
	 * 只要存在一个不注册ip就返回false
	 * 
	 * @return
	 */
	private boolean isRegisteHost() {
		for(String host : this.LOCAL_HOSTS) {
			if(!registerHost.contains(host) //
					&& !(StringUtils.isNotBlank(registerHostPattern) && host.matches(registerHostPattern))) {
				return false;
			}
		}
		return true;
	}
	
	public List<String> getRegisterHost() {
		return registerHost;
	}

	public void setRegisterHost(List<String> registerHost) {
		this.registerHost = registerHost;
	}

	public String getRegisterHostPattern() {
		return registerHostPattern;
	}

	public void setRegisterHostPattern(String registerHostPattern) {
		this.registerHostPattern = registerHostPattern;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		this.isOnlie = this.isOnline();
		if(isOnlie) {
			this.logger.info(System.lineSeparator() + ">>>>> >>>>> 生产环境, 不做任何处理");
			return ;
		}
		this.logger.info(System.lineSeparator() + this.toString() //
							+ System.lineSeparator() + ">>>>> >>>>> " + (this.isRegisteHost()? "本机做服务注册": "本机不做服务注册"));
		if(!this.isRegisteHost()) {
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
		StringBuilder builer = new StringBuilder("===== ===== DubboProviderRegisteBarrier  ===== =====" + System.lineSeparator());
		builer.append("本机IP: " + this.LOCAL_HOSTS + System.lineSeparator());
		builer.append("注册服务地址: ");
		for(int i = 0; i < registerHost.size(); i++) {
			builer.append(registerHost.get(i));
			if(i < registerHost.size() - 1) {
				builer.append(", ");
			}
		}
		builer.append(System.lineSeparator());
		builer.append("正则过滤IP表达式: " + this.registerHostPattern).append(System.lineSeparator())
			  .append("本地服务通知目录: " + this.notifyFilePath);
		return builer.toString();
	}

}
