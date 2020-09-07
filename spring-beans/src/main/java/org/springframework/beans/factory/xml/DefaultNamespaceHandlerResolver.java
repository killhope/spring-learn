/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.FatalBeanException;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

/**
 * Default implementation of the {@link NamespaceHandlerResolver} interface.
 * Resolves namespace URIs to implementation classes based on the mappings
 * contained in mapping file.
 *
 * <p>By default, this implementation looks for the mapping file at
 * {@code META-INF/spring.handlers}, but this can be changed using the
 * {@link #DefaultNamespaceHandlerResolver(ClassLoader, String)} constructor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @since 2.0
 * @see NamespaceHandler
 * @see DefaultBeanDefinitionDocumentReader
 */
public class DefaultNamespaceHandlerResolver implements NamespaceHandlerResolver {

	/**
	 * The location to look for the mapping files. Can be present in multiple JAR files.
	 */
	public static final String DEFAULT_HANDLER_MAPPINGS_LOCATION = "META-INF/spring.handlers";


	/** Logger available to subclasses */
	protected final Log logger = LogFactory.getLog(getClass());

	/** ClassLoader to use for NamespaceHandler classes */
	@Nullable
	private final ClassLoader classLoader;

	/** Resource location to search for */
	private final String handlerMappingsLocation;

	/** Stores the mappings from namespace URI to NamespaceHandler class name / instance */
	@Nullable
	private volatile Map<String, Object> handlerMappings;


	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * default mapping file location.
	 * <p>This constructor will result in the thread context ClassLoader being used
	 * to load resources.
	 * @see #DEFAULT_HANDLER_MAPPINGS_LOCATION
	 */
	public DefaultNamespaceHandlerResolver() {
		this(null, DEFAULT_HANDLER_MAPPINGS_LOCATION);
	}

	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * default mapping file location.
	 * @param classLoader the {@link ClassLoader} instance used to load mapping resources
	 * (may be {@code null}, in which case the thread context ClassLoader will be used)
	 * @see #DEFAULT_HANDLER_MAPPINGS_LOCATION
	 */
	public DefaultNamespaceHandlerResolver(@Nullable ClassLoader classLoader) {
		this(classLoader, DEFAULT_HANDLER_MAPPINGS_LOCATION);
	}

	/**
	 * Create a new {@code DefaultNamespaceHandlerResolver} using the
	 * supplied mapping file location.
	 * @param classLoader the {@link ClassLoader} instance used to load mapping resources
	 * may be {@code null}, in which case the thread context ClassLoader will be used)
	 * @param handlerMappingsLocation the mapping file location
	 */
	public DefaultNamespaceHandlerResolver(@Nullable ClassLoader classLoader, String handlerMappingsLocation) {
		Assert.notNull(handlerMappingsLocation, "Handler mappings location must not be null");
		this.classLoader = (classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader());
		this.handlerMappingsLocation = handlerMappingsLocation;
	}


	/**
	 * Locate the {@link NamespaceHandler} for the supplied namespace URI
	 * from the configured mappings.
	 * @param namespaceUri the relevant namespace URI
	 * @return the located {@link NamespaceHandler}, or {@code null} if none found
	 */
	@Override
	@Nullable
	public NamespaceHandler resolve(String namespaceUri) {
		//1.拿到“META-INF/spring.handlers”（默认地址，可以自定义）下所有命名空间和对应的 handler
		//例如："http://www.springframework.org/schema/aop" -> "org.springframework.aop.config.AopNamespaceHandler"
		Map<String, Object> handlerMappings = getHandlerMappings();
		//2.拿到当前命名空间对应的 handler（可能是 handler 的 className，也可能是已经实例化的 handler）
		Object handlerOrClassName = handlerMappings.get(namespaceUri);
		if (handlerOrClassName == null) {
			//2.1 如果不存在 namespaceUri 对应的 handler，则返回 null
			return null;
		}
		else if (handlerOrClassName instanceof NamespaceHandler) {
			//2.2 如果是已经实例化的 handler，则直接强转返回
			return (NamespaceHandler) handlerOrClassName;
		}
		else {
			//2.3 如果是 handler 的 className
			String className = (String) handlerOrClassName;
			try {
				//2.3.1 根据 className，使用类加载器拿到该类
				Class<?> handlerClass = ClassUtils.forName(className, this.classLoader);
				//2.3.2 校验是否是继承自 NamespaceHandler（所有的 handler 都继承自 NamespaceHandler）
				if (!NamespaceHandler.class.isAssignableFrom(handlerClass)) {
					throw new FatalBeanException("Class [" + className + "] for namespace [" + namespaceUri +
							"] does not implement the [" + NamespaceHandler.class.getName() + "] interface");
				}
				//2.3.3 使用无参构造函数实例化 handlerClass 类
				NamespaceHandler namespaceHandler = (NamespaceHandler) BeanUtils.instantiateClass(handlerClass);
				//2.3.4 调用 handler 类的初始化方法(将命名空间下的节点名和对应的解析器注册到 parsers 缓存中)
				//有很多个实现类，分别对应了不同的命名空间 Handler,最常见的命名空间：http://www.springframework.org/schema/context 来举例，对应的 handler 为 ContextNamespaceHandler
				namespaceHandler.init();
				//2.3.5 将实例化的handler放到缓存，替换原来的className
				//原来为: namespaceUri -> handler的className，会被覆盖成: namespaceUri -> 实例化的handler
				handlerMappings.put(namespaceUri, namespaceHandler);
				return namespaceHandler;
			}
			catch (ClassNotFoundException ex) {
				throw new FatalBeanException("Could not find NamespaceHandler class [" + className +
						"] for namespace [" + namespaceUri + "]", ex);
			}
			catch (LinkageError err) {
				throw new FatalBeanException("Unresolvable class definition for NamespaceHandler class [" +
						className + "] for namespace [" + namespaceUri + "]", err);
			}
		}
	}

	/**
	 * Load the specified NamespaceHandler mappings lazily.
	 */
	private Map<String, Object> getHandlerMappings() {
		//1.如果 handlerMappings 已经加载过，则直接返回
		Map<String, Object> handlerMappings = this.handlerMappings;
		if (handlerMappings == null) {
			synchronized (this) {
				//2.如果 handlerMappings 还没加载过，则进行加载
				handlerMappings = this.handlerMappings;
				if (handlerMappings == null) {
					if (logger.isDebugEnabled()) {
						logger.debug("Loading NamespaceHandler mappings from [" + this.handlerMappingsLocation + "]");
					}
					try {
						//2.1 使用给定的类加载器从指定的类路径资源加载所有属性
						Properties mappings =
								PropertiesLoaderUtils.loadAllProperties(this.handlerMappingsLocation, this.classLoader);
						if (logger.isDebugEnabled()) {
							logger.debug("Loaded NamespaceHandler mappings: " + mappings);
						}
						handlerMappings = new ConcurrentHashMap<>(mappings.size());
						//2.2 将 Properties 转换成 Map, mappings -> handlerMappings
						CollectionUtils.mergePropertiesIntoMap(mappings, handlerMappings);
						this.handlerMappings = handlerMappings;
					}
					catch (IOException ex) {
						throw new IllegalStateException(
								"Unable to load NamespaceHandler mappings from location [" + this.handlerMappingsLocation + "]", ex);
					}
				}
			}
		}
		return handlerMappings;
	}


	@Override
	public String toString() {
		return "NamespaceHandlerResolver using mappings " + getHandlerMappings();
	}

}
