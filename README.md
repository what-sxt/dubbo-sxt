# dubbo-sxt

<pre>
<font style="font-family:Microsoft YaHei">
    1. 本地调试，customer<font style="font-style:italic">不希望</font> 配置url='dubbo//本机ip:端口号'.
    2. 本地服务，<font style="font-style:italic">不希望</font> 暴露给其他调用者，需要配置register='false'.
    <font style="font-style:italic">3. 不小心提交到了测试环境，麻烦。</font>
</font>
</pre>
***
## 添加spring配置
#### 消费者配置
<pre>
<code>
&lt;bean id=&quot;dubboClientListener&quot; class=&quot;com.api.common.dubbo.DubboClientLocalListener&quot;&gt;
	 	&lt;!-- 以下地址不做本地服务监听 --&gt;
		&lt;property name=&quot;excludeHost&quot;&gt;
			&lt;list&gt;
				&lt;value&gt;192.168.0.18&lt;/value&gt;
			&lt;/list&gt;
		&lt;/property&gt;
		&lt;property name=&quot;excludeHostPattern&quot; value=&quot;10.*&quot;/&gt;
&lt;/bean&gt; 
</code>
</pre>
#### 服务配置
<pre>
<code>
&lt;bean id=&quot;dubboProviderRegisteBarrier&quot; class=&quot;com.api.common.dubbo.DubboProviderRegisteBarrier&quot;&gt;
		&lt;!-- 以下地址做服务注册 --&gt;
		&lt;property name=&quot;registerHost&quot;&gt;
			&lt;list&gt;
				&lt;value&gt;192.168.0.18&lt;/value&gt;
			&lt;/list&gt;
		&lt;/property&gt;
		&lt;property name=&quot;registerHostPattern&quot; value=&quot;10.*&quot;/&gt;
&lt;/bean&gt;
</code>
</pre>

<pre>
<font style="font-family:Microsoft YaHei">
    1. 本地调试，不需要配置url.
    2. 本地服务，不需要配置register='false'.
    3. 本地启动优先调用本地服务，调用本地服务失败，调用远程服务.
    4. 可以使用dubbo的Filter做，但此示例使用的是Cluster.
</font>
</pre>






