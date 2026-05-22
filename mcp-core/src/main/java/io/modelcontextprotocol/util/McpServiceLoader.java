/**
 * Copyright 2026 - 2026 the original author or authors.
 */
package io.modelcontextprotocol.util;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/**
 * Instance of this class are intended to be used differently in OSGi and non-OSGi
 * environments. In all non-OSGi environments the supplier member will be
 * <code>null</code> and the serviceLoad method will be called to use the
 * ServiceLoader.load to find the first instance of the supplier (assuming one is present
 * in the runtime), cache it, and call the supplier's get method.
 * <p>
 * In OSGi environments, the Service component runtime (scr) will call the setSupplier
 * method upon bundle activation (assuming one is present in the runtime), and subsequent
 * calls will use the given supplier instance rather than the ServiceLoader.load.
 *
 * @param <S> the type of the supplier
 * @param <R> the type of the supplier result/returned value
 */
public class McpServiceLoader<S extends Supplier<R>, R> {

	private Class<S> supplierType;

	private S supplier;

	private R supplierResult;

	public void setSupplier(S supplier) {
		this.supplier = supplier;
		this.supplierResult = null;
	}

	public void unsetSupplier(S supplier) {
		this.supplier = null;
		this.supplierResult = null;
	}

	public McpServiceLoader(Class<S> supplierType) {
		this.supplierType = supplierType;
	}

	protected Optional<S> serviceLoad(Class<S> type) {
		return findFirstByIterator(ServiceLoader.load(type));
	}

	@SuppressWarnings("unchecked")
	public synchronized R getDefault() {
		if (this.supplierResult == null) {
			if (this.supplier == null) {
				// Use serviceloader
				Optional<?> sl = serviceLoad(this.supplierType);
				if (!sl.isPresent()) {
					throw new ServiceConfigurationError(
							String.format("No %s available for creating McpJsonMapper", this.supplierType.getSimpleName()));
				}
				this.supplier = (S) sl.get();
			}
			this.supplierResult = this.supplier.get();
		}
		return supplierResult;
	}


	private <T> Optional<T> findFirstByIterator(ServiceLoader<T> loader) {
		java.util.Iterator<T> it = loader.iterator();
		if (it.hasNext()) {
			return Optional.of(it.next());
		}
		return Optional.empty();
	}
}
