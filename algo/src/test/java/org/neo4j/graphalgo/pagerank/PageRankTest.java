/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.pagerank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.TestLog;
import org.neo4j.graphalgo.TestProgressLogger;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.extension.GdlExtension;
import org.neo4j.graphalgo.extension.GdlGraph;
import org.neo4j.graphalgo.extension.Inject;
import org.neo4j.graphalgo.extension.TestGraph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.graphalgo.TestSupport.assertMemoryEstimation;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

@GdlExtension
final class PageRankTest {

    @GdlGraph
    @GdlGraph(graphName = "reverseGraph", orientation = Orientation.REVERSE)
    private static final String GRAPH =
        "CREATE" +
        "  (a:Label)" +
        ", (b:Label)" +
        ", (c:Label)" +
        ", (d:Label)" +
        ", (e:Label)" +
        ", (f:Label)" +
        ", (g:Label)" +
        ", (h:Label)" +
        ", (i:Label)" +
        ", (j:Label)" +
        ", (b)-[:TYPE]->(c)" +
        ", (c)-[:TYPE]->(b)" +
        ", (d)-[:TYPE]->(a)" +
        ", (d)-[:TYPE]->(b)" +
        ", (e)-[:TYPE]->(b)" +
        ", (e)-[:TYPE]->(d)" +
        ", (e)-[:TYPE]->(f)" +
        ", (f)-[:TYPE]->(b)" +
        ", (f)-[:TYPE]->(e)";

    @Inject
    private TestGraph graph;

    @Inject(graphName = "reverseGraph")
    private TestGraph reverseGraph;

    private static final PageRankBaseConfig DEFAULT_CONFIG = defaultConfigBuilder().build();

    static ImmutablePageRankStreamConfig.Builder defaultConfigBuilder() {
        return ImmutablePageRankStreamConfig.builder()
            .maxIterations(40);
    }

    @Test
    void testOnOutgoingRelationships() {
        var expected = Map.of(
            graph.toOriginalNodeId("a"), 0.243007,
            graph.toOriginalNodeId("b"), 1.9183995,
            graph.toOriginalNodeId("c"), 1.7806315,
            graph.toOriginalNodeId("d"), 0.21885,
            graph.toOriginalNodeId("e"), 0.243007,
            graph.toOriginalNodeId("f"), 0.21885,
            graph.toOriginalNodeId("g"), 0.15,
            graph.toOriginalNodeId("h"), 0.15,
            graph.toOriginalNodeId("i"), 0.15,
            graph.toOriginalNodeId("j"), 0.15
        );

        assertResult(this.graph, PageRankAlgorithmType.NON_WEIGHTED, expected);
    }

    @Test
    void testOnIncomingRelationships() {
        var expected = Map.of(
            reverseGraph.toOriginalNodeId("a"), 0.15,
            reverseGraph.toOriginalNodeId("b"), 0.3386727,
            reverseGraph.toOriginalNodeId("c"), 0.2219679,
            reverseGraph.toOriginalNodeId("d"), 0.3494679,
            reverseGraph.toOriginalNodeId("e"), 2.5463981,
            reverseGraph.toOriginalNodeId("f"), 2.3858317,
            reverseGraph.toOriginalNodeId("g"), 0.15,
            reverseGraph.toOriginalNodeId("h"), 0.15,
            reverseGraph.toOriginalNodeId("i"), 0.15,
            reverseGraph.toOriginalNodeId("j"), 0.15
        );

        assertResult(reverseGraph, PageRankAlgorithmType.NON_WEIGHTED, expected);
    }

    @Test
    void correctPartitionBoundariesForAllNodes() {
        // explicitly list all source nodes to prevent the 'we got everything' optimization
        PageRankAlgorithmType.NON_WEIGHTED
            .create(
                graph,
                LongStream.range(0L, graph.nodeCount()),
                DEFAULT_CONFIG,
                1,
                null,
                1,
                ProgressLogger.NULL_LOGGER,
                AllocationTracker.EMPTY
            )
            .compute();
        // should not throw
    }

    static Stream<Arguments> expectedMemoryEstimation() {
        return Stream.of(
            Arguments.of(1, 2000416L, 2000416L),
            Arguments.of(4, 3201304L, 3201304L),
            Arguments.of(42, 18451288L, 18451288L)
        );
    }

    @ParameterizedTest
    @MethodSource("org.neo4j.graphalgo.pagerank.PageRankTest#expectedMemoryEstimation")
    void shouldComputeMemoryEstimation(int concurrency, long expectedMinBytes, long expectedMaxBytes) {
        assertMemoryEstimation(
            () -> new PageRankFactory<>(PageRankAlgorithmType.NON_WEIGHTED).memoryEstimation(defaultConfigBuilder().build()),
            100_000L,
            concurrency,
            expectedMinBytes,
            expectedMaxBytes
        );
    }

    @Test
    void shouldLogProgress() {
        var config = ImmutablePageRankStreamConfig.builder().build();

        var testLogger = new TestProgressLogger(
            graph.relationshipCount(),
            "PageRank",
            config.concurrency()
        );

        var pageRank = PageRankAlgorithmType.NON_WEIGHTED.create(
            graph,
            config,
            LongStream.empty(),
            testLogger
        );

        pageRank.compute();

        List<AtomicLong> progresses = testLogger.getProgresses();

        assertEquals(progresses.size(), pageRank.iterations());
        progresses.forEach(progress -> assertEquals(graph.relationshipCount(), progress.get()));

        assertTrue(testLogger.containsMessage(TestLog.INFO, ":: Start"));
        LongStream.range(1, pageRank.iterations() + 1).forEach(iteration -> {
            assertTrue(testLogger.containsMessage(TestLog.INFO, formatWithLocale("Iteration %d :: Start", iteration)));
            assertTrue(testLogger.containsMessage(TestLog.INFO, formatWithLocale("Iteration %d :: Start", iteration)));
        });
        assertTrue(testLogger.containsMessage(TestLog.INFO, ":: Finished"));
    }

    static void assertResult(Graph graph, PageRankAlgorithm algorithmType, Map<Long, Double> expected) {
        var rankResult = algorithmType
            .create(graph, DEFAULT_CONFIG, LongStream.empty(), ProgressLogger.NULL_LOGGER)
            .compute()
            .result();

        expected.forEach((originalNodeId, expectedPageRank) -> {
            assertEquals(
                expected.get(originalNodeId),
                rankResult.score(graph.toMappedNodeId(originalNodeId)),
                1e-2,
                "Node#" + originalNodeId
            );
        });
    }
}
