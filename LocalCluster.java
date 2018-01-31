package com.api.common.dubbo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.cluster.support.FailbackClusterInvoker;
import com.alibaba.dubbo.rpc.cluster.support.FailfastClusterInvoker;
import com.alibaba.dubbo.rpc.cluster.support.FailoverClusterInvoker;

/**
 * 本地 服务cluster
 * 
 * @author sxt
 */
public class LocalCluster implements Cluster {
	
	private Logger logger = Logger.getLogger(this.getClass());
	
	private String localHost = NetUtils.getLocalHost();
	
	private String filePath = DubboClientLocalListener.notifyFilePath;
	
	ScheduledExecutorService scanner = Executors.newScheduledThreadPool(1);
	{
		scanner.scheduleAtFixedRate(new Runnable() {
			
			@Override
			public void run() {
				LocalCluster.this.sannerLocal();
			}
		}, 0, 10, TimeUnit.MILLISECONDS);
	}

	// file name : FileContent
	private ConcurrentHashMap<String, FileContent> cacheLocalProviders = new ConcurrentHashMap<String,  FileContent>(30);
	
	// interface name : Invoker
	private ConcurrentHashMap<String, Invoker<Object>> cacheInvoker = new ConcurrentHashMap<String,  Invoker<Object>>(30);
	
	private ThreadLocal<Pair> romotingFileName = new ThreadLocal<Pair>();
	
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	
	private final String INTERCEPT_CLUSTER_NAME_PREFIX = "intercept";
	
	private final int startIndex = INTERCEPT_CLUSTER_NAME_PREFIX.length() + 1;
	
	@Override
	public <T> Invoker<T> join(Directory<T> directory) throws RpcException {
		String cluster = directory.getUrl().getParameter("cluster");
		if(!cluster.startsWith(INTERCEPT_CLUSTER_NAME_PREFIX)) {
			// 默认 failover
			cluster = this.INTERCEPT_CLUSTER_NAME_PREFIX + "-failover";
		}
		String delegateName = cluster.substring(this.startIndex);
		logger.info("use cluster invoker " + delegateName);
		return new InterceptInvoker<T>(directory, delegateName);
	}
	
	private void sannerLocal() {
		try {
			File dirFiles = new File(filePath);
			if(!dirFiles.exists()) {
				return ;
			}
			Set<String> localFileNames = new HashSet<String>();
			
			lock.writeLock().lock();
			try {
				for(File providerFile : dirFiles.listFiles()) {
					localFileNames.add(providerFile.getName());
					FileContent fileContent = cacheLocalProviders.get(providerFile.getName());
					if(fileContent == null) {
						cacheLocalProviders.put(providerFile.getName(), new FileContent(providerFile.lastModified(), this.resloveProperties(providerFile)));
						continue ;
					}
					if(fileContent.lastModified != providerFile.lastModified()) {
						cacheLocalProviders.put(providerFile.getName(), new FileContent(providerFile.lastModified(), this.resloveProperties(providerFile)));
					}
				}
				this.clearCacheLastModified(localFileNames);
			} catch (Exception e) {
				logger.error(e.getMessage());
			} finally {
				lock.writeLock().unlock();
			}
		} catch (Exception e) {
			logger.error("检查本地文件", e);
		}
	}
	
	private void clearCacheLastModified(Set<String> localFileNames) {
		ConcurrentHashMap<String, FileContent> cacheProviders = this.cacheLocalProviders;
		for(String fileName : cacheProviders.keySet()) {
			if(!localFileNames.contains(fileName)) {
				cacheProviders.remove(fileName);
			}
		}
	}
	
	private Properties resloveProperties(File file) throws FileNotFoundException, IOException {
		Properties prop = new Properties();
		prop.load(new FileInputStream(file));
		return prop;
	}
	
	class InterceptInvoker<T> extends FailbackClusterInvoker<T> {
		
		private String cleanErrorMsg = "Failed to invoke the";
		
		private String delegateName;
		
		private Invoker<T> delegate;
		
		public InterceptInvoker(Directory<T> directory, String delegateName) {
			super(directory);
			this.delegateName = delegateName;
		}
		
		@Override
		protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance)
				throws RpcException {
			if(delegateName.equals("failover") || StringUtils.isBlank(delegateName) || String.valueOf(delegateName).equals("null")) {
				FailoverClusterInvoker<T> failover = (FailoverClusterInvoker<T>) this.resloverInvoker(FailoverClusterInvoker.class, directory);
				return failover.doInvoke(invocation, invokers, loadbalance);
			}
			else if(delegateName.equals("failfast")) {
				FailfastClusterInvoker<T> failover = (FailfastClusterInvoker<T>) this.resloverInvoker(FailfastClusterInvoker.class, directory);
				return failover.doInvoke(invocation, invokers, loadbalance);
			}
			else if(delegateName.equals("failback")) {
				// FailbackClusterInvoker doInvoke 是protected
				this.delegate = this;
				return super.doInvoke(invocation, invokers, loadbalance);
			}
			else {
				throw new RuntimeException("不支持【" + delegateName + "】, 手动添加一下吧-_-|| ==>>> LocalCluster.InterceptInvoker.doInvoke()方法中" );
			}
		}
		
		@SuppressWarnings({ "rawtypes", "unchecked" })
		private Invoker<T> resloverInvoker(Class<? extends Invoker> invokerClazz, Directory<T> directory) {
			try {
				if(this.delegate != null && this.delegate.getClass() == invokerClazz) {
					return this.delegate;
				}
				if(this.delegate != null) {
					logger.warn("WTF...");
				}
				// create... ...
				Constructor<? extends Invoker> constructor = invokerClazz.getConstructor(Directory.class);
				this.delegate = constructor.newInstance(directory);
				return this.delegate;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public Result invoke(Invocation invocation) throws RpcException {
			Result result = null;
			try {
				result = super.invoke(invocation);
			} catch (Exception e) {
				logger.error(e.getMessage());
				//  Failed to invoke the
				if(e.getMessage().startsWith(cleanErrorMsg) //
							&& e.getMessage().contains(localHost)) {
					this.clearInvokeFail();
					// 远程provider重试一次
					result = this.delegate.invoke(invocation);
				}
			}
			return result;
		}
		
		private void clearInvokeFail() {
			lock.readLock().lock();
			try {
				// 清理cache
				Pair remotingPair = romotingFileName.get();
				if(remotingPair == null) {
					// 已经清理过了
					return ;
				}
				cacheLocalProviders.remove(remotingPair.fileName);
				cacheInvoker.remove(remotingPair.interfaceName);
				romotingFileName.remove();
				// 删除文件
				File delFile = new File(filePath + File.separator + remotingPair.fileName);
				if(delFile.exists() && delFile.isFile() && !delFile.delete()) {
					logger.warn("删除文件 【" + filePath + File.separator + remotingPair.fileName + "】, 失败需要手动删除");
				}
				else {
					logger.info("清理文件【" + delFile.getName() + "】, 成功... ...");
				}
			} catch (Exception e) {
				logger.error("清理本地请求失败", e);
			} finally {
				lock.readLock().unlock();
			}
		}

		/**
		 * 选择 invoker
		 */
		@Override
		@SuppressWarnings("unchecked")
		protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
			if(cacheLocalProviders.isEmpty()) {
				return super.list(invocation);
			}
			List<Invoker<T>> remotingInvokers = super.list(invocation);
			Invoker<T> remotingInvoker = remotingInvokers.get(0);
			String key = this.resolveInterfaceKey(remotingInvoker.getUrl());
			
			// local
			if(!this.loadLocal(key)) {
				return remotingInvokers;
			}
			logger.info("======>>> 使用本地" + localHost + ":" + remotingInvoker.getUrl().getPort() //
								+ "【" + remotingInvoker.getInterface() + "." + invocation.getMethodName() + "】... ...");
			// local start
			if(cacheInvoker.containsKey(key)) {
				Invoker<T> localInvoker = (Invoker<T>) cacheInvoker.get(key);
				return Arrays.asList(localInvoker);
			}
			
			// TODO 默认先使用dubbo protocol了
			Protocol protocol = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension("dubbo");
			Invoker<T> localInvoker = protocol.refer(remotingInvoker.getInterface(), //
					remotingInvoker.getUrl().setHost(localHost).setPort(remotingInvoker.getUrl().getPort())); // 本地url
			cacheInvoker.put(key, (Invoker<Object>)localInvoker);
			return Arrays.asList(localInvoker);
		}
		
		private boolean loadLocal(String key) {
			if(cacheLocalProviders.size() == 0) {
				return false;
			}
			ConcurrentHashMap<String, FileContent> map = cacheLocalProviders;
			for(String fileName : map.keySet()) {
				FileContent fileContent = map.get(fileName);
				if(fileContent.prop.contains(key)) {
					romotingFileName.set(new Pair(key, fileName));
					return true;
				}
			}
			return false;
		}
		
		private String resolveInterfaceKey(URL url) {
			String invokeInterface = url.getServiceInterface();
			String group = url.getParameter(Constants.GROUP_KEY);
			return StringUtils.isBlank(group)? invokeInterface: invokeInterface + "-" + group;
		}

	}
	
	private class Pair {
		
		String interfaceName;
		
		String fileName;
		
		public Pair(String interfaceName, String fileName) {
			this.interfaceName = interfaceName;
			this.fileName = fileName;
		}
		
	}
	
	private class FileContent {
		
		Long lastModified;
		
		Properties prop;

		public FileContent(Long lastModified, Properties prop) {
			super();
			this.lastModified = lastModified;
			this.prop = prop;
		}
		
	}

}
