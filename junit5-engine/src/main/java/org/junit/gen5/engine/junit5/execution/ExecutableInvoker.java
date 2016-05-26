/*
 * Copyright 2015-2016 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.junit.gen5.engine.junit5.execution;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.junit.gen5.commons.meta.API.Usage.Internal;
import static org.junit.gen5.commons.util.ReflectionUtils.isAssignableTo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.junit.gen5.api.extension.ExtensionContext;
import org.junit.gen5.api.extension.ParameterResolutionException;
import org.junit.gen5.api.extension.ParameterResolver;
import org.junit.gen5.commons.meta.API;
import org.junit.gen5.commons.util.ReflectionUtils;
import org.junit.gen5.engine.junit5.extension.ExtensionRegistry;

/**
 * {@code ExecutableInvoker} encapsulates the invocation of a
 * {@link java.lang.reflect.Executable} (i.e., method or constructor),
 * including support for dynamic resolution of method parameters via
 * {@link ParameterResolver ParameterResolvers}.
 *
 * @since 5.0
 */
@API(Internal)
public class ExecutableInvoker {

	private static final Logger LOG = Logger.getLogger(ExecutableInvoker.class.getName());

	private final ExtensionContext extensionContext;
	private final ExtensionRegistry extensionRegistry;

	public ExecutableInvoker(ExtensionContext extensionContext, ExtensionRegistry extensionRegistry) {
		this.extensionContext = extensionContext;
		this.extensionRegistry = extensionRegistry;
	}

	/**
	 * Invoke the supplied constructor with dynamic parameter resolution.
	 *
	 * @param constructor the constructor to invoke and resolve parameters for
	 */
	public <T> T invoke(Constructor<T> constructor) {
		return ReflectionUtils.newInstance(constructor, resolveParameters(constructor, Optional.empty()));
	}

	/**
	 * Invoke the supplied constructor with the supplied outer instance and
	 * dynamic parameter resolution.
	 *
	 * <p>This method should only be used to invoke the constructor for
	 * an inner class.
	 *
	 * @param constructor the constructor to invoke and resolve parameters for
	 * @param outerInstance the outer instance to supply as the first argument
	 * to the constructor
	 */
	public <T> T invoke(Constructor<T> constructor, Object outerInstance) {
		return ReflectionUtils.newInstance(constructor,
			resolveParameters(constructor, Optional.empty(), outerInstance));
	}

	/**
	 * Invoke the supplied {@code static} method with dynamic parameter resolution.
	 *
	 * @param method the method to invoke and resolve parameters for
	 */
	public Object invoke(Method method) {
		return ReflectionUtils.invokeMethod(method, null, resolveParameters(method, Optional.empty()));
	}

	/**
	 * Invoke the supplied method on the supplied target object with dynamic parameter
	 * resolution.
	 *
	 * @param method the method to invoke and resolve parameters for
	 * @param target the object on which the method will be invoked; should be
	 * {@code null} for static methods
	 */
	public Object invoke(Method method, Object target) {
		@SuppressWarnings("unchecked")
		Optional<Object> optionalTarget = (target instanceof Optional ? (Optional<Object>) target
				: Optional.ofNullable(target));
		return ReflectionUtils.invokeMethod(method, target, resolveParameters(method, optionalTarget));
	}

	/**
	 * Resolve the array of parameters for the supplied executable and target.
	 *
	 * @param executable the executable for which to resolve parameters
	 * @param target the object on which the executable will be invoked;
	 * should be {@code null} for static methods and constructors
	 * @return the array of Objects to be used as parameters in the executable
	 * invocation; never {@code null} though potentially empty
	 */
	private Object[] resolveParameters(Executable executable, Optional<Object> target) {
		return resolveParameters(executable, target, null);
	}

	/**
	 * Resolve the array of parameters for the supplied executable, target, and
	 * outer instance.
	 *
	 * @param executable the executable for which to resolve parameters
	 * @param target the object on which the executable will be invoked;
	 * should be {@code null} for static methods and constructors
	 * @param outerInstance the outer instance that will be supplied as the
	 * first argument to a constructor for an inner class; should be {@code null}
	 * for methods and constructors for top-level or static classes
	 * @return the array of Objects to be used as parameters in the executable
	 * invocation; never {@code null} though potentially empty
	 */
	private Object[] resolveParameters(Executable executable, Optional<Object> target, Object outerInstance) {
		Parameter[] parameters = executable.getParameters();
		Object[] values = new Object[parameters.length];
		int start = 0;

		// Ensure that the outer instance is resolved as the first parameter if
		// the executable is a constructor for an inner class.
		if (outerInstance != null) {
			values[0] = outerInstance;
			start = 1;
		}

		// Resolve remaining parameters dynamically
		for (int i = start; i < parameters.length; i++) {
			values[i] = resolveParameter(parameters[i], executable, target);
		}
		return values;
	}

	private Object resolveParameter(Parameter parameter, Executable executable, Optional<Object> target) {
		try {
			// @formatter:off
			List<ParameterResolver> matchingResolvers = this.extensionRegistry.stream(ParameterResolver.class)
					.filter(resolver -> resolver.supports(parameter, target, this.extensionContext))
					.collect(toList());
			// @formatter:on

			if (matchingResolvers.size() == 0) {
				throw new ParameterResolutionException(
					String.format("No ParameterResolver registered for parameter [%s] in executable [%s].", parameter,
						executable.toGenericString()));
			}

			if (matchingResolvers.size() > 1) {
				// @formatter:off
				String resolverNames = matchingResolvers.stream()
						.map(resolver -> resolver.getClass().getName())
						.collect(joining(", "));
				// @formatter:on
				throw new ParameterResolutionException(String.format(
					"Discovered multiple competing ParameterResolvers for parameter [%s] in executable [%s]: %s",
					parameter, executable.toGenericString(), resolverNames));
			}

			ParameterResolver resolver = matchingResolvers.get(0);
			Object value = resolver.resolve(parameter, target, this.extensionContext);
			validateResolvedType(parameter, value, executable, resolver);

			LOG.finer(() -> String.format(
				"ParameterResolver [%s] resolved a value of type [%s] for parameter [%s] in executable [%s].",
				resolver.getClass().getName(), (value != null ? value.getClass().getName() : null), parameter,
				executable.toGenericString()));

			return value;
		}
		catch (Throwable ex) {
			if (ex instanceof ParameterResolutionException) {
				throw (ParameterResolutionException) ex;
			}
			throw new ParameterResolutionException(String.format("Failed to resolve parameter [%s] in executable [%s]",
				parameter, executable.toGenericString()), ex);
		}
	}

	private void validateResolvedType(Parameter parameter, Object value, Executable executable,
			ParameterResolver resolver) {

		Class<?> type = parameter.getType();

		// Note: null is permissible as a resolved value but only for non-primitive types.
		if (!isAssignableTo(value, type)) {
			String message;
			if (value == null && type.isPrimitive()) {
				message = String.format(
					"ParameterResolver [%s] resolved a null value for parameter [%s] "
							+ "in executable [%s], but a primitive of type [%s] is required.",
					resolver.getClass().getName(), parameter, executable.toGenericString(), type.getName());
			}
			else {
				message = String.format(
					"ParameterResolver [%s] resolved a value of type [%s] for parameter [%s] "
							+ "in executable [%s], but a value assignment compatible with [%s] is required.",
					resolver.getClass().getName(), (value != null ? value.getClass().getName() : null), parameter,
					executable.toGenericString(), type.getName());
			}

			throw new ParameterResolutionException(message);
		}
	}

}
