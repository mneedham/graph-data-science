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
package org.neo4j.gds.embeddings.randomprojections;

import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.core.concurrency.ParallelUtil;
import org.neo4j.graphalgo.core.utils.ProgressLogger;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.core.utils.paged.HugeObjectArray;
import org.neo4j.graphalgo.utils.CloseableThreadLocal;

import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public class RandomProjection extends Algorithm<RandomProjection, RandomProjection> {

    private final Graph graph;
    private final int concurrency;
    private final boolean normalizeL2;
    private final double normalizationStrength;
    private final HugeObjectArray<double[]> embeddings;
    private final HugeObjectArray<double[]> embeddingA;
    private final HugeObjectArray<double[]> embeddingB;

    private final int embeddingDimension;
    private final int sparsity;
    private final int iterations;
    private final List<Double> iterationWeights;

    public RandomProjection(
        Graph graph,
        RandomProjectionBaseConfig config,
        ProgressLogger progressLogger,
        AllocationTracker tracker
    ) {
        this.graph = graph;
        this.progressLogger = progressLogger;

        this.embeddings = HugeObjectArray.newArray(double[].class, graph.nodeCount(), tracker);
        this.embeddingA = HugeObjectArray.newArray(double[].class, graph.nodeCount(), tracker);
        this.embeddingB = HugeObjectArray.newArray(double[].class, graph.nodeCount(), tracker);

        this.embeddingDimension = config.embeddingDimension();
        this.sparsity = config.sparsity();
        this.iterations = config.maxIterations();
        this.iterationWeights = config.iterationWeights();
        this.normalizationStrength = config.normalizationStrength();
        this.normalizeL2 = config.normalizeL2();
        this.concurrency = config.concurrency();

        int embeddingSize = iterationWeights.isEmpty() ? embeddingDimension * iterations : embeddingDimension;
        this.embeddings.setAll((i) -> new double[embeddingSize]);
    }

    @Override
    public RandomProjection compute() {
        initRandomVectors();
        propagateEmbeddings();
        return me();
    }

    public HugeObjectArray<double[]> embeddings() {
        return this.embeddings;
    }

    HugeObjectArray<double[]> currentEmbedding(int iteration) {
        return iteration % 2 == 0
            ? this.embeddingA
            : this.embeddingB;
    }

    @Override
    public RandomProjection me() {
        return this;
    }

    @Override
    public void release() {
        this.embeddingA.release();
        this.embeddingB.release();
    }

    void initRandomVectors() {
        double probability = 1.0f / (2.0f * sparsity);
        double sqrtSparsity = Math.sqrt(sparsity);
        double sqrtEmbeddingDimension = Math.sqrt(embeddingDimension);

        progressLogger.logMessage("Computing random vectors");
        ParallelUtil.parallelForEachNode(graph, concurrency, nodeId -> {
            progressLogger.logProgress();

            ThreadLocal<Random> random = ThreadLocal.withInitial(HighQualityRandom::new);
            int degree = graph.degree(nodeId);
            double scaling = degree == 0
                ? 1.0f
                : Math.pow(degree, normalizationStrength);

            double entryValue = scaling * sqrtSparsity / sqrtEmbeddingDimension;
            double[] randomVector = computeRandomVector(random.get(), probability, entryValue);
            embeddingB.set(nodeId, randomVector);
        });
    }

    void propagateEmbeddings() {
        for (int i = 0; i < iterations; i++) {
            progressLogger.reset(graph.relationshipCount());
            progressLogger.logMessage(formatWithLocale("Start iteration %s", i));

            var localCurrent = i % 2 == 0 ? embeddingA : embeddingB;
            var localPrevious = i % 2 == 0 ? embeddingB : embeddingA;

            try (var concurrentGraphCopy = CloseableThreadLocal.withInitial(graph::concurrentCopy)) {
                ParallelUtil.parallelForEachNode(graph, concurrency, nodeId -> {
                    double[] currentEmbedding = new double[embeddingDimension];
                    localCurrent.set(nodeId, currentEmbedding);
                    concurrentGraphCopy.get().forEachRelationship(nodeId, (source, target) -> {
                        addArrayValues(currentEmbedding, localPrevious.get(target));
                        return true;
                    });
                    progressLogger.logProgress(graph.degree(nodeId));

                    int degree = graph.degree(nodeId) == 0 ? 1 : graph.degree(nodeId);
                    double degreeScale = 1.0f / degree;
                    multiplyArrayValues(currentEmbedding, degreeScale);
                });
            }

            int offset = embeddingDimension * i;
            double weight = iterationWeights.isEmpty()
                ? Double.NaN
                : iterationWeights.get(i);
            ParallelUtil.parallelForEachNode(graph, concurrency, nodeId -> {
                double[] embedding = embeddings.get(nodeId);

                double[] newEmbedding = localCurrent.get(nodeId);
                if (normalizeL2) {
                    l2Normalize(newEmbedding);
                }
                if (iterationWeights.isEmpty()) {
                    System.arraycopy(newEmbedding, 0, embedding, offset, embeddingDimension);
                } else {
                    updateEmbeddings(weight, embedding, newEmbedding);
                }
            });
        }
    }

    private double[] computeRandomVector(Random random, double probability, double entryValue) {
        double[] randomVector = new double[embeddingDimension];
        for (int i = 0; i < embeddingDimension; i++) {
            randomVector[i] = computeRandomEntry(random, probability, entryValue);
        }
        return randomVector;
    }

    private double computeRandomEntry(Random random, double probability, double entryValue) {
        double randomValue = random.nextDouble();

        if (randomValue < probability) {
            return entryValue;
        } else if (randomValue < probability * 2.0f) {
            return -entryValue;
        } else {
            return 0.0f;
        }
    }

    private void updateEmbeddings(double weight, double[] embedding, double[] newEmbedding) {
        multiplyArrayValues(newEmbedding, weight);
        addArrayValues(embedding, newEmbedding);
    }

    private void addArrayValues(double[] lhs, double[] rhs) {
        for (int i = 0; i < lhs.length; i++) {
            lhs[i] += rhs[i];
        }
    }

    private void multiplyArrayValues(double[] lhs, double scalar) {
        for (int i = 0; i < lhs.length; i++) {
            lhs[i] *= scalar;
        }
    }

    private void l2Normalize(double[] array) {
        double sum = 0.0f;
        for (double value : array) {
            sum += value * value;
        }
        double sqrtSum = sum == 0 ? 1 : Math.sqrt(sum);
        double scaling = 1 / sqrtSum;
        for (int i = 0; i < array.length; i++) {
            array[i] *= scaling;
        }
    }

    public class HighQualityRandom extends Random {
        private Lock l = new ReentrantLock();
        private long u;
        private long v = 4101842887655102017L;
        private long w = 1;

        public HighQualityRandom() {
            this(System.nanoTime());
        }

        public HighQualityRandom(long seed) {
            l.lock();
            u = seed ^ v;
            nextLong();
            v = u;
            nextLong();
            w = v;
            nextLong();
            l.unlock();
        }

        public long nextLong() {
            l.lock();
            try {
                u = u * 2862933555777941757L + 7046029254386353087L;
                v ^= v >>> 17;
                v ^= v << 31;
                v ^= v >>> 8;
                w = 4294957665L * (w & 0xffffffff) + (w >>> 32);
                long x = u ^ (u << 21);
                x ^= x >>> 35;
                x ^= x << 4;
                long ret = (x + v) ^ w;
                return ret;
            } finally {
                l.unlock();
            }
        }

        protected int next(int bits) {
            return (int) (nextLong() >>> (64-bits));
        }
    }
}
