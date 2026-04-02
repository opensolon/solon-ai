/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.agent.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Encapsulates a handler method and its target bean.
 * Supports both eager instance and lazy factory-based construction.
 *
 * @author Mark Pollack
 * @since 1.0.0
 */
public final class AcpHandlerMethod {

	private final Supplier<Object> beanSupplier;

	private final Method method;

	private final String acpMethod;

	private final AcpMethodParameter[] parameters;

	private final AcpMethodParameter returnType;

	/**
	 * Create a handler method with an eager bean instance.
	 * @param bean the target bean instance
	 * @param method the method to invoke
	 * @param acpMethod the ACP method name (e.g., "initialize", "session/prompt")
	 */
	public AcpHandlerMethod(Object bean, Method method, String acpMethod) {
		this(() -> bean, method, acpMethod);
	}

	/**
	 * Create a handler method with a lazy bean supplier.
	 * @param beanSupplier supplier for the target bean
	 * @param method the method to invoke
	 * @param acpMethod the ACP method name
	 */
	public AcpHandlerMethod(Supplier<Object> beanSupplier, Method method, String acpMethod) {
		this.beanSupplier = beanSupplier;
		this.method = method;
		this.acpMethod = acpMethod;
		this.method.setAccessible(true);

		// Pre-compute parameter metadata
		int paramCount = method.getParameterCount();
		this.parameters = new AcpMethodParameter[paramCount];
		for (int i = 0; i < paramCount; i++) {
			this.parameters[i] = new AcpMethodParameter(method, i);
		}

		this.returnType = AcpMethodParameter.forReturnType(method);
	}

	/**
	 * Get the target bean instance.
	 * @return the bean
	 */
	public Object getBean() {
		return beanSupplier.get();
	}

	/**
	 * Get the method to invoke.
	 * @return the method
	 */
	public Method getMethod() {
		return method;
	}

	/**
	 * Get the ACP method name (e.g., "initialize", "session/prompt").
	 * @return the ACP method name
	 */
	public String getAcpMethod() {
		return acpMethod;
	}

	/**
	 * Get the pre-computed parameter metadata.
	 * @return array of parameter metadata
	 */
	public AcpMethodParameter[] getParameters() {
		return parameters;
	}

	/**
	 * Get the return type metadata.
	 * @return the return type parameter
	 */
	public AcpMethodParameter getReturnType() {
		return returnType;
	}

	/**
	 * Invoke the method with the given arguments.
	 * @param args the arguments
	 * @return the return value
	 * @throws Exception if invocation fails
	 */
	public Object invoke(Object[] args) throws Exception {
		try {
			return method.invoke(getBean(), args);
		}
		catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception)cause;
			}
			if (cause instanceof Error) {
				throw (Error)cause;
			}
			throw new RuntimeException(cause);
		}
	}

	@Override
	public String toString() {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName()
				+ " -> " + acpMethod;
	}

}
