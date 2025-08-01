/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference;

import org.elasticsearch.features.FeatureSpecification;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.xpack.inference.mapper.SemanticInferenceMetadataFieldsMapper;
import org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper;
import org.elasticsearch.xpack.inference.rank.textsimilarity.TextSimilarityRankRetrieverBuilder;

import java.util.HashSet;
import java.util.Set;

import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper.SEMANTIC_TEXT_EXCLUDE_SUB_FIELDS_FROM_FIELD_CAPS;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper.SEMANTIC_TEXT_INDEX_OPTIONS;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper.SEMANTIC_TEXT_INDEX_OPTIONS_WITH_DEFAULTS;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper.SEMANTIC_TEXT_SPARSE_VECTOR_INDEX_OPTIONS;
import static org.elasticsearch.xpack.inference.mapper.SemanticTextFieldMapper.SEMANTIC_TEXT_SUPPORT_CHUNKING_CONFIG;
import static org.elasticsearch.xpack.inference.queries.SemanticKnnVectorQueryRewriteInterceptor.SEMANTIC_KNN_FILTER_FIX;
import static org.elasticsearch.xpack.inference.queries.SemanticKnnVectorQueryRewriteInterceptor.SEMANTIC_KNN_VECTOR_QUERY_REWRITE_INTERCEPTION_SUPPORTED;
import static org.elasticsearch.xpack.inference.queries.SemanticMatchQueryRewriteInterceptor.SEMANTIC_MATCH_QUERY_REWRITE_INTERCEPTION_SUPPORTED;
import static org.elasticsearch.xpack.inference.queries.SemanticSparseVectorQueryRewriteInterceptor.SEMANTIC_SPARSE_VECTOR_QUERY_REWRITE_INTERCEPTION_SUPPORTED;
import static org.elasticsearch.xpack.inference.rank.textsimilarity.TextSimilarityRankRetrieverBuilder.RERANK_SNIPPETS;
import static org.elasticsearch.xpack.inference.rank.textsimilarity.TextSimilarityRankRetrieverBuilder.TEXT_SIMILARITY_RERANKER_SNIPPETS;

/**
 * Provides inference features.
 */
public class InferenceFeatures implements FeatureSpecification {

    private static final NodeFeature SEMANTIC_TEXT_HIGHLIGHTER = new NodeFeature("semantic_text.highlighter");
    private static final NodeFeature SEMANTIC_TEXT_HIGHLIGHTER_DEFAULT = new NodeFeature("semantic_text.highlighter.default");
    private static final NodeFeature TEST_RERANKING_SERVICE_PARSE_TEXT_AS_SCORE = new NodeFeature(
        "test_reranking_service.parse_text_as_score"
    );
    private static final NodeFeature TEST_RULE_RETRIEVER_WITH_INDICES_THAT_DONT_RETURN_RANK_DOCS = new NodeFeature(
        "test_rule_retriever.with_indices_that_dont_return_rank_docs"
    );
    private static final NodeFeature SEMANTIC_QUERY_REWRITE_INTERCEPTORS_PROPAGATE_BOOST_AND_QUERY_NAME_FIX = new NodeFeature(
        "semantic_query_rewrite_interceptors.propagate_boost_and_query_name_fix"
    );
    private static final NodeFeature SEMANTIC_TEXT_MATCH_ALL_HIGHLIGHTER = new NodeFeature("semantic_text.match_all_highlighter");
    private static final NodeFeature COHERE_V2_API = new NodeFeature("inference.cohere.v2");
    public static final NodeFeature SEMANTIC_TEXT_HIGHLIGHTING_FLAT = new NodeFeature("semantic_text.highlighter.flat_index_options");

    @Override
    public Set<NodeFeature> getTestFeatures() {
        var testFeatures = new HashSet<>(
            Set.of(
                SemanticTextFieldMapper.SEMANTIC_TEXT_IN_OBJECT_FIELD_FIX,
                SemanticTextFieldMapper.SEMANTIC_TEXT_SINGLE_FIELD_UPDATE_FIX,
                SemanticTextFieldMapper.SEMANTIC_TEXT_DELETE_FIX,
                SemanticTextFieldMapper.SEMANTIC_TEXT_ZERO_SIZE_FIX,
                SemanticTextFieldMapper.SEMANTIC_TEXT_ALWAYS_EMIT_INFERENCE_ID_FIX,
                SemanticTextFieldMapper.SEMANTIC_TEXT_SKIP_INFERENCE_FIELDS,
                SEMANTIC_TEXT_HIGHLIGHTER,
                SEMANTIC_MATCH_QUERY_REWRITE_INTERCEPTION_SUPPORTED,
                SEMANTIC_SPARSE_VECTOR_QUERY_REWRITE_INTERCEPTION_SUPPORTED,
                SemanticInferenceMetadataFieldsMapper.EXPLICIT_NULL_FIXES,
                SEMANTIC_KNN_VECTOR_QUERY_REWRITE_INTERCEPTION_SUPPORTED,
                TextSimilarityRankRetrieverBuilder.TEXT_SIMILARITY_RERANKER_ALIAS_HANDLING_FIX,
                TextSimilarityRankRetrieverBuilder.TEXT_SIMILARITY_RERANKER_MINSCORE_FIX,
                SemanticInferenceMetadataFieldsMapper.INFERENCE_METADATA_FIELDS_ENABLED_BY_DEFAULT,
                SEMANTIC_TEXT_HIGHLIGHTER_DEFAULT,
                SEMANTIC_KNN_FILTER_FIX,
                TEST_RERANKING_SERVICE_PARSE_TEXT_AS_SCORE,
                SemanticTextFieldMapper.SEMANTIC_TEXT_BIT_VECTOR_SUPPORT,
                SemanticTextFieldMapper.SEMANTIC_TEXT_HANDLE_EMPTY_INPUT,
                TEST_RULE_RETRIEVER_WITH_INDICES_THAT_DONT_RETURN_RANK_DOCS,
                SEMANTIC_TEXT_SUPPORT_CHUNKING_CONFIG,
                SEMANTIC_TEXT_MATCH_ALL_HIGHLIGHTER,
                SEMANTIC_TEXT_EXCLUDE_SUB_FIELDS_FROM_FIELD_CAPS,
                SEMANTIC_TEXT_INDEX_OPTIONS,
                COHERE_V2_API,
                SEMANTIC_TEXT_INDEX_OPTIONS_WITH_DEFAULTS,
                SEMANTIC_QUERY_REWRITE_INTERCEPTORS_PROPAGATE_BOOST_AND_QUERY_NAME_FIX,
                SEMANTIC_TEXT_HIGHLIGHTING_FLAT,
                SEMANTIC_TEXT_SPARSE_VECTOR_INDEX_OPTIONS
            )
        );
        if (RERANK_SNIPPETS.isEnabled()) {
            testFeatures.add(TEXT_SIMILARITY_RERANKER_SNIPPETS);
        }
        return testFeatures;
    }
}
