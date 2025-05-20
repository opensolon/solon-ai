/*
 * Copyright 2017-2025 noear.org and authors
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
package org.noear.solon.ai.rag.util;

/**
 * 时间热度
 *
 * @author noear
 * @since 3.1
 */
public enum Freshness {
	/**
	 * 一天内
	 */
	ONE_DAY("oneDay"),
	/**
	 * 一周内
	 */
	ONE_WEEK("oneWeek"),
	/**
	 * 一月内
	 */
	ONE_MONTH("oneMonth"),
	/**
	 * 一年内
	 */
	ONE_YEAR("oneYear"),
	/**
	 * 不限
	 */
	NO_LIMIT("noLimit");

	public final String value;

	Freshness(String value) {
		this.value = value;
	}
}
