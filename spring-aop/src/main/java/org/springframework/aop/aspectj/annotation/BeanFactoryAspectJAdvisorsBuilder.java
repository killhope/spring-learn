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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Advisor;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Helper for retrieving @AspectJ beans from a BeanFactory and building
 * Spring Advisors based on them, for use with auto-proxying.
 *
 * @author Juergen Hoeller
 * @since 2.0.2
 * @see AnnotationAwareAspectJAutoProxyCreator
 */
public class BeanFactoryAspectJAdvisorsBuilder {

	private final ListableBeanFactory beanFactory;

	private final AspectJAdvisorFactory advisorFactory;

	@Nullable
	private volatile List<String> aspectBeanNames;

	private final Map<String, List<Advisor>> advisorsCache = new ConcurrentHashMap<>();

	private final Map<String, MetadataAwareAspectInstanceFactory> aspectFactoryCache = new ConcurrentHashMap<>();


	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory) {
		this(beanFactory, new ReflectiveAspectJAdvisorFactory(beanFactory));
	}

	/**
	 * Create a new BeanFactoryAspectJAdvisorsBuilder for the given BeanFactory.
	 * @param beanFactory the ListableBeanFactory to scan
	 * @param advisorFactory the AspectJAdvisorFactory to build each Advisor with
	 */
	public BeanFactoryAspectJAdvisorsBuilder(ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {
		Assert.notNull(beanFactory, "ListableBeanFactory must not be null");
		Assert.notNull(advisorFactory, "AspectJAdvisorFactory must not be null");
		this.beanFactory = beanFactory;
		this.advisorFactory = advisorFactory;
	}


	/**
	 * Look for AspectJ-annotated aspect beans in the current bean factory,
	 * and return to a list of Spring AOP Advisors representing them.
	 * <p>Creates a Spring Advisor for each AspectJ advice method.
	 * @return the list of {@link org.springframework.aop.Advisor} beans
	 * @see #isEligibleBean
	 */
	public List<Advisor> buildAspectJAdvisors() {
		List<String> aspectNames = this.aspectBeanNames;
		//如果 aspectNames 为空，说明没有解析过
		if (aspectNames == null) {
			synchronized (this) {
				aspectNames = this.aspectBeanNames;
				if (aspectNames == null) {
					List<Advisor> advisors = new ArrayList<>();
					aspectNames = new ArrayList<>();
					//1.获取所有 beanName
					String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
							this.beanFactory, Object.class, true, false);
					//2.遍历所有的 beanName，找出对应的增强方法
					for (String beanName : beanNames) {
						//3.不合法的 beanName 直接跳过
						//子类 AnnotationAwareAspectJAutoProxyCreator 重写了逻辑
						if (!isEligibleBean(beanName)) {
							continue;
						}
						//获取对应 bean 的类型
						Class<?> beanType = this.beanFactory.getType(beanName);
						if (beanType == null) {
							continue;
						}
						//4.如果存在 Aspect 注解则进行处理
						if (this.advisorFactory.isAspect(beanType)) {
							//将存在 Aspect 注解的 beanName 添加到 aspectNames 列表
							aspectNames.add(beanName);
							AspectMetadata amd = new AspectMetadata(beanType, beanName);
							//如果 per-clause 的类型是 SINGLETON
							if (amd.getAjType().getPerClause().getKind() == PerClauseKind.SINGLETON) {
								MetadataAwareAspectInstanceFactory factory =
										new BeanFactoryAspectInstanceFactory(this.beanFactory, beanName);
								//5.解析标记 AspectJ 注解中的增强方法  解析的逻辑在这里 advisorFactory.getAdvisors
								List<Advisor> classAdvisors = this.advisorFactory.getAdvisors(factory);
								//6.放到缓存
								if (this.beanFactory.isSingleton(beanName)) {
									//如果是单例,直接将解析的增强方法放到缓存
									this.advisorsCache.put(beanName, classAdvisors);
								}
								else {
									//如果不是单例，则将 factory 放到缓存，之后可以通过 factory 来解析增强方法
									this.aspectFactoryCache.put(beanName, factory);
								}
								advisors.addAll(classAdvisors);
							}
							else {
								if (this.beanFactory.isSingleton(beanName)) {
									//如果 per-clause 的类型不是 SINGLETON，beanName 获取的 Bean 是单例，抛异常
									throw new IllegalArgumentException("Bean with name '" + beanName +
											"' is a singleton, but aspect instantiation model is not singleton");
								}
								MetadataAwareAspectInstanceFactory factory =
										new PrototypeAspectInstanceFactory(this.beanFactory, beanName);
								//将 factory 放到缓存，之后可以通过 factory 来解析增强方法
								this.aspectFactoryCache.put(beanName, factory);
								//解析标记 AspectJ 注解中的增强方法，并添加到 advisors 中
								//解析的逻辑在这里 advisorFactory.getAdvisors
								advisors.addAll(this.advisorFactory.getAdvisors(factory));
							}
						}
					}
					//7.将解析出来的切面 beanName 放到缓存，表示已经解析过了
					this.aspectBeanNames = aspectNames;

					return advisors;
				}
			}
		}
		//已经解析过了，无需再次解析。空列表也是解析过的，只不过解析完没找到合适的数据，只要不是 null 都是解析过的。
		if (aspectNames.isEmpty()) {
			return Collections.emptyList();
		}
		//已经解析过的情况，分单例、非单例两种情况
		List<Advisor> advisors = new ArrayList<>();
		for (String aspectName : aspectNames) {
			//根据 aspectName 从缓存中获取增强器
			List<Advisor> cachedAdvisors = this.advisorsCache.get(aspectName);
			if (cachedAdvisors != null) {
				//（1）对于单例的，已经放到缓存中，直接取
				advisors.addAll(cachedAdvisors);
			}
			else {
				//（2）对于非单例的，缓存中的是 factory，需要尽心解析
				MetadataAwareAspectInstanceFactory factory = this.aspectFactoryCache.get(aspectName);
				advisors.addAll(this.advisorFactory.getAdvisors(factory));
			}
		}
		//返回增强器
		return advisors;
	}

	/**
	 * Return whether the aspect bean with the given name is eligible.
	 * @param beanName the name of the aspect bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleBean(String beanName) {
		return true;
	}

}
