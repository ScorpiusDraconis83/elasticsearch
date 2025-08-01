/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.cluster.metadata;

import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.RegExp;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexClusterStateUpdateRequest;
import org.elasticsearch.action.admin.indices.shrink.ResizeType;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.master.ShardsAcknowledgedResponse;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.EmptyClusterInfoService;
import org.elasticsearch.cluster.TestShardRoutingRoleStrategies;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodeUtils;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.GlobalRoutingTable;
import org.elasticsearch.cluster.routing.GlobalRoutingTableTestHelper;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.DataTier;
import org.elasticsearch.cluster.routing.allocation.ExistingShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.version.CompatibilityVersions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexSettingProvider;
import org.elasticsearch.index.IndexSettingProviders;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.query.SearchExecutionContextHelper;
import org.elasticsearch.index.shard.IndexLongFieldRange;
import org.elasticsearch.indices.EmptySystemIndices;
import org.elasticsearch.indices.IndexCreationException;
import org.elasticsearch.indices.InvalidAliasNameException;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.indices.ShardLimitValidator;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.indices.SystemIndexDescriptorUtils;
import org.elasticsearch.indices.SystemIndices;
import org.elasticsearch.snapshots.EmptySnapshotsInfoService;
import org.elasticsearch.test.ClusterServiceUtils;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.TransportVersionUtils;
import org.elasticsearch.test.gateway.TestGatewayAllocator;
import org.elasticsearch.test.index.IndexVersionUtils;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentFactory;
import org.hamcrest.Matchers;
import org.junit.Before;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_READ_ONLY_BLOCK;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_READ_ONLY;
import static org.elasticsearch.cluster.metadata.IndexMetadata.SETTING_VERSION_CREATED;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.buildIndexMetadata;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.clusterStateCreateIndex;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.getIndexNumberOfRoutingShards;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.parseV1Mappings;
import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.resolveAndValidateAliases;
import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.index.IndexSettings.INDEX_SOFT_DELETES_SETTING;
import static org.elasticsearch.indices.ShardLimitValidatorTests.createTestShardLimitService;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasValue;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

public class MetadataCreateIndexServiceTests extends ESTestCase {

    private ProjectId projectId;
    private CreateIndexClusterStateUpdateRequest request;
    private SearchExecutionContext searchExecutionContext;

    @Before
    public void setupCreateIndexRequestAndAliasValidator() {
        projectId = randomProjectIdOrDefault();
        request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test");
        Settings indexSettings = indexSettings(IndexVersion.current(), 1, 1).build();
        searchExecutionContext = SearchExecutionContextHelper.createSimple(
            new IndexSettings(IndexMetadata.builder("test").settings(indexSettings).build(), indexSettings),
            parserConfig(),
            writableRegistry()
        );
    }

    private ClusterState createClusterState(String name, int numShards, int numReplicas, Settings settings) {
        int numRoutingShards = settings.getAsInt(IndexMetadata.INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING.getKey(), numShards);
        Metadata.Builder metaBuilder = Metadata.builder();
        IndexMetadata indexMetadata = IndexMetadata.builder(name)
            .settings(settings(IndexVersion.current()).put(settings))
            .numberOfShards(numShards)
            .numberOfReplicas(numReplicas)
            .setRoutingNumShards(numRoutingShards)
            .build();
        metaBuilder.put(ProjectMetadata.builder(projectId).put(indexMetadata, false));
        Metadata metadata = metaBuilder.build();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder(TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY);
        routingTableBuilder.addAsNew(metadata.getProject(projectId).index(name));

        return ClusterState.builder(ClusterName.DEFAULT)
            .metadata(metadata)
            .routingTable(GlobalRoutingTableTestHelper.buildRoutingTable(metadata, RoutingTable.Builder::addAsNew))
            .blocks(ClusterBlocks.builder().addBlocks(projectId, indexMetadata))
            .build();
    }

    public static boolean isShrinkable(int source, int target) {
        int x = source / target;
        assert source > target : source + " <= " + target;
        return target * x == source;
    }

    public static boolean isSplitable(int source, int target) {
        int x = target / source;
        assert source < target : source + " >= " + target;
        return source * x == target;
    }

    public void testValidateShrinkIndex() {
        int numShards = randomIntBetween(2, 42);
        ClusterState state = createClusterState(
            "source",
            numShards,
            randomIntBetween(0, 10),
            Settings.builder().put("index.blocks.write", true).build()
        );

        assertEquals(
            "index [source] already exists",
            expectThrows(ResourceAlreadyExistsException.class, () -> validateShrinkIndex(state, "target", "source", Settings.EMPTY))
                .getMessage()
        );

        assertEquals(
            "no such index [no_such_index]",
            expectThrows(IndexNotFoundException.class, () -> validateShrinkIndex(state, "no_such_index", "target", Settings.EMPTY))
                .getMessage()
        );

        Settings targetSettings = Settings.builder().put("index.number_of_shards", 1).build();
        assertEquals(
            "can't shrink an index with only one shard",
            expectThrows(
                IllegalArgumentException.class,
                () -> validateShrinkIndex(
                    createClusterState("source", 1, 0, Settings.builder().put("index.blocks.write", true).build()),
                    "source",
                    "target",
                    targetSettings
                )
            ).getMessage()
        );

        assertEquals(
            "the number of target shards [10] must be less that the number of source shards [5]",
            expectThrows(
                IllegalArgumentException.class,
                () -> validateShrinkIndex(
                    createClusterState("source", 5, 0, Settings.builder().put("index.blocks.write", true).build()),
                    "source",
                    "target",
                    Settings.builder().put("index.number_of_shards", 10).build()
                )
            ).getMessage()
        );

        assertEquals(
            "index source must be read-only to resize index. use \"index.blocks.write=true\"",
            expectThrows(
                IllegalStateException.class,
                () -> validateShrinkIndex(
                    createClusterState("source", randomIntBetween(2, 100), randomIntBetween(0, 10), Settings.EMPTY),
                    "source",
                    "target",
                    targetSettings
                )
            ).getMessage()
        );

        assertEquals(
            "index source must have all shards allocated on the same node to shrink index",
            expectThrows(
                IllegalStateException.class,
                () -> validateShrinkIndex(state, "source", "target", targetSettings)

            ).getMessage()
        );
        assertEquals(
            "the number of source shards [8] must be a multiple of [3]",
            expectThrows(
                IllegalArgumentException.class,
                () -> validateShrinkIndex(
                    createClusterState("source", 8, randomIntBetween(0, 10), Settings.builder().put("index.blocks.write", true).build()),
                    "source",
                    "target",
                    Settings.builder().put("index.number_of_shards", 3).build()
                )
            ).getMessage()
        );

        // create one that won't fail
        ClusterState clusterState = ClusterState.builder(
            createClusterState("source", numShards, 0, Settings.builder().put("index.blocks.write", true).build())
        ).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();
        AllocationService service = new AllocationService(
            new AllocationDeciders(Collections.singleton(new MaxRetryAllocationDecider())),
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            EmptyClusterInfoService.INSTANCE,
            EmptySnapshotsInfoService.INSTANCE,
            TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
        );

        GlobalRoutingTable routingTable = service.reroute(clusterState, "reroute", ActionListener.noop()).globalRoutingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        // now we start the shard
        routingTable = ESAllocationTestCase.startShardsAndReroute(
            service,
            clusterState,
            clusterState.routingTable(projectId).index("source").shardsWithState(INITIALIZING)
        ).globalRoutingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        int targetShards;
        do {
            targetShards = randomIntBetween(1, numShards / 2);
        } while (isShrinkable(numShards, targetShards) == false);
        validateShrinkIndex(clusterState, "source", "target", Settings.builder().put("index.number_of_shards", targetShards).build());
    }

    public void testValidateSplitIndex() {
        int numShards = randomIntBetween(1, 42);
        Settings targetSettings = Settings.builder().put("index.number_of_shards", numShards * 2).build();
        ClusterState state = createClusterState(
            "source",
            numShards,
            randomIntBetween(0, 10),
            Settings.builder().put("index.blocks.write", true).build()
        );

        assertEquals(
            "index [source] already exists",
            expectThrows(ResourceAlreadyExistsException.class, () -> validateSplitIndex(state, "target", "source", targetSettings))
                .getMessage()
        );

        assertEquals(
            "no such index [no_such_index]",
            expectThrows(IndexNotFoundException.class, () -> validateSplitIndex(state, "no_such_index", "target", targetSettings))
                .getMessage()
        );

        assertEquals(
            "the number of source shards [10] must be less that the number of target shards [5]",
            expectThrows(
                IllegalArgumentException.class,
                () -> validateSplitIndex(
                    createClusterState("source", 10, 0, Settings.builder().put("index.blocks.write", true).build()),
                    "source",
                    "target",
                    Settings.builder().put("index.number_of_shards", 5).build()
                )
            ).getMessage()
        );

        assertEquals(
            "index source must be read-only to resize index. use \"index.blocks.write=true\"",
            expectThrows(
                IllegalStateException.class,
                () -> validateSplitIndex(
                    createClusterState("source", randomIntBetween(2, 100), randomIntBetween(0, 10), Settings.EMPTY),
                    "source",
                    "target",
                    targetSettings
                )
            ).getMessage()
        );

        assertEquals(
            "the number of source shards [3] must be a factor of [4]",
            expectThrows(
                IllegalArgumentException.class,
                () -> validateSplitIndex(
                    createClusterState("source", 3, randomIntBetween(0, 10), Settings.builder().put("index.blocks.write", true).build()),
                    "source",
                    "target",
                    Settings.builder().put("index.number_of_shards", 4).build()
                )
            ).getMessage()
        );

        int targetShards;
        do {
            targetShards = randomIntBetween(numShards + 1, 100);
        } while (isSplitable(numShards, targetShards) == false);
        ClusterState clusterState = ClusterState.builder(
            createClusterState(
                "source",
                numShards,
                0,
                Settings.builder().put("index.blocks.write", true).put("index.number_of_routing_shards", targetShards).build()
            )
        ).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();
        AllocationService service = new AllocationService(
            new AllocationDeciders(Collections.singleton(new MaxRetryAllocationDecider())),
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            EmptyClusterInfoService.INSTANCE,
            EmptySnapshotsInfoService.INSTANCE,
            TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
        );

        GlobalRoutingTable routingTable = service.reroute(clusterState, "reroute", ActionListener.noop()).globalRoutingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        // now we start the shard
        routingTable = ESAllocationTestCase.startShardsAndReroute(
            service,
            clusterState,
            clusterState.routingTable(projectId).index("source").shardsWithState(INITIALIZING)
        ).globalRoutingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        validateSplitIndex(clusterState, "source", "target", Settings.builder().put("index.number_of_shards", targetShards).build());
    }

    public void testPrepareResizeIndexSettings() {
        final IndexVersion version = IndexVersionUtils.randomWriteVersion();
        final Settings.Builder indexSettingsBuilder = Settings.builder()
            .put("index.version.created", version)
            .put("index.similarity.default.type", "BM25")
            .put("index.analysis.analyzer.default.tokenizer", "keyword")
            .put("index.soft_deletes.enabled", "true");
        if (randomBoolean()) {
            indexSettingsBuilder.put("index.allocation.max_retries", randomIntBetween(1, 1000));
        }
        runPrepareResizeIndexSettingsTest(
            indexSettingsBuilder.build(),
            Settings.EMPTY,
            Collections.emptyList(),
            randomBoolean(),
            settings -> {
                assertThat("similarity settings must be copied", settings.get("index.similarity.default.type"), equalTo("BM25"));
                assertThat(
                    "analysis settings must be copied",
                    settings.get("index.analysis.analyzer.default.tokenizer"),
                    equalTo("keyword")
                );
                assertThat(settings.get("index.routing.allocation.initial_recovery._id"), equalTo("node1"));
                assertThat(settings.get("index.allocation.max_retries"), nullValue());
                assertThat(settings.getAsVersionId("index.version.created", IndexVersion::fromId), equalTo(version));
                assertThat(settings.get("index.soft_deletes.enabled"), equalTo("true"));
            }
        );
    }

    public void testPrepareResizeIndexSettingsCopySettings() {
        final int maxMergeCount = randomIntBetween(1, 16);
        final int maxThreadCount = randomIntBetween(1, 16);
        final Setting<String> nonCopyableExistingIndexSetting = Setting.simpleString(
            "index.non_copyable.existing",
            Setting.Property.IndexScope,
            Setting.Property.NotCopyableOnResize
        );
        final Setting<String> nonCopyableRequestIndexSetting = Setting.simpleString(
            "index.non_copyable.request",
            Setting.Property.IndexScope,
            Setting.Property.NotCopyableOnResize
        );
        runPrepareResizeIndexSettingsTest(
            Settings.builder()
                .put("index.merge.scheduler.max_merge_count", maxMergeCount)
                .put("index.non_copyable.existing", "existing")
                .build(),
            Settings.builder()
                .put("index.blocks.write", (String) null)
                .put("index.merge.scheduler.max_thread_count", maxThreadCount)
                .put("index.non_copyable.request", "request")
                .build(),
            Arrays.asList(nonCopyableExistingIndexSetting, nonCopyableRequestIndexSetting),
            true,
            settings -> {
                assertNull(settings.getAsBoolean("index.blocks.write", null));
                assertThat(settings.get("index.routing.allocation.require._name"), equalTo("node1"));
                assertThat(settings.getAsInt("index.merge.scheduler.max_merge_count", null), equalTo(maxMergeCount));
                assertThat(settings.getAsInt("index.merge.scheduler.max_thread_count", null), equalTo(maxThreadCount));
                assertNull(settings.get("index.non_copyable.existing"));
                assertThat(settings.get("index.non_copyable.request"), equalTo("request"));
            }
        );
    }

    public void testPrepareResizeIndexSettingsAnalysisSettings() {
        // analysis settings from the request are not overwritten
        runPrepareResizeIndexSettingsTest(
            Settings.EMPTY,
            Settings.builder().put("index.analysis.analyzer.default.tokenizer", "whitespace").build(),
            Collections.emptyList(),
            randomBoolean(),
            settings -> assertThat(
                "analysis settings are not overwritten",
                settings.get("index.analysis.analyzer.default.tokenizer"),
                equalTo("whitespace")
            )
        );

    }

    public void testPrepareResizeIndexSettingsSimilaritySettings() {
        // similarity settings from the request are not overwritten
        runPrepareResizeIndexSettingsTest(
            Settings.EMPTY,
            Settings.builder().put("index.similarity.sim.type", "DFR").build(),
            Collections.emptyList(),
            randomBoolean(),
            settings -> assertThat("similarity settings are not overwritten", settings.get("index.similarity.sim.type"), equalTo("DFR"))
        );

    }

    public void testDoNotOverrideSoftDeletesSettingOnResize() {
        runPrepareResizeIndexSettingsTest(
            Settings.builder().put("index.soft_deletes.enabled", "false").build(),
            Settings.builder().put("index.soft_deletes.enabled", "true").build(),
            Collections.emptyList(),
            randomBoolean(),
            settings -> assertThat(settings.get("index.soft_deletes.enabled"), equalTo("true"))
        );
    }

    private void runPrepareResizeIndexSettingsTest(
        final Settings sourceSettings,
        final Settings requestSettings,
        final Collection<Setting<?>> additionalIndexScopedSettings,
        final boolean copySettings,
        final Consumer<Settings> consumer
    ) {
        final String indexName = randomAlphaOfLength(10);

        final Settings indexSettings = Settings.builder()
            .put("index.blocks.write", true)
            .put("index.routing.allocation.require._name", "node1")
            .put(sourceSettings)
            .build();

        final ClusterState initialClusterState = ClusterState.builder(
            createClusterState(indexName, randomIntBetween(2, 10), 0, indexSettings)
        ).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();

        final AllocationService service = new AllocationService(
            new AllocationDeciders(Collections.singleton(new MaxRetryAllocationDecider())),
            new TestGatewayAllocator(),
            new BalancedShardsAllocator(Settings.EMPTY),
            EmptyClusterInfoService.INSTANCE,
            EmptySnapshotsInfoService.INSTANCE,
            TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
        );

        final GlobalRoutingTable initialRoutingTable = service.reroute(initialClusterState, "reroute", ActionListener.noop())
            .globalRoutingTable();

        final ClusterState routingTableClusterState = ClusterState.builder(initialClusterState).routingTable(initialRoutingTable).build();

        // now we start the shard
        final GlobalRoutingTable routingTable = ESAllocationTestCase.startShardsAndReroute(
            service,
            routingTableClusterState,
            routingTableClusterState.routingTable(projectId).index(indexName).shardsWithState(INITIALIZING)
        ).globalRoutingTable();
        final ClusterState clusterState = ClusterState.builder(routingTableClusterState).routingTable(routingTable).build();

        final Settings.Builder indexSettingsBuilder = Settings.builder().put("index.number_of_shards", 1).put(requestSettings);
        final Set<Setting<?>> settingsSet = Stream.concat(
            IndexScopedSettings.BUILT_IN_INDEX_SETTINGS.stream(),
            additionalIndexScopedSettings.stream()
        ).collect(Collectors.toSet());
        MetadataCreateIndexService.prepareResizeIndexSettings(
            clusterState.metadata().getProject(projectId),
            clusterState.blocks(),
            clusterState.routingTable(projectId),
            indexSettingsBuilder,
            clusterState.metadata().getProject(projectId).index(indexName).getIndex(),
            "target",
            ResizeType.SHRINK,
            copySettings,
            new IndexScopedSettings(Settings.EMPTY, settingsSet)
        );
        consumer.accept(indexSettingsBuilder.build());
    }

    public void testCreateIndexInUnknownProject() {
        withTemporaryClusterService(((clusterService, threadPool) -> {
            MetadataCreateIndexService checkerService = new MetadataCreateIndexService(
                Settings.EMPTY,
                clusterService,
                null,
                null,
                createTestShardLimitService(randomIntBetween(1, 1000), clusterService),
                null,
                new IndexScopedSettings(Settings.EMPTY, IndexScopedSettings.BUILT_IN_INDEX_SETTINGS),
                threadPool,
                null,
                EmptySystemIndices.INSTANCE,
                false,
                new IndexSettingProviders(Set.of())
            );
            PlainActionFuture<ShardsAcknowledgedResponse> createIndexFuture = new PlainActionFuture<>();
            ProjectId unknownProjectId = randomUniqueProjectId();
            checkerService.createIndex(
                TimeValue.MAX_VALUE,
                TimeValue.MAX_VALUE,
                TimeValue.MAX_VALUE,
                new CreateIndexClusterStateUpdateRequest("test cause", unknownProjectId, "test_index", "test_index"),
                createIndexFuture
            );
            ExecutionException executionException = expectThrows(ExecutionException.class, createIndexFuture::get);
            assertThat(executionException.getCause(), instanceOf(IndexCreationException.class));
            assertThat(executionException.getCause().getMessage(), containsString("failed to create index [test_index]"));
            assertThat(executionException.getCause().getCause(), instanceOf(IllegalArgumentException.class));
            assertThat(executionException.getCause().getCause().getMessage(), containsString("no such project"));
        }));
    }

    private DiscoveryNode newNode(String nodeId) {
        return DiscoveryNodeUtils.builder(nodeId).roles(Set.of(DiscoveryNodeRole.MASTER_ROLE, DiscoveryNodeRole.DATA_ROLE)).build();
    }

    public void testValidateIndexName() throws Exception {
        withTemporaryClusterService(((clusterService, threadPool) -> {
            MetadataCreateIndexService checkerService = new MetadataCreateIndexService(
                Settings.EMPTY,
                clusterService,
                null,
                null,
                createTestShardLimitService(randomIntBetween(1, 1000), clusterService),
                null,
                null,
                threadPool,
                null,
                EmptySystemIndices.INSTANCE,
                false,
                new IndexSettingProviders(Set.of())
            );
            validateIndexName(checkerService, "index?name", "must not contain the following characters " + Strings.INVALID_FILENAME_CHARS);

            validateIndexName(checkerService, "index#name", "must not contain '#'");

            validateIndexName(checkerService, "_indexname", "must not start with '_', '-', or '+'");
            validateIndexName(checkerService, "-indexname", "must not start with '_', '-', or '+'");
            validateIndexName(checkerService, "+indexname", "must not start with '_', '-', or '+'");

            validateIndexName(checkerService, "INDEXNAME", "must be lowercase");

            validateIndexName(checkerService, "..", "must not be '.' or '..'");

            validateIndexName(checkerService, "foo:bar", "must not contain ':'");

            validateIndexName(checkerService, "", "must not be empty");
            validateIndexName(checkerService, null, "must not be empty");
        }));
    }

    private static void validateIndexName(MetadataCreateIndexService metadataCreateIndexService, String indexName, String errorMessage) {
        ClusterState state = ClusterState.builder(ClusterName.DEFAULT).build();
        InvalidIndexNameException e = expectThrows(
            InvalidIndexNameException.class,
            () -> MetadataCreateIndexService.validateIndexName(indexName, state.metadata().getProject(), state.routingTable())
        );
        assertThat(e.getMessage(), endsWith(errorMessage));
    }

    public void testCalculateNumRoutingShards() {
        assertEquals(1024, MetadataCreateIndexService.calculateNumRoutingShards(1, IndexVersion.current()));
        assertEquals(1024, MetadataCreateIndexService.calculateNumRoutingShards(2, IndexVersion.current()));
        assertEquals(768, MetadataCreateIndexService.calculateNumRoutingShards(3, IndexVersion.current()));
        assertEquals(576, MetadataCreateIndexService.calculateNumRoutingShards(9, IndexVersion.current()));
        assertEquals(1024, MetadataCreateIndexService.calculateNumRoutingShards(512, IndexVersion.current()));
        assertEquals(2048, MetadataCreateIndexService.calculateNumRoutingShards(1024, IndexVersion.current()));
        assertEquals(4096, MetadataCreateIndexService.calculateNumRoutingShards(2048, IndexVersion.current()));

        for (int i = 0; i < 1000; i++) {
            int randomNumShards = randomIntBetween(1, 10000);
            int numRoutingShards = MetadataCreateIndexService.calculateNumRoutingShards(randomNumShards, IndexVersion.current());
            if (numRoutingShards <= 1024) {
                assertTrue("numShards: " + randomNumShards, randomNumShards < 513);
                assertTrue("numRoutingShards: " + numRoutingShards, numRoutingShards > 512);
            } else {
                assertEquals("numShards: " + randomNumShards, numRoutingShards / 2, randomNumShards);
            }

            double ratio = numRoutingShards / randomNumShards;
            int intRatio = (int) ratio;
            assertEquals(ratio, intRatio, 0.0d);
            assertTrue(1 < ratio);
            assertTrue(ratio <= 1024);
            assertEquals(0, intRatio % 2);
            assertEquals("ratio is not a power of two", intRatio, Integer.highestOneBit(intRatio));
        }
    }

    public void testValidateDotIndex() {
        List<SystemIndexDescriptor> systemIndexDescriptors = new ArrayList<>();
        systemIndexDescriptors.add(SystemIndexDescriptorUtils.createUnmanaged(".test-one*", "test"));
        Automaton patternAutomaton = new RegExp("\\.test-~(one.*)", RegExp.ALL | RegExp.DEPRECATED_COMPLEMENT).toAutomaton();

        systemIndexDescriptors.add(SystemIndexDescriptorUtils.createUnmanaged(".test-~(one*)", "test"));
        systemIndexDescriptors.add(SystemIndexDescriptorUtils.createUnmanaged(".pattern-test*", "test-1"));

        withTemporaryClusterService(((clusterService, threadPool) -> {
            MetadataCreateIndexService checkerService = new MetadataCreateIndexService(
                Settings.EMPTY,
                clusterService,
                null,
                null,
                createTestShardLimitService(randomIntBetween(1, 1000), clusterService),
                null,
                null,
                threadPool,
                null,
                new SystemIndices(Collections.singletonList(new SystemIndices.Feature("foo", "test feature", systemIndexDescriptors))),
                false,
                new IndexSettingProviders(Set.of())
            );
            // Check deprecations
            assertFalse(checkerService.validateDotIndex(".test2", false));
            assertWarnings(
                "index name [.test2] starts with a dot '.', in the next major version, index "
                    + "names starting with a dot are reserved for hidden indices and system indices"
            );

            // Check non-system hidden indices don't trigger a warning
            assertFalse(checkerService.validateDotIndex(".test2", true));

            // Check NO deprecation warnings if we give the index name
            assertTrue(checkerService.validateDotIndex(".test-one", false));
            assertTrue(checkerService.validateDotIndex(".test-3", false));

            // Check that patterns with wildcards work
            assertTrue(checkerService.validateDotIndex(".pattern-test", false));
            assertTrue(checkerService.validateDotIndex(".pattern-test-with-suffix", false));
            assertTrue(checkerService.validateDotIndex(".pattern-test-other-suffix", false));
        }));
    }

    @SuppressWarnings("unchecked")
    public void testParseMappingsAppliesDataFromTemplateAndRequest() throws Exception {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(templateBuilder -> {
            templateBuilder.putAlias(AliasMetadata.builder("alias1"));
            templateBuilder.putMapping("_doc", createMapping("mapping_from_template", "text"));
        });
        request.mappings(createMapping("mapping_from_request", "text").string());

        Map<String, Object> parsedMappings = MetadataCreateIndexService.parseV1Mappings(
            request.mappings(),
            List.of(templateMetadata.getMappings()),
            NamedXContentRegistry.EMPTY
        );

        assertThat(parsedMappings, hasKey("_doc"));
        Map<String, Object> doc = (Map<String, Object>) parsedMappings.get("_doc");
        assertThat(doc, hasKey("properties"));
        Map<String, Object> mappingsProperties = (Map<String, Object>) doc.get("properties");
        assertThat(mappingsProperties, hasKey("mapping_from_request"));
        assertThat(mappingsProperties, hasKey("mapping_from_template"));
    }

    public void testAggregateSettingsAppliesSettingsFromTemplatesAndRequest() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().put("template_setting", "value1"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        request.settings(Settings.builder().put("request_setting", "value2").build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), equalTo("value1"));
        assertThat(aggregatedIndexSettings.get("request_setting"), equalTo("value2"));
    }

    public void testAggregateSettingsProviderOverrulesSettingsFromRequest() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().put("template_setting", "value1"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        request.settings(Settings.builder().put("request_setting", "value2").build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new IndexSettingProvider() {
                @Override
                public Settings getAdditionalIndexSettings(
                    String indexName,
                    String dataStreamName,
                    IndexMode templateIndexMode,
                    ProjectMetadata projectMetadata,
                    Instant resolvedAt,
                    Settings indexTemplateAndCreateRequestSettings,
                    List<CompressedXContent> combinedTemplateMappings
                ) {
                    return Settings.builder().put("request_setting", "overrule_value").put("other_setting", "other_value").build();
                }

                @Override
                public boolean overrulesTemplateAndRequestSettings() {
                    return true;
                }
            })
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), equalTo("value1"));
        assertThat(aggregatedIndexSettings.get("request_setting"), equalTo("overrule_value"));
        assertThat(aggregatedIndexSettings.get("other_setting"), equalTo("other_value"));
    }

    /**
     * When a failure store index is created, we must filter out any unsupported settings from the create request or from the template that
     * may have been provided by users in the create request or from the original data stream template. An exception to this is any settings
     * that have been provided by index setting providers which should be considered default values on indices.
     */
    public void testAggregateSettingsProviderIsNotFilteredOnFailureStore() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().put("template_setting", "value1"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        var request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test").settings(
            Settings.builder().put("request_setting", "value2").build()
        ).isFailureIndex(true);

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new IndexSettingProvider() {
                @Override
                public Settings getAdditionalIndexSettings(
                    String indexName,
                    String dataStreamName,
                    IndexMode templateIndexMode,
                    ProjectMetadata projectMetadata,
                    Instant resolvedAt,
                    Settings indexTemplateAndCreateRequestSettings,
                    List<CompressedXContent> combinedTemplateMappings
                ) {
                    return Settings.builder().put(ExistingShardsAllocator.EXISTING_SHARDS_ALLOCATOR_SETTING.getKey(), "override").build();
                }

                @Override
                public boolean overrulesTemplateAndRequestSettings() {
                    return true;
                }
            })
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), nullValue());
        assertThat(aggregatedIndexSettings.get(ExistingShardsAllocator.EXISTING_SHARDS_ALLOCATOR_SETTING.getKey()), equalTo("override"));
    }

    public void testAggregateSettingsProviderOverrulesNullFromRequest() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().put("template_setting", "value1"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        request.settings(Settings.builder().putNull("request_setting").build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new IndexSettingProvider() {
                @Override
                public Settings getAdditionalIndexSettings(
                    String indexName,
                    String dataStreamName,
                    IndexMode templateIndexMode,
                    ProjectMetadata projectMetadata,
                    Instant resolvedAt,
                    Settings indexTemplateAndCreateRequestSettings,
                    List<CompressedXContent> combinedTemplateMappings
                ) {
                    return Settings.builder().put("request_setting", "overrule_value").put("other_setting", "other_value").build();
                }

                @Override
                public boolean overrulesTemplateAndRequestSettings() {
                    return true;
                }
            })
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), equalTo("value1"));
        assertThat(aggregatedIndexSettings.get("request_setting"), equalTo("overrule_value"));
        assertThat(aggregatedIndexSettings.get("other_setting"), equalTo("other_value"));
    }

    public void testAggregateSettingsProviderOverrulesSettingsFromTemplates() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().put("template_setting", "value1"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        request.settings(Settings.builder().put("request_setting", "value2").build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new IndexSettingProvider() {
                @Override
                public Settings getAdditionalIndexSettings(
                    String indexName,
                    String dataStreamName,
                    IndexMode templateIndexMode,
                    ProjectMetadata projectMetadata,
                    Instant resolvedAt,
                    Settings indexTemplateAndCreateRequestSettings,
                    List<CompressedXContent> combinedTemplateMappings
                ) {
                    return Settings.builder().put("template_setting", "overrule_value").put("other_setting", "other_value").build();
                }

                @Override
                public boolean overrulesTemplateAndRequestSettings() {
                    return true;
                }
            })
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), equalTo("overrule_value"));
        assertThat(aggregatedIndexSettings.get("request_setting"), equalTo("value2"));
        assertThat(aggregatedIndexSettings.get("other_setting"), equalTo("other_value"));
    }

    public void testAggregateSettingsProviderOverrulesNullFromTemplates() {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            builder.settings(Settings.builder().putNull("template_setting"));
        });
        ProjectMetadata projectMetadata = ProjectMetadata.builder(projectId).templates(Map.of("template_1", templateMetadata)).build();
        ClusterState clusterState = ClusterState.builder(ClusterName.DEFAULT).putProjectMetadata(projectMetadata).build();
        request.settings(Settings.builder().put("request_setting", "value2").build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new IndexSettingProvider() {
                @Override
                public Settings getAdditionalIndexSettings(
                    String indexName,
                    String dataStreamName,
                    IndexMode templateIndexMode,
                    ProjectMetadata projectMetadata,
                    Instant resolvedAt,
                    Settings indexTemplateAndCreateRequestSettings,
                    List<CompressedXContent> combinedTemplateMappings
                ) {
                    return Settings.builder().put("template_setting", "overrule_value").put("other_setting", "other_value").build();
                }

                @Override
                public boolean overrulesTemplateAndRequestSettings() {
                    return true;
                }
            })
        );

        assertThat(aggregatedIndexSettings.get("template_setting"), equalTo("overrule_value"));
        assertThat(aggregatedIndexSettings.get("request_setting"), equalTo("value2"));
        assertThat(aggregatedIndexSettings.get("other_setting"), equalTo("other_value"));
    }

    public void testInvalidAliasName() {
        final String[] invalidAliasNames = new String[] { "-alias1", "+alias2", "_alias3", "a#lias", "al:ias", ".", ".." };
        String aliasName = randomFrom(invalidAliasNames);
        request.aliases(Set.of(new Alias(aliasName)));

        expectThrows(
            InvalidAliasNameException.class,
            () -> resolveAndValidateAliases(
                request.index(),
                request.aliases(),
                List.of(),
                ProjectMetadata.builder(randomUniqueProjectId()).build(),
                xContentRegistry(),
                searchExecutionContext,
                IndexNameExpressionResolver::resolveDateMathExpression,
                m -> false
            )
        );
    }

    public void testAliasNameWithMathExpression() {
        final String aliasName = "<date-math-based-{2021-01-19||/M{yyyy-MM-dd}}>";

        request.aliases(Set.of(new Alias(aliasName)));

        List<AliasMetadata> aliasMetadata = resolveAndValidateAliases(
            request.index(),
            request.aliases(),
            List.of(),
            ProjectMetadata.builder(randomUniqueProjectId()).build(),
            xContentRegistry(),
            searchExecutionContext,
            IndexNameExpressionResolver::resolveDateMathExpression,
            m -> false
        );

        assertEquals("date-math-based-2021-01-01", aliasMetadata.get(0).alias());
    }

    @SuppressWarnings("unchecked")
    public void testRequestDataHavePriorityOverTemplateData() throws Exception {
        CompressedXContent templateMapping = createMapping("test", "text");
        CompressedXContent reqMapping = createMapping("test", "keyword");

        IndexTemplateMetadata templateMetadata = addMatchingTemplate(
            builder -> builder.putAlias(AliasMetadata.builder("alias").searchRouting("fromTemplate").build())
                .putMapping("_doc", templateMapping)
                .settings(Settings.builder().put("key1", "templateValue"))
        );

        request.mappings(reqMapping.string());
        request.aliases(Set.of(new Alias("alias").searchRouting("fromRequest")));
        request.settings(Settings.builder().put("key1", "requestValue").build());

        Map<String, Object> parsedMappings = MetadataCreateIndexService.parseV1Mappings(
            request.mappings(),
            List.of(templateMetadata.mappings()),
            xContentRegistry()
        );
        List<AliasMetadata> resolvedAliases = resolveAndValidateAliases(
            request.index(),
            request.aliases(),
            MetadataIndexTemplateService.resolveAliases(List.of(templateMetadata)),
            ProjectMetadata.builder(randomUniqueProjectId()).build(),
            xContentRegistry(),
            searchExecutionContext,
            IndexNameExpressionResolver::resolveDateMathExpression,
            m -> false
        );

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            templateMetadata.settings(),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );

        assertThat(resolvedAliases.get(0).getSearchRouting(), equalTo("fromRequest"));
        assertThat(aggregatedIndexSettings.get("key1"), equalTo("requestValue"));
        assertThat(parsedMappings, hasKey("_doc"));
        Map<String, Object> doc = (Map<String, Object>) parsedMappings.get("_doc");
        assertThat(doc, hasKey("properties"));
        Map<String, Object> mappingsProperties = (Map<String, Object>) doc.get("properties");
        assertThat(mappingsProperties, hasKey("test"));
        assertThat((Map<String, Object>) mappingsProperties.get("test"), hasValue("keyword"));
    }

    public void testDefaultSettings() {
        Settings aggregatedIndexSettings = aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            Settings.EMPTY,
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );

        assertThat(aggregatedIndexSettings.get(SETTING_NUMBER_OF_SHARDS), equalTo("1"));
    }

    public void testSettingsFromClusterState() {
        Settings aggregatedIndexSettings = aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            Settings.EMPTY,
            null,
            null,
            Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 15).build(),
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );

        assertThat(aggregatedIndexSettings.get(SETTING_NUMBER_OF_SHARDS), equalTo("15"));
    }

    public void testTemplateOrder() throws Exception {
        List<IndexTemplateMetadata> templates = new ArrayList<>(3);
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(3)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 12))
                    .putAlias(AliasMetadata.builder("alias1").writeIndex(true).searchRouting("3").build())
            )
        );
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(2)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 11))
                    .putAlias(AliasMetadata.builder("alias1").searchRouting("2").build())
            )
        );
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(1)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 10))
                    .putAlias(AliasMetadata.builder("alias1").searchRouting("1").build())
            )
        );

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            MetadataIndexTemplateService.resolveSettings(templates),
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );
        List<AliasMetadata> resolvedAliases = resolveAndValidateAliases(
            request.index(),
            request.aliases(),
            MetadataIndexTemplateService.resolveAliases(templates),
            ProjectMetadata.builder(randomUniqueProjectId()).build(),
            xContentRegistry(),
            searchExecutionContext,
            IndexNameExpressionResolver::resolveDateMathExpression,
            m -> false
        );

        assertThat(aggregatedIndexSettings.get(SETTING_NUMBER_OF_SHARDS), equalTo("12"));
        AliasMetadata alias = resolvedAliases.get(0);
        assertThat(alias.getSearchRouting(), equalTo("3"));
        assertThat(alias.writeIndex(), is(true));
    }

    public void testResolvedAliasInTemplate() {
        List<IndexTemplateMetadata> templates = new ArrayList<>(3);
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(3)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
                    .putAlias(AliasMetadata.builder("<jan-{2021-01-07||/M{yyyy-MM-dd}}>").build())
            )
        );
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(2)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
                    .putAlias(AliasMetadata.builder("<feb-{2021-02-28||/M{yyyy-MM-dd}}>").build())
            )
        );
        templates.add(
            addMatchingTemplate(
                builder -> builder.order(1)
                    .settings(Settings.builder().put(SETTING_NUMBER_OF_SHARDS, 1))
                    .putAlias(AliasMetadata.builder("<mar-{2021-03-07||/M{yyyy-MM-dd}}>").build())
            )
        );

        List<AliasMetadata> resolvedAliases = resolveAndValidateAliases(
            request.index(),
            request.aliases(),
            MetadataIndexTemplateService.resolveAliases(templates),
            ProjectMetadata.builder(randomUniqueProjectId()).build(),
            xContentRegistry(),
            searchExecutionContext,
            IndexNameExpressionResolver::resolveDateMathExpression,
            m -> false
        );

        assertThat(resolvedAliases.get(0).alias(), equalTo("jan-2021-01-01"));
        assertThat(resolvedAliases.get(1).alias(), equalTo("feb-2021-02-01"));
        assertThat(resolvedAliases.get(2).alias(), equalTo("mar-2021-03-01"));
    }

    public void testAggregateIndexSettingsIgnoresTemplatesOnCreateFromSourceIndex() throws Exception {
        CompressedXContent templateMapping = createMapping("test", "text");

        IndexTemplateMetadata templateMetadata = addMatchingTemplate(
            builder -> builder.putAlias(AliasMetadata.builder("alias").searchRouting("fromTemplate").build())
                .putMapping("_doc", templateMapping)
                .settings(Settings.builder().put("templateSetting", "templateValue"))
        );

        request.settings(Settings.builder().put("requestSetting", "requestValue").build());
        request.resizeType(ResizeType.SPLIT);
        request.recoverFrom(new Index("sourceIndex", UUID.randomUUID().toString()));
        ClusterState clusterState = createClusterState("sourceIndex", 1, 0, Settings.builder().put("index.blocks.write", true).build());

        Settings aggregatedIndexSettings = aggregateIndexSettings(
            clusterState,
            request,
            templateMetadata.settings(),
            List.of(templateMetadata.getMappings()),
            clusterState.metadata().getProject(projectId).index("sourceIndex"),
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );

        assertThat(aggregatedIndexSettings.get("templateSetting"), is(nullValue()));
        assertThat(aggregatedIndexSettings.get("requestSetting"), is("requestValue"));
    }

    public void testClusterStateCreateIndexThrowsWriteIndexValidationException() throws Exception {
        IndexMetadata existingWriteIndex = IndexMetadata.builder("test2")
            .settings(settings(IndexVersion.current()))
            .putAlias(AliasMetadata.builder("alias1").writeIndex(true).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .build();
        ProjectId projectId = randomUniqueProjectId();
        ClusterState currentClusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .putProjectMetadata(ProjectMetadata.builder(projectId).put(existingWriteIndex, false))
            .build();

        IndexMetadata newIndex = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("alias1").writeIndex(true).build())
            .build();

        assertThat(
            expectThrows(
                IllegalStateException.class,
                () -> clusterStateCreateIndex(
                    currentClusterState,
                    projectId,
                    newIndex,
                    null,
                    null,
                    TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
                )
            ).getMessage(),
            startsWith("alias [alias1] has more than one write index [")
        );
    }

    public void testClusterStateCreateIndex() {
        ProjectId projectId = randomUniqueProjectId();
        ClusterState currentClusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .putProjectMetadata(ProjectMetadata.builder(projectId))
            .build();

        IndexMetadata newIndexMetadata = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()).put(SETTING_READ_ONLY, true))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("alias1").writeIndex(true).build())
            .build();
        ClusterState updatedClusterState = clusterStateCreateIndex(
            currentClusterState,
            projectId,
            newIndexMetadata,
            null,
            null,
            TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
        );
        assertThat(
            updatedClusterState.blocks().getIndexBlockWithId(projectId, "test", INDEX_READ_ONLY_BLOCK.id()),
            is(INDEX_READ_ONLY_BLOCK)
        );
        assertThat(updatedClusterState.routingTable(projectId).index("test"), is(notNullValue()));

        assertThat(updatedClusterState.routingTable(projectId).allShards("test"), iterableWithSize(1));
        Metadata metadata = updatedClusterState.metadata();
        IndexAbstraction alias = metadata.getProject(projectId).getIndicesLookup().get("alias1");
        assertNotNull(alias);
        assertThat(alias.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        Index index = metadata.getProject(projectId).index("test").getIndex();
        assertThat(alias.getIndices(), contains(index));
        assertThat(metadata.getProject(projectId).aliasedIndices("alias1"), contains(index));
    }

    public void testClusterStateCreateIndexWithMetadataTransaction() {
        ProjectId projectId = randomUniqueProjectId();
        ClusterState currentClusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
            .putProjectMetadata(
                ProjectMetadata.builder(projectId)
                    .put(
                        IndexMetadata.builder("my-index")
                            .settings(settings(IndexVersion.current()).put(SETTING_READ_ONLY, true))
                            .numberOfShards(1)
                            .numberOfReplicas(0)
                    )

            )
            .build();

        IndexMetadata newIndexMetadata = IndexMetadata.builder("test")
            .settings(settings(IndexVersion.current()).put(SETTING_READ_ONLY, true))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("alias1").writeIndex(true).build())
            .build();

        // adds alias from new index to existing index
        BiConsumer<ProjectMetadata.Builder, IndexMetadata> metadataTransformer = (builder, indexMetadata) -> {
            AliasMetadata newAlias = indexMetadata.getAliases().values().iterator().next();
            IndexMetadata myIndex = builder.get("my-index");
            builder.put(IndexMetadata.builder(myIndex).putAlias(AliasMetadata.builder(newAlias.getAlias()).build()));
        };

        ClusterState updatedClusterState = clusterStateCreateIndex(
            currentClusterState,
            projectId,
            newIndexMetadata,
            metadataTransformer,
            null,
            TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
        );
        assertTrue(
            updatedClusterState.metadata().getProject(projectId).findAllAliases(new String[] { "my-index" }).containsKey("my-index")
        );
        assertNotNull(updatedClusterState.metadata().getProject(projectId).findAllAliases(new String[] { "my-index" }).get("my-index"));
        assertNotNull(
            updatedClusterState.metadata().getProject(projectId).findAllAliases(new String[] { "my-index" }).get("my-index").get(0).alias(),
            equalTo("alias1")
        );
    }

    public void testParseMappingsWithTypedTemplateAndTypelessIndexMapping() throws Exception {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            try {
                builder.putMapping("type", "{\"type\": {}}");
            } catch (IOException e) {
                ExceptionsHelper.reThrowIfNotNull(e);
            }
        });

        Map<String, Object> mappings = parseV1Mappings("{\"_doc\":{}}", List.of(templateMetadata.mappings()), xContentRegistry());
        assertThat(mappings, Matchers.hasKey(MapperService.SINGLE_MAPPING_NAME));
    }

    public void testParseMappingsWithTypedTemplate() throws Exception {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            try {
                builder.putMapping("type", """
                    {"type":{"properties":{"field":{"type":"keyword"}}}}
                    """);
            } catch (IOException e) {
                ExceptionsHelper.reThrowIfNotNull(e);
            }
        });
        Map<String, Object> mappings = parseV1Mappings("", List.of(templateMetadata.mappings()), xContentRegistry());
        assertThat(mappings, Matchers.hasKey(MapperService.SINGLE_MAPPING_NAME));
    }

    public void testParseMappingsWithTypelessTemplate() throws Exception {
        IndexTemplateMetadata templateMetadata = addMatchingTemplate(builder -> {
            try {
                builder.putMapping(MapperService.SINGLE_MAPPING_NAME, "{\"_doc\": {}}");
            } catch (IOException e) {
                ExceptionsHelper.reThrowIfNotNull(e);
            }
        });
        Map<String, Object> mappings = parseV1Mappings("", List.of(templateMetadata.mappings()), xContentRegistry());
        assertThat(mappings, Matchers.hasKey(MapperService.SINGLE_MAPPING_NAME));
    }

    public void testBuildIndexMetadata() {
        IndexMetadata sourceIndexMetadata = IndexMetadata.builder("parent")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current()).build())
            .numberOfShards(1)
            .numberOfReplicas(0)
            .primaryTerm(0, 3L)
            .build();

        Settings indexSettings = indexSettings(IndexVersion.current(), 1, 0).build();
        List<AliasMetadata> aliases = List.of(AliasMetadata.builder("alias1").build());
        IndexMetadata indexMetadata = buildIndexMetadata("test", aliases, () -> null, indexSettings, 4, sourceIndexMetadata, false);

        assertThat(indexMetadata.getAliases().size(), is(1));
        assertThat(indexMetadata.getAliases().keySet().iterator().next(), is("alias1"));
        assertThat("The source index primary term must be used", indexMetadata.primaryTerm(0), is(3L));
        assertThat(indexMetadata.getTimestampRange(), equalTo(IndexLongFieldRange.NO_SHARDS));
        assertThat(indexMetadata.getEventIngestedRange(), equalTo(IndexLongFieldRange.NO_SHARDS));
    }

    public void testGetIndexNumberOfRoutingShardsWithNullSourceIndex() {
        Settings indexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 3)
            .build();
        int targetRoutingNumberOfShards = getIndexNumberOfRoutingShards(indexSettings, null);
        assertThat(
            "When the target routing number of shards is not specified the expected value is the configured number of shards "
                + "multiplied by 2 at most ten times (ie. 3 * 2^8)",
            targetRoutingNumberOfShards,
            is(768)
        );
    }

    public void testGetIndexNumberOfRoutingShardsWhenExplicitlyConfigured() {
        Settings indexSettings = Settings.builder()
            .put(INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING.getKey(), 9)
            .put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 3)
            .build();
        int targetRoutingNumberOfShards = getIndexNumberOfRoutingShards(indexSettings, null);
        assertThat(targetRoutingNumberOfShards, is(9));
    }

    public void testGetIndexNumberOfRoutingShardsNullVsNotDefined() {
        int numberOfPrimaryShards = randomIntBetween(1, 16);
        Settings indexSettings = settings(IndexVersion.current()).put(INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING.getKey(), (String) null)
            .put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), numberOfPrimaryShards)
            .build();
        int targetRoutingNumberOfShardsWithNull = getIndexNumberOfRoutingShards(indexSettings, null);
        indexSettings = settings(IndexVersion.current()).put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), numberOfPrimaryShards).build();
        int targetRoutingNumberOfShardsWithNotDefined = getIndexNumberOfRoutingShards(indexSettings, null);
        assertThat(targetRoutingNumberOfShardsWithNull, is(targetRoutingNumberOfShardsWithNotDefined));
    }

    public void testGetIndexNumberOfRoutingShardsNull() {
        Settings indexSettings = settings(IndexVersion.current()).put(INDEX_NUMBER_OF_ROUTING_SHARDS_SETTING.getKey(), (String) null)
            .put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 2)
            .build();
        int targetRoutingNumberOfShardsWithNull = getIndexNumberOfRoutingShards(indexSettings, null);
        assertThat(targetRoutingNumberOfShardsWithNull, is(1024));
    }

    public void testGetIndexNumberOfRoutingShardsYieldsSourceNumberOfShards() {
        Settings indexSettings = Settings.builder().put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 3).build();

        IndexMetadata sourceIndexMetadata = IndexMetadata.builder("parent")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current()).build())
            .numberOfShards(6)
            .numberOfReplicas(0)
            .build();

        int targetRoutingNumberOfShards = getIndexNumberOfRoutingShards(indexSettings, sourceIndexMetadata);
        assertThat(targetRoutingNumberOfShards, is(6));
    }

    private Optional<String> aggregatedTierPreference(Settings settings, boolean isDataStream) {
        Settings templateSettings = Settings.EMPTY;
        request.settings(Settings.EMPTY);

        if (randomBoolean()) {
            templateSettings = settings;
        } else {
            request.settings(settings);
        }

        if (isDataStream) {
            request.dataStreamName(randomAlphaOfLength(10));
        } else {
            request.dataStreamName(null);
        }
        Settings aggregatedIndexSettings = aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            templateSettings,
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Set.of(new DataTier.DefaultHotAllocationSettingProvider())
        );

        if (aggregatedIndexSettings.keySet().contains(DataTier.TIER_PREFERENCE)) {
            return Optional.of(aggregatedIndexSettings.get(DataTier.TIER_PREFERENCE));
        } else {
            return Optional.empty();
        }
    }

    public void testEnforceDefaultTierPreference() {
        Settings settings;
        Optional<String> tier;

        // empty settings gets the appropriate tier
        settings = Settings.EMPTY;
        tier = aggregatedTierPreference(settings, false);
        assertEquals(DataTier.DATA_CONTENT, tier.get());

        settings = Settings.EMPTY;
        tier = aggregatedTierPreference(settings, true);
        assertEquals(DataTier.DATA_HOT, tier.get());

        // an explicit tier is respected
        settings = Settings.builder().put(DataTier.TIER_PREFERENCE, DataTier.DATA_COLD).build();
        tier = aggregatedTierPreference(settings, randomBoolean());
        assertEquals(DataTier.DATA_COLD, tier.get());

        // any of the INDEX_ROUTING_.*_GROUP_PREFIX settings still result in a default
        String includeRoutingSetting = randomFrom(
            IndexMetadata.INDEX_ROUTING_REQUIRE_GROUP_PREFIX,
            IndexMetadata.INDEX_ROUTING_EXCLUDE_GROUP_PREFIX,
            IndexMetadata.INDEX_ROUTING_INCLUDE_GROUP_PREFIX
        ) + "." + randomAlphaOfLength(10);
        settings = Settings.builder().put(includeRoutingSetting, randomAlphaOfLength(10)).build();
        tier = aggregatedTierPreference(settings, false);
        assertEquals(DataTier.DATA_CONTENT, tier.get());

        // an explicit null gets the appropriate tier
        settings = Settings.builder().putNull(DataTier.TIER_PREFERENCE).build();
        tier = aggregatedTierPreference(settings, false);
        assertEquals(DataTier.DATA_CONTENT, tier.get());

        settings = Settings.builder().putNull(DataTier.TIER_PREFERENCE).build();
        tier = aggregatedTierPreference(settings, true);
        assertEquals(DataTier.DATA_HOT, tier.get());
    }

    public void testRejectWithSoftDeletesDisabled() {
        final IllegalArgumentException error = expectThrows(IllegalArgumentException.class, () -> {
            request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test");
            request.settings(Settings.builder().put(INDEX_SOFT_DELETES_SETTING.getKey(), false).build());
            aggregateIndexSettings(
                ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
                request,
                Settings.EMPTY,
                null,
                null,
                Settings.EMPTY,
                IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
                randomShardLimitService(),
                Collections.emptySet()
            );
        });
        assertThat(
            error.getMessage(),
            equalTo(
                "Creating indices with soft-deletes disabled is no longer supported. "
                    + "Please do not specify a value for setting [index.soft_deletes.enabled]."
            )
        );
    }

    public void testRejectTranslogRetentionSettings() {
        request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test");
        final Settings.Builder settings = Settings.builder();
        if (randomBoolean()) {
            settings.put(IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING.getKey(), TimeValue.timeValueMillis(between(1, 120)));
        } else {
            settings.put(IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING.getKey(), between(1, 128) + "mb");
        }
        if (randomBoolean()) {
            settings.put(
                SETTING_VERSION_CREATED,
                IndexVersionUtils.randomVersionBetween(random(), IndexVersions.V_8_0_0, IndexVersion.current())
            );
        }
        request.settings(settings.build());
        IllegalArgumentException error = expectThrows(
            IllegalArgumentException.class,
            () -> aggregateIndexSettings(
                ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
                request,
                Settings.EMPTY,
                null,
                null,
                Settings.EMPTY,
                IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
                randomShardLimitService(),
                Collections.emptySet()
            )
        );
        assertThat(
            error.getMessage(),
            equalTo(
                "Translog retention settings [index.translog.retention.age] "
                    + "and [index.translog.retention.size] are no longer supported. Please do not specify values for these settings"
            )
        );
    }

    public void testDeprecateTranslogRetentionSettings() {
        request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test");
        final Settings.Builder settings = Settings.builder();
        if (randomBoolean()) {
            settings.put(IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING.getKey(), TimeValue.timeValueMillis(between(1, 120)));
        } else {
            settings.put(IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING.getKey(), between(1, 128) + "mb");
        }
        settings.put(SETTING_VERSION_CREATED, IndexVersionUtils.randomPreviousCompatibleVersion(random(), IndexVersions.V_8_0_0));
        request.settings(settings.build());
        aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            Settings.EMPTY,
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );
        assertWarnings(
            "Translog retention settings [index.translog.retention.age] "
                + "and [index.translog.retention.size] are deprecated and effectively ignored. They will be removed in a future version."
        );
    }

    public void testDeprecateSimpleFS() {
        request = new CreateIndexClusterStateUpdateRequest("create index", projectId, "test", "test");
        final Settings.Builder settings = Settings.builder();
        settings.put(IndexModule.INDEX_STORE_TYPE_SETTING.getKey(), IndexModule.Type.SIMPLEFS.getSettingsKey());

        request.settings(settings.build());
        aggregateIndexSettings(
            ClusterState.builder(ClusterState.EMPTY_STATE).putProjectMetadata(ProjectMetadata.builder(projectId).build()).build(),
            request,
            Settings.EMPTY,
            null,
            null,
            Settings.EMPTY,
            IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
            randomShardLimitService(),
            Collections.emptySet()
        );
        assertWarnings(
            "[simplefs] is deprecated and will be removed in 8.0. Use [niofs] or other file systems instead. "
                + "Elasticsearch 7.15 or later uses [niofs] for the [simplefs] store type "
                + "as it offers superior or equivalent performance to [simplefs]."
        );
    }

    public void testClusterStateCreateIndexWithClusterBlockTransformer() {
        {
            var emptyClusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
                .putProjectMetadata(ProjectMetadata.builder(projectId))
                .build();
            var updatedClusterState = clusterStateCreateIndex(
                emptyClusterState,
                projectId,
                IndexMetadata.builder("test")
                    .settings(settings(IndexVersion.current()))
                    .numberOfShards(1)
                    .numberOfReplicas(randomIntBetween(1, 3))
                    .build(),
                null,
                MetadataCreateIndexService.createClusterBlocksTransformerForIndexCreation(Settings.EMPTY),
                TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
            );
            assertThat(updatedClusterState.blocks().indices(projectId), is(anEmptyMap()));
            assertThat(updatedClusterState.blocks().hasIndexBlock(projectId, "test", IndexMetadata.INDEX_REFRESH_BLOCK), is(false));
            assertThat(updatedClusterState.routingTable(projectId).index("test"), is(notNullValue()));
        }
        {
            var minTransportVersion = TransportVersionUtils.randomCompatibleVersion(random());
            var emptyClusterState = ClusterState.builder(ClusterState.EMPTY_STATE)
                .putProjectMetadata(ProjectMetadata.builder(projectId))
                .nodes(DiscoveryNodes.builder().add(DiscoveryNodeUtils.create("_node_id")).build())
                .putCompatibilityVersions("_node_id", new CompatibilityVersions(minTransportVersion, Map.of()))
                .build();
            var settings = Settings.builder()
                .put(DiscoveryNode.STATELESS_ENABLED_SETTING_NAME, true)
                .put(MetadataCreateIndexService.USE_INDEX_REFRESH_BLOCK_SETTING_NAME, true)
                .build();
            int nbReplicas = randomIntBetween(0, 1);
            var updatedClusterState = clusterStateCreateIndex(
                emptyClusterState,
                projectId,
                IndexMetadata.builder("test")
                    .settings(settings(IndexVersion.current()))
                    .numberOfShards(1)
                    .numberOfReplicas(nbReplicas)
                    .build()
                    .withTimestampRanges(IndexLongFieldRange.UNKNOWN, IndexLongFieldRange.UNKNOWN),
                null,
                MetadataCreateIndexService.createClusterBlocksTransformerForIndexCreation(settings),
                TestShardRoutingRoleStrategies.DEFAULT_ROLE_ONLY
            );

            var expectRefreshBlock = 0 < nbReplicas && minTransportVersion.onOrAfter(TransportVersions.NEW_REFRESH_CLUSTER_BLOCK);
            assertThat(updatedClusterState.blocks().indices(projectId), is(aMapWithSize(expectRefreshBlock ? 1 : 0)));
            assertThat(
                updatedClusterState.blocks().hasIndexBlock(projectId, "test", IndexMetadata.INDEX_REFRESH_BLOCK),
                is(expectRefreshBlock)
            );
            assertThat(updatedClusterState.routingTable(projectId).index("test"), is(notNullValue()));
        }
    }

    public void testCreateClusterBlocksTransformerForIndexCreation() {
        boolean isStateless = randomBoolean();
        boolean useRefreshBlock = randomBoolean();
        var minTransportVersion = TransportVersionUtils.randomCompatibleVersion(random());

        var applier = MetadataCreateIndexService.createClusterBlocksTransformerForIndexCreation(
            Settings.builder()
                .put(DiscoveryNode.STATELESS_ENABLED_SETTING_NAME, isStateless)
                .put(MetadataCreateIndexService.USE_INDEX_REFRESH_BLOCK_SETTING_NAME, useRefreshBlock)
                .build()
        );
        assertThat(applier, notNullValue());

        var blocks = ClusterBlocks.builder().blocks(ClusterState.EMPTY_STATE.blocks());
        applier.apply(
            blocks,
            projectId,
            IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(1)
                .numberOfReplicas(randomIntBetween(1, 3))
                .build(),
            minTransportVersion
        );
        assertThat(
            blocks.hasIndexBlock(projectId, "test", IndexMetadata.INDEX_REFRESH_BLOCK),
            is(isStateless && useRefreshBlock && minTransportVersion.onOrAfter(TransportVersions.NEW_REFRESH_CLUSTER_BLOCK))
        );
    }

    private IndexTemplateMetadata addMatchingTemplate(Consumer<IndexTemplateMetadata.Builder> configurator) {
        IndexTemplateMetadata.Builder builder = templateMetadataBuilder("template1", "te*");
        configurator.accept(builder);
        return builder.build();
    }

    private IndexTemplateMetadata.Builder templateMetadataBuilder(String name, String pattern) {
        return IndexTemplateMetadata.builder(name).patterns(Collections.singletonList(pattern));
    }

    private CompressedXContent createMapping(String fieldName, String fieldType) {
        try {
            final String mapping = Strings.toString(
                XContentFactory.jsonBuilder()
                    .startObject()
                    .startObject("_doc")
                    .startObject("properties")
                    .startObject(fieldName)
                    .field("type", fieldType)
                    .endObject()
                    .endObject()
                    .endObject()
                    .endObject()
            );

            return new CompressedXContent(mapping);
        } catch (IOException e) {
            throw ExceptionsHelper.convertToRuntime(e);
        }
    }

    private ShardLimitValidator randomShardLimitService() {
        return createTestShardLimitService(randomIntBetween(10, 10000));
    }

    private void withTemporaryClusterService(BiConsumer<ClusterService, ThreadPool> consumer) {
        final ThreadPool threadPool = new TestThreadPool(getTestName());
        final ClusterService clusterService = ClusterServiceUtils.createClusterService(threadPool);
        try {
            consumer.accept(clusterService, threadPool);
        } finally {
            clusterService.stop();
            threadPool.shutdown();
        }
    }

    private List<String> validateShrinkIndex(ClusterState state, String sourceIndex, String targetIndexName, Settings targetIndexSettings) {
        return MetadataCreateIndexService.validateShrinkIndex(
            state.metadata().getProject(projectId),
            state.blocks(),
            state.routingTable(projectId),
            sourceIndex,
            targetIndexName,
            targetIndexSettings
        );
    }

    private void validateSplitIndex(ClusterState state, String sourceIndex, String targetIndexName, Settings targetIndexSettings) {
        MetadataCreateIndexService.validateSplitIndex(
            state.metadata().getProject(projectId),
            state.blocks(),
            sourceIndex,
            targetIndexName,
            targetIndexSettings
        );
    }

    private Settings aggregateIndexSettings(
        ClusterState state,
        CreateIndexClusterStateUpdateRequest request,
        Settings combinedTemplateSettings,
        List<CompressedXContent> combinedTemplateMappings,
        @Nullable IndexMetadata sourceMetadata,
        Settings settings,
        IndexScopedSettings indexScopedSettings,
        ShardLimitValidator shardLimitValidator,
        Set<IndexSettingProvider> indexSettingProviders
    ) {
        return MetadataCreateIndexService.aggregateIndexSettings(
            state.metadata(),
            state.getMetadata().getProject(projectId),
            state.nodes(),
            state.blocks(),
            state.routingTable(projectId),
            request,
            combinedTemplateSettings,
            combinedTemplateMappings,
            sourceMetadata,
            settings,
            indexScopedSettings,
            shardLimitValidator,
            indexSettingProviders
        );
    }
}
