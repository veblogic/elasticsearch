/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index;

import org.apache.lucene.index.MergePolicy;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.AbstractScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.translog.Translog;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * This class encapsulates all index level settings and handles settings updates.
 * It's created per index and available to all index level classes and allows them to retrieve
 * the latest updated settings instance. Classes that need to listen to settings updates can register
 * a settings consumer at index creation via {@link IndexModule#addSettingsUpdateConsumer(Setting, Consumer)} that will
 * be called for each settings update.
 */
public final class IndexSettings {

    public static final String DEFAULT_FIELD = "index.query.default_field";
    public static final String QUERY_STRING_LENIENT = "index.query_string.lenient";
    public static final String QUERY_STRING_ANALYZE_WILDCARD = "indices.query.query_string.analyze_wildcard";
    public static final String QUERY_STRING_ALLOW_LEADING_WILDCARD = "indices.query.query_string.allowLeadingWildcard";
    public static final String ALLOW_UNMAPPED = "index.query.parse.allow_unmapped_fields";
    public static final String INDEX_TRANSLOG_SYNC_INTERVAL = "index.translog.sync_interval";
    public static final Setting<Translog.Durability> INDEX_TRANSLOG_DURABILITY_SETTING = new Setting<>("index.translog.durability", Translog.Durability.REQUEST.name(), (value) -> Translog.Durability.valueOf(value.toUpperCase(Locale.ROOT)), true, Setting.Scope.INDEX);
    public static final Setting<Boolean> INDEX_WARMER_ENABLED_SETTING = Setting.boolSetting("index.warmer.enabled", true, true, Setting.Scope.INDEX);

    public static final String INDEX_REFRESH_INTERVAL = "index.refresh_interval";
    public static final TimeValue DEFAULT_REFRESH_INTERVAL = new TimeValue(1, TimeUnit.SECONDS);
    public static final String INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE = "index.translog.flush_threshold_size";
    public static final TimeValue DEFAULT_GC_DELETES = TimeValue.timeValueSeconds(60);

    /**
     * Index setting to enable / disable deletes garbage collection.
     * This setting is realtime updateable
     */
    public static final String INDEX_GC_DELETES_SETTING = "index.gc_deletes";

    private final String uuid;
    private final Index index;
    private final Version version;
    private final ESLogger logger;
    private final String nodeName;
    private final Settings nodeSettings;
    private final int numberOfShards;
    private final boolean isShadowReplicaIndex;
    private final ParseFieldMatcher parseFieldMatcher;
    // volatile fields are updated via #updateIndexMetaData(IndexMetaData) under lock
    private volatile Settings settings;
    private volatile IndexMetaData indexMetaData;
    private final String defaultField;
    private final boolean queryStringLenient;
    private final boolean queryStringAnalyzeWildcard;
    private final boolean queryStringAllowLeadingWildcard;
    private final boolean defaultAllowUnmappedFields;
    private final Predicate<String> indexNameMatcher;
    private volatile Translog.Durability durability;
    private final TimeValue syncInterval;
    private volatile TimeValue refreshInterval;
    private volatile ByteSizeValue flushThresholdSize;
    private final MergeSchedulerConfig mergeSchedulerConfig;
    private final MergePolicyConfig mergePolicyConfig;
    private final ScopedSettings scopedSettings;
    private long gcDeletesInMillis = DEFAULT_GC_DELETES.millis();
    private volatile boolean warmerEnabled;

    public static Set<Setting<?>> BUILT_IN_CLUSTER_SETTINGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING,
        IndexStore.INDEX_STORE_THROTTLE_MAX_BYTES_PER_SEC_SETTING,
        MergeSchedulerConfig.AUTO_THROTTLE_SETTING,
        MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING,
        MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING,
        IndexSettings.INDEX_TRANSLOG_DURABILITY_SETTING,
        IndexSettings.INDEX_WARMER_ENABLED_SETTING
    )));


    /**
     * Returns the default search field for this index.
     */
    public String getDefaultField() {
        return defaultField;
    }

    /**
     * Returns <code>true</code> if query string parsing should be lenient. The default is <code>false</code>
     */
    public boolean isQueryStringLenient() {
        return queryStringLenient;
    }

    /**
     * Returns <code>true</code> if the query string should analyze wildcards. The default is <code>false</code>
     */
    public boolean isQueryStringAnalyzeWildcard() {
        return queryStringAnalyzeWildcard;
    }

    /**
     * Returns <code>true</code> if the query string parser should allow leading wildcards. The default is <code>true</code>
     */
    public boolean isQueryStringAllowLeadingWildcard() {
        return queryStringAllowLeadingWildcard;
    }

    /**
     * Returns <code>true</code> if queries should be lenient about unmapped fields. The default is <code>true</code>
     */
    public boolean isDefaultAllowUnmappedFields() {
        return defaultAllowUnmappedFields;
    }

    /**
     * Creates a new {@link IndexSettings} instance. The given node settings will be merged with the settings in the metadata
     * while index level settings will overwrite node settings.
     *
     * @param indexMetaData the index metadata this settings object is associated with
     * @param nodeSettings the nodes settings this index is allocated on.
     */
    public IndexSettings(final IndexMetaData indexMetaData, final Settings nodeSettings) {
        this(indexMetaData, nodeSettings, (index) -> Regex.simpleMatch(index, indexMetaData.getIndex()));
    }

    /**
     * Creates a new {@link IndexSettings} instance. The given node settings will be merged with the settings in the metadata
     * while index level settings will overwrite node settings.
     *
     * @param indexMetaData the index metadata this settings object is associated with
     * @param nodeSettings the nodes settings this index is allocated on.
     * @param indexNameMatcher a matcher that can resolve an expression to the index name or index alias
     */
    public IndexSettings(final IndexMetaData indexMetaData, final Settings nodeSettings,final Predicate<String> indexNameMatcher) {
        scopedSettings = new ScopedSettings(nodeSettings, indexMetaData.getSettings(), BUILT_IN_CLUSTER_SETTINGS);
        this.nodeSettings = nodeSettings;
        this.settings = Settings.builder().put(nodeSettings).put(indexMetaData.getSettings()).build();
        this.index = new Index(indexMetaData.getIndex());
        version = Version.indexCreated(settings);
        uuid = settings.get(IndexMetaData.SETTING_INDEX_UUID, IndexMetaData.INDEX_UUID_NA_VALUE);
        logger = Loggers.getLogger(getClass(), settings, index);
        nodeName = settings.get("name", "");
        this.indexMetaData = indexMetaData;
        numberOfShards = settings.getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, null);
        isShadowReplicaIndex = IndexMetaData.isIndexUsingShadowReplicas(settings);

        this.defaultField = settings.get(DEFAULT_FIELD, AllFieldMapper.NAME);
        this.queryStringLenient = settings.getAsBoolean(QUERY_STRING_LENIENT, false);
        this.queryStringAnalyzeWildcard = settings.getAsBoolean(QUERY_STRING_ANALYZE_WILDCARD, false);
        this.queryStringAllowLeadingWildcard = settings.getAsBoolean(QUERY_STRING_ALLOW_LEADING_WILDCARD, true);
        this.parseFieldMatcher = new ParseFieldMatcher(settings);
        this.defaultAllowUnmappedFields = settings.getAsBoolean(ALLOW_UNMAPPED, true);
        this.indexNameMatcher = indexNameMatcher;
        this.durability = scopedSettings.get(INDEX_TRANSLOG_DURABILITY_SETTING);
        scopedSettings.addSettingsUpdateConsumer(INDEX_TRANSLOG_DURABILITY_SETTING, this::setTranslogDurability);
        syncInterval = settings.getAsTime(INDEX_TRANSLOG_SYNC_INTERVAL, TimeValue.timeValueSeconds(5));
        refreshInterval =  settings.getAsTime(INDEX_REFRESH_INTERVAL, DEFAULT_REFRESH_INTERVAL);
        flushThresholdSize = settings.getAsBytesSize(INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE, new ByteSizeValue(512, ByteSizeUnit.MB));
        mergeSchedulerConfig = new MergeSchedulerConfig(this);
        gcDeletesInMillis = settings.getAsTime(IndexSettings.INDEX_GC_DELETES_SETTING, DEFAULT_GC_DELETES).getMillis();
        warmerEnabled = scopedSettings.get(INDEX_WARMER_ENABLED_SETTING);
        scopedSettings.addSettingsUpdateConsumer(INDEX_WARMER_ENABLED_SETTING, this::setEnableWarmer);
        this.mergePolicyConfig = new MergePolicyConfig(logger, settings);
        assert indexNameMatcher.test(indexMetaData.getIndex());
    }

    /**
     * Returns the settings for this index. These settings contain the node and index level settings where
     * settings that are specified on both index and node level are overwritten by the index settings.
     */
    public Settings getSettings() { return settings; }

    /**
     * Returns the index this settings object belongs to
     */
    public Index getIndex() {
        return index;
    }

    /**
     * Returns the indexes UUID
     */
    public String getUUID() {
        return uuid;
    }

    /**
     * Returns <code>true</code> if the index has a custom data path
     */
    public boolean hasCustomDataPath() {
        return customDataPath() != null;
    }

    /**
     * Returns the customDataPath for this index, if configured. <code>null</code> o.w.
     */
    public String customDataPath() {
        return settings.get(IndexMetaData.SETTING_DATA_PATH);
    }

    /**
     * Returns <code>true</code> iff the given settings indicate that the index
     * associated with these settings allocates it's shards on a shared
     * filesystem.
     */
    public boolean isOnSharedFilesystem() {
        return IndexMetaData.isOnSharedFilesystem(getSettings());
    }

    /**
     * Returns <code>true</code> iff the given settings indicate that the index associated
     * with these settings uses shadow replicas. Otherwise <code>false</code>. The default
     * setting for this is <code>false</code>.
     */
    public boolean isIndexUsingShadowReplicas() {
        return IndexMetaData.isOnSharedFilesystem(getSettings());
    }

    /**
     * Returns the version the index was created on.
     * @see Version#indexCreated(Settings)
     */
    public Version getIndexVersionCreated() {
        return version;
    }

    /**
     * Returns the current node name
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * Returns the current IndexMetaData for this index
     */
    public IndexMetaData getIndexMetaData() {
        return indexMetaData;
    }

    /**
     * Returns the number of shards this index has.
     */
    public int getNumberOfShards() { return numberOfShards; }

    /**
     * Returns the number of replicas this index has.
     */
    public int getNumberOfReplicas() { return settings.getAsInt(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, null); }

    /**
     * Returns <code>true</code> iff this index uses shadow replicas.
     * @see IndexMetaData#isIndexUsingShadowReplicas(Settings)
     */
    public boolean isShadowReplicaIndex() { return isShadowReplicaIndex; }

    /**
     * Returns the node settings. The settings retured from {@link #getSettings()} are a merged version of the
     * index settings and the node settings where node settings are overwritten by index settings.
     */
    public Settings getNodeSettings() {
        return nodeSettings;
    }

    /**
     * Returns a {@link ParseFieldMatcher} for this index.
     */
    public ParseFieldMatcher getParseFieldMatcher() { return parseFieldMatcher; }

    /**
     * Returns <code>true</code> if the given expression matches the index name or one of it's aliases
     */
    public boolean matchesIndexName(String expression) {
        return indexNameMatcher.test(expression);
    }

    /**
     * Updates the settings and index metadata and notifies all registered settings consumers with the new settings iff at least one setting has changed.
     *
     * @return <code>true</code> iff any setting has been updated otherwise <code>false</code>.
     */
    synchronized boolean updateIndexMetaData(IndexMetaData indexMetaData) {
        final Settings newSettings = indexMetaData.getSettings();
        if (Version.indexCreated(newSettings) != version) {
            throw new IllegalArgumentException("version mismatch on settings update expected: " + version + " but was: " + Version.indexCreated(newSettings));
        }
        final String newUUID = newSettings.get(IndexMetaData.SETTING_INDEX_UUID, IndexMetaData.INDEX_UUID_NA_VALUE);
        if (newUUID.equals(getUUID()) == false) {
            throw new IllegalArgumentException("uuid mismatch on settings update expected: " + uuid + " but was: " + newUUID);
        }
        this.indexMetaData = indexMetaData;
        final Settings existingSettings = this.settings;
        if (existingSettings.getByPrefix(IndexMetaData.INDEX_SETTING_PREFIX).getAsMap().equals(newSettings.getByPrefix(IndexMetaData.INDEX_SETTING_PREFIX).getAsMap())) {
            // nothing to update, same settings
            return false;
        }
        scopedSettings.applySettings(newSettings);

        // nocommit
        final Settings mergedSettings = this.settings = Settings.builder().put(nodeSettings).put(newSettings).build();
        try {
            updateSettings(mergedSettings);
        } catch (Exception e) {
            logger.warn("failed to refresh index settings for [{}]", e, mergedSettings);
        }
        return true;
    }

    /**
     * Returns the translog durability for this index.
     */
    public Translog.Durability getTranslogDurability() {
        return durability;
    }

    private void setTranslogDurability(Translog.Durability durability) {
        this.durability = durability;
    }

    /**
     * Returns true if index warmers are enabled, otherwise <code>false</code>
     */
    public boolean isWarmerEnabled() {
        return warmerEnabled;
    }

    private void setEnableWarmer(boolean enableWarmer) {
        this.warmerEnabled = enableWarmer;
    }


    private void updateSettings(Settings settings) {
        TimeValue refreshInterval = settings.getAsTime(IndexSettings.INDEX_REFRESH_INTERVAL, this.refreshInterval);
        if (!refreshInterval.equals(this.refreshInterval)) {
            logger.info("updating refresh_interval from [{}] to [{}]", this.refreshInterval, refreshInterval);
            this.refreshInterval = refreshInterval;
        }

        ByteSizeValue flushThresholdSize = settings.getAsBytesSize(IndexSettings.INDEX_TRANSLOG_FLUSH_THRESHOLD_SIZE, this.flushThresholdSize);
        if (!flushThresholdSize.equals(this.flushThresholdSize)) {
            logger.info("updating flush_threshold_size from [{}] to [{}]", this.flushThresholdSize, flushThresholdSize);
            this.flushThresholdSize = flushThresholdSize;
        }

        long gcDeletesInMillis = settings.getAsTime(IndexSettings.INDEX_GC_DELETES_SETTING, TimeValue.timeValueMillis(this.gcDeletesInMillis)).getMillis();
        if (gcDeletesInMillis != this.gcDeletesInMillis) {
            logger.info("updating {} from [{}] to [{}]", IndexSettings.INDEX_GC_DELETES_SETTING, TimeValue.timeValueMillis(this.gcDeletesInMillis), TimeValue.timeValueMillis(gcDeletesInMillis));
            this.gcDeletesInMillis = gcDeletesInMillis;
        }

        mergePolicyConfig.onRefreshSettings(settings);
    }

    /**
     * Returns the translog sync interval. This is the interval in which the transaction log is asynchronously fsynced unless
     * the transaction log is fsyncing on every operations
     */
    public TimeValue getTranslogSyncInterval() {
        return syncInterval;
    }

    /**
     * Returns this interval in which the shards of this index are asynchronously refreshed. <tt>-1</tt> means async refresh is disabled.
     */
    public TimeValue getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * Returns the transaction log threshold size when to forcefully flush the index and clear the transaction log.
     */
    public ByteSizeValue getFlushThresholdSize() { return flushThresholdSize; }

    /**
     * Returns the {@link MergeSchedulerConfig}
     */
    public MergeSchedulerConfig getMergeSchedulerConfig() { return mergeSchedulerConfig; }

    /**
     * Returns the GC deletes cycle in milliseconds.
     */
    public long getGcDeletesInMillis() {
        return gcDeletesInMillis;
    }

    /**
     * Returns the merge policy that should be used for this index.
     */
    public MergePolicy getMergePolicy() {
        return mergePolicyConfig.getMergePolicy();
    }

    boolean containsSetting(Setting<?> setting) {
        return scopedSettings.get(setting.getKey()) != null;
    }

    public <T> T getValue(Setting<T> setting) {
        return scopedSettings.get(setting);
    }

    private static final class ScopedSettings extends AbstractScopedSettings {

        ScopedSettings(Settings settings, Settings scopeSettings, Set<Setting<?>> settingsSet) {
            super(settings, scopeSettings, settingsSet, Setting.Scope.INDEX);
        }

        void addSettingInternal(Setting<?> settings) {
            addSetting(settings);
        }
    }

    void addSetting(Setting<?> setting) {
        scopedSettings.addSettingInternal(setting);
    }

    <T> void addSettingsUpdateConsumer(Setting<T> setting, Consumer<T> consumer) {
        scopedSettings.addSettingInternal(setting);
        scopedSettings.addSettingsUpdateConsumer(setting, consumer);
    }
}
