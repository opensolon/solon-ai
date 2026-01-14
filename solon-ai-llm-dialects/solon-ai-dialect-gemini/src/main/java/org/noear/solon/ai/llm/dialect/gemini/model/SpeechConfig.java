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
package org.noear.solon.ai.llm.dialect.gemini.model;

/**
 * 语音配置
 * <p>
 * 用于配置 Gemini API 生成语音输出时的参数。
 * 包含语音选择、语言代码等配置项。
 * <p>
 * 示例配置：
 * <pre>{@code
 * SpeechConfig config = new SpeechConfig()
 *     .setVoiceConfig(new VoiceConfig()
 *         .setPrebuiltVoiceConfig(new PrebuiltVoiceConfig()
 *             .setVoiceName("en-US-Wavenet-A")))
 *     .setLanguageCode("en-US");
 * }</pre>
 *
 * @author cwdhf
 * @since 3.1
 */
public class SpeechConfig {

    /**
     * 语音配置
     * <p>
     * 单语音输出时的配置。
     * 与 multiSpeakerVoiceConfig 互斥。
     */
    private VoiceConfig voiceConfig;

    /**
     * 多说话者语音配置
     * <p>
     * 多说话者设置的配置。
     * 与 voiceConfig 字段互斥。
     */
    private MultiSpeakerVoiceConfig multiSpeakerVoiceConfig;

    /**
     * 语言代码
     * <p>
     * 语音合成的语言代码（BCP 47 格式，如 "en-US"）。
     * <p>
     * 有效值包括：de-DE, en-AU, en-GB, en-IN, en-US, es-US, fr-FR, hi-IN, pt-BR,
     * ar-XA, es-ES, fr-CA, id-ID, it-IT, ja-JP, tr-TR, vi-VN, bn-IN, gu-IN,
     * kn-IN, ml-IN, mr-IN, ta-IN, te-IN, nl-NL, ko-KR, cmn-CN, pl-PL, ru-RU, 和 th-TH。
     */
    private String languageCode;

    public VoiceConfig getVoiceConfig() {
        return voiceConfig;
    }

    public SpeechConfig setVoiceConfig(VoiceConfig voiceConfig) {
        this.voiceConfig = voiceConfig;
        return this;
    }

    public MultiSpeakerVoiceConfig getMultiSpeakerVoiceConfig() {
        return multiSpeakerVoiceConfig;
    }

    public SpeechConfig setMultiSpeakerVoiceConfig(MultiSpeakerVoiceConfig multiSpeakerVoiceConfig) {
        this.multiSpeakerVoiceConfig = multiSpeakerVoiceConfig;
        return this;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public SpeechConfig setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
        return this;
    }

    /**
     * 语音配置
     * <p>
     * 用于指定要使用的语音配置。
     */
    public static class VoiceConfig {

        /**
         * 预构建语音配置
         * <p>
         * 要使用的预构建语音的配置。
         */
        private PrebuiltVoiceConfig prebuiltVoiceConfig;

        public PrebuiltVoiceConfig getPrebuiltVoiceConfig() {
            return prebuiltVoiceConfig;
        }

        public VoiceConfig setPrebuiltVoiceConfig(PrebuiltVoiceConfig prebuiltVoiceConfig) {
            this.prebuiltVoiceConfig = prebuiltVoiceConfig;
            return this;
        }
    }

    /**
     * 预构建语音配置
     * <p>
     * 用于指定要使用的预构建说话者。
     */
    public static class PrebuiltVoiceConfig {

        /**
         * 语音名称
         * <p>
         * 要使用的预设语音的名称。
         */
        private String voiceName;

        public String getVoiceName() {
            return voiceName;
        }

        public PrebuiltVoiceConfig setVoiceName(String voiceName) {
            this.voiceName = voiceName;
            return this;
        }
    }

    /**
     * 多说话者语音配置
     * <p>
     * 用于多说话者设置的配置。
     */
    public static class MultiSpeakerVoiceConfig {

        /**
         * 说话者语音配置列表
         * <p>
         * 多说话者设置中每个说话者的配置。
         */
        private java.util.List<SpeakerVoiceConfig> speakerVoiceConfigs;

        public java.util.List<SpeakerVoiceConfig> getSpeakerVoiceConfigs() {
            return speakerVoiceConfigs;
        }

        public MultiSpeakerVoiceConfig setSpeakerVoiceConfigs(java.util.List<SpeakerVoiceConfig> speakerVoiceConfigs) {
            this.speakerVoiceConfigs = speakerVoiceConfigs;
            return this;
        }
    }

    /**
     * 说话者语音配置
     * <p>
     * 多说话者设置中单个说话者的配置。
     */
    public static class SpeakerVoiceConfig {

        /**
         * 说话者名称
         * <p>
         * 要使用的说话者名称。
         * 应与提示词中的名称相同。
         */
        private String speaker;

        /**
         * 语音配置
         * <p>
         * 要使用的语音配置。
         */
        private VoiceConfig voiceConfig;

        public String getSpeaker() {
            return speaker;
        }

        public SpeakerVoiceConfig setSpeaker(String speaker) {
            this.speaker = speaker;
            return this;
        }

        public VoiceConfig getVoiceConfig() {
            return voiceConfig;
        }

        public SpeakerVoiceConfig setVoiceConfig(VoiceConfig voiceConfig) {
            this.voiceConfig = voiceConfig;
            return this;
        }
    }
}
