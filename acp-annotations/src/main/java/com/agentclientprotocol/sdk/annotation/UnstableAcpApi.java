/*
 * Copyright 2025-2026 the original author or authors.
 */

package com.agentclientprotocol.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an ACP API element as unstable.
 *
 * <p>Unstable APIs correspond to protocol elements defined only in
 * {@code schema.unstable.json}. They are public and functional but may
 * change signature, behavior, wire mapping, or be removed in any minor
 * release to track the ACP protocol. See {@code VERSIONING.md} for the
 * full stability policy.
 *
 * <p>When the protocol element is promoted to {@code schema.json}, this
 * annotation will be removed. Removing it is a compatible change.
 *
 * <p>"Unstable" refers to protocol stability, not implementation quality.
 *
 * <p><b>Propagation:</b> if a package is marked, all its classes are
 * considered unstable (subpackages are not affected). If a type is marked,
 * all its members are unstable, but inheritors are not. If a method is
 * marked, overriding methods are not considered unstable.
 *
 * <p>This annotation is a stability marker and documentation signal, not
 * an access-control mechanism. IntelliJ users can configure the built-in
 * <em>Unstable API Usage</em> inspection to flag usages of APIs carrying
 * this annotation.
 *
 * @author Mark Pollack
 * @since 0.12.0
 * @see <a href="https://agentclientprotocol.com/protocol/overview">ACP Protocol</a>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD,
		ElementType.PARAMETER, ElementType.PACKAGE, ElementType.ANNOTATION_TYPE })
public @interface UnstableAcpApi {

	/**
	 * Optional reference to the tracking spec entry, RFD, or issue that gates
	 * stabilization. Empty by default.
	 * @return a URL or identifier linking to the protocol proposal
	 */
	String value() default "";

}
