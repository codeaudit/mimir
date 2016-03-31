/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Isak Karlsson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.briljantframework.mimir.classification.tree.pattern;

import java.util.*;

import org.briljantframework.array.DoubleArray;
import org.briljantframework.data.Is;
import org.briljantframework.data.Na;
import org.briljantframework.data.dataframe.DataFrame;
import org.briljantframework.data.statistics.FastStatistics;
import org.briljantframework.data.vector.Vector;
import org.briljantframework.mimir.classification.tree.*;
import org.briljantframework.mimir.data.*;
import org.briljantframework.mimir.distance.Distance;
import org.briljantframework.mimir.distance.EuclideanDistance;
import org.briljantframework.mimir.shapelet.ChannelShapelet;
import org.briljantframework.mimir.shapelet.Shapelet;
import org.briljantframework.mimir.supervised.AbstractLearner;
import org.briljantframework.mimir.supervised.Predictor;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.ObjectDoubleCursor;

/**
 * @author Isak Karlsson <isak-kar@dsv.su.se>
 */
public class PatternTree<In> extends TreeClassifier<In> {

  /**
   * Number of patterns to inspect at each node
   */
  public static final TypeKey<Integer> PATTERN_COUNT =
      TypeKey.of("inspected_patterns", Integer.class, 10, i -> i > 0);

  /**
   * Number of input elements required to split a node
   */
  public static final TypeKey<Double> MIN_SPLIT_SIZE =
      TypeKey.of("min_split_size", Double.class, 1.0, i -> i > 0);

  private final int depth;

  private PatternTree(List<?> classes, TreeVisitor<In, ?> predictionVisitor, int depth) {
    super(classes, predictionVisitor);
    this.depth = depth;
  }

  public int getDepth() {
    return depth;
  }

  /**
   * An implementation of a shapelet tree
   * <p>
   * <b>The code herein is so ugly that a kitten dies every time someone look at it.</b>
   *
   * @author Isak Karlsson
   */
  public static class Learner<In, E> extends AbstractLearner<In, Object, PatternTree<In>> {

    protected final Gain gain = Gain.INFO;

    private final PatternFactory<? super In, ? extends E> patternFactory;
    private final PatternDistance<? super In, ? super E> patternDistance;

    private final ClassSet classSet;
    private final Assessment assessment;
    private List<?> classes;

    protected Learner(Configurator<In, E> builder, ClassSet classSet, List<?> classes) {
      this.patternDistance = builder.patternDistance;
      this.patternFactory = builder.patternFactory;
      this.set(PATTERN_COUNT, builder.patternCount);
      this.assessment = builder.assessment;
      this.set(MIN_SPLIT_SIZE, builder.minSplit);

      this.classSet = classSet;
      this.classes = classes;
    }

    public Learner(PatternFactory<? super In, ? extends E> factory,
        PatternDistance<? super In, ? super E> patternDistance, ClassSet classSet, List<?> classes,
        TypeMap properties) {
      super(properties);
      this.patternFactory = factory;
      this.patternDistance = patternDistance;
      this.classSet = classSet;
      this.classes = classes;
      this.assessment = Assessment.IG;
    }

    private Gain getGain() {
      return gain;
    }

    @Override
    public PatternTree<In> fit(Input<? extends In> x, Output<?> y) {
      ClassSet classSet = this.classSet;
      List<?> classes = this.classes != null ? this.classes : Outputs.unique(y);
      if (classSet == null) {
        classSet = new ClassSet(y, classes);
      }

      Params params = new Params();
      params.noExamples = classSet.getTotalWeight();
      TreeNode<In, PatternTree.Threshold<E>> node = build(x, y, classSet, params);
      DefaultPatternTreeVisitor<In, E> visitor =
          new DefaultPatternTreeVisitor<>(node, patternDistance);
      return new PatternTree<>(classes, visitor, params.depth);
    }

    protected TreeNode<In, PatternTree.Threshold<E>> build(Input<? extends In> x, Output<?> y,
        ClassSet classSet, Params params) {
      if (classSet.getTotalWeight() <= get(MIN_SPLIT_SIZE) || classSet.getTargetCount() == 1) {
        return TreeLeaf.fromExamples(classSet, classSet.getTotalWeight() / params.noExamples);
      }
      params.depth += 1;
      TreeSplit<PatternTree.Threshold<E>> maxSplit = find(classSet, x, y);
      if (maxSplit == null) {
        return TreeLeaf.fromExamples(classSet, classSet.getTotalWeight() / params.noExamples);
      } else {
        ClassSet left = maxSplit.getLeft();
        ClassSet right = maxSplit.getRight();
        if (left.isEmpty()) {
          return TreeLeaf.fromExamples(right, right.getTotalWeight() / params.noExamples);
        } else if (right.isEmpty()) {
          return TreeLeaf.fromExamples(left, left.getTotalWeight() / params.noExamples);
        } else {
          TreeNode<In, PatternTree.Threshold<E>> leftNode = build(x, y, left, params);
          TreeNode<In, PatternTree.Threshold<E>> rightNode = build(x, y, right, params);
          TreeNode<In, PatternTree.Threshold<E>> missingNode = null;
          if (maxSplit.getMissing() != null && !maxSplit.getMissing().isEmpty()) {
            missingNode = build(x, y, maxSplit.getMissing(), params);
          }

          Vector.Builder classDist = Vector.Builder.of(double.class);
          for (Object target : classSet.getTargets()) {
            classDist.set(target, classSet.get(target).getWeight());
          }

          return new TreeBranch<>(leftNode, rightNode, missingNode, classes, classDist.build(),
              maxSplit.getThreshold(), classSet.getTotalWeight() / params.noExamples);
        }
      }
    }

    public TreeSplit<PatternTree.Threshold<E>> find(ClassSet c, Input<? extends In> x,
        Output<?> y) {
      int patternCount = get(PATTERN_COUNT);
      List<E> shapelets = new ArrayList<>(patternCount);
      for (int i = 0; i < patternCount; i++) {
        E pattern = patternFactory.createPattern(x, c);
        if (pattern != null) {
          shapelets.add(pattern);
        }
      }

      if (shapelets.isEmpty()) {
        System.err.println("debug: empty sample");
        return null;
      }

      TreeSplit<PatternTree.Threshold<E>> bestSplit;
      if (assessment == Assessment.IG) {
        bestSplit = findBestSplit(c, x, y, shapelets);
      } else {
        bestSplit = findBestSplitFstat(c, x, y, shapelets);
      }
      return bestSplit;
    }

    protected TreeSplit<PatternTree.Threshold<E>> findBestSplit(ClassSet classSet,
        Input<? extends In> x, Output<?> y, List<E> subPatterns) {
      Threshold bestThreshold = Learner.Threshold.inf();
      IntDoubleMap bestDistanceMap = null;
      E bestShapelet = null;
      for (E subPattern : subPatterns) {
        IntDoubleMap distanceMap = new IntDoubleOpenHashMap();
        Threshold threshold =
            bestDistanceThresholdInSample(classSet, x, y, subPattern, distanceMap);
        boolean lowerImpurity = threshold.impurity < bestThreshold.impurity;
        boolean equalImpuritySmallerGap =
            threshold.impurity == bestThreshold.impurity && threshold.gap > bestThreshold.gap;
        if (lowerImpurity || equalImpuritySmallerGap) {
          bestShapelet = subPattern;
          bestThreshold = threshold;
          bestDistanceMap = distanceMap;
        }
      }

      if (bestDistanceMap != null && bestShapelet != null) {
        TreeSplit<PatternTree.Threshold<E>> bestSplit =
            split(bestDistanceMap, classSet, bestThreshold.threshold, bestShapelet);
        bestSplit.setImpurity(bestThreshold.impurity);
        // PatternTree.Threshold threshold = bestSplit.getThreshold();
        // threshold.setClassDistances(computeMeanDistance(bestDistanceMap, classSet, y));
        return bestSplit;
      } else {
        return null;
      }
    }

    private Vector computeMeanDistance(IntDoubleMap bestDistanceMap, ClassSet classSet,
        Output<?> y) {
      Map<Object, FastStatistics> cmd = new HashMap<>();
      for (Example example : classSet) {
        double distance = bestDistanceMap.get(example.getIndex());
        Object cls = y.get(example.getIndex());
        FastStatistics statistics = cmd.get(cls);
        if (statistics == null) {
          statistics = new FastStatistics();
          cmd.put(cls, statistics);
        }
        statistics.addValue(distance);
      }
      Vector.Builder builder = Vector.Builder.of(double.class);
      for (Map.Entry<Object, FastStatistics> entry : cmd.entrySet()) {
        builder.set(entry.getKey(), entry.getValue().getMean());
      }
      return builder.build();
    }

    protected Threshold bestDistanceThresholdInSample(ClassSet classSet, Input<? extends In> x,
        Output<?> y, E shapelet, IntDoubleMap memoizedDistances) {
      double sum = 0.0;
      List<ExampleDistance> distances = new ArrayList<>();
      for (Example example : classSet) {
        In record = x.get(example.getIndex());
        double distance = patternDistance.computeDistance(record, shapelet);
        memoizedDistances.put(example.getIndex(), distance);
        distances.add(new ExampleDistance(distance, example));
        if (!Is.NA(distance) && !Double.isInfinite(distance)) {
          sum += distance;
        }
      }

      if (patternDistance.isCategoric(shapelet)) {
        TreeSplit<?> split = split(memoizedDistances, classSet, 0.5, shapelet);
        double impurity = gain.compute(split);
        return new Threshold(0.5, impurity, 0, Double.POSITIVE_INFINITY);
      } else {
        Collections.sort(distances);
        int firstNa = distances.indexOf(ExampleDistance.NA);
        if (firstNa >= 0) {
          distances = distances.subList(0, firstNa);
        }
        return findBestThreshold(distances, classSet, y, sum, firstNa);
      }
    }

    protected TreeSplit<PatternTree.Threshold<E>> findBestSplitFstat(ClassSet classSet,
        Input<? extends In> x, Output<?> y, List<E> shapelets) {
      IntDoubleMap bestDistanceMap = null;
      List<ExampleDistance> bestDistances = null;
      double bestStat = Double.NEGATIVE_INFINITY;
      E bestShapelet = null;
      double bestSum = 0;

      for (E shapelet : shapelets) {
        List<ExampleDistance> distances = new ArrayList<>();
        IntDoubleMap distanceMap = new IntDoubleOpenHashMap();
        double sum = 0;
        for (Example example : classSet) {
          In record = x.get(example.getIndex());
          double dist = patternDistance.computeDistance(record, shapelet);
          distanceMap.put(example.getIndex(), dist);
          distances.add(new ExampleDistance(dist, example));
          sum += dist;
        }
        double stat = assessFstatShapeletQuality(distances, y);
        if (stat > bestStat || bestDistances == null) {
          bestStat = stat;
          bestDistanceMap = distanceMap;
          bestDistances = distances;
          bestShapelet = shapelet;
          bestSum = sum;
        }
      }

      Threshold t = findBestThreshold(bestDistances, classSet, y, bestSum, -1);
      TreeSplit<PatternTree.Threshold<E>> split =
          split(bestDistanceMap, classSet, t.threshold, bestShapelet);
      split.setImpurity(t.impurity);
      return split;
    }

    private double assessFstatShapeletQuality(List<ExampleDistance> distances, Output<?> y) {
      ObjectDoubleMap<Object> sums = new ObjectDoubleOpenHashMap<>();
      ObjectDoubleMap<Object> sumsSquared = new ObjectDoubleOpenHashMap<>();
      ObjectDoubleMap<Object> sumOfSquares = new ObjectDoubleOpenHashMap<>();
      ObjectIntMap<Object> sizes = new ObjectIntOpenHashMap<>();

      int numInstances = distances.size();
      for (ExampleDistance distance : distances) {
        Object c = y.get(distance.example.getIndex()); // getClassVal
        double thisDist = distance.distance; // getDistance
        sizes.addTo(c, 1);
        sums.addTo(c, thisDist); // sums[c] += thisDist
        sumOfSquares.addTo(c, thisDist * thisDist); // sumsOfSquares[c] += thisDist + thisDist
      }
      //
      double part1 = 0;
      double part2 = 0;
      for (ObjectDoubleCursor<Object> sum : sums) {
        sumsSquared.put(sum.key, sum.value * sum.value); // sumsSquared[i] = sums[i] * sums[i]
        part1 += sumOfSquares.get(sum.key); // sumOfSquares[i]
        part2 += sum.value; // sums[i]
      }
      part2 *= part2;
      part2 /= numInstances;
      double ssTotal = part1 - part2;

      part1 = 0;
      part2 = 0;
      for (ObjectDoubleCursor<Object> c : sumsSquared) {
        part1 += c.value / sizes.get(c.key);
        part2 += sums.get(c.key);
      }
      double ssAmong = part1 - (part2 * part2) / numInstances;
      double ssWithin = ssTotal - ssAmong;
      int dfAmong = sums.size() - 1;
      int dfWithin = numInstances - sums.size();
      double msAmong = ssAmong / dfAmong;
      double msWithin = ssWithin / dfWithin;
      double f = msAmong / msWithin;
      return Double.isNaN(f) ? 0 : f;
    }

    public Threshold findBestThreshold(List<ExampleDistance> distances, ClassSet classSet,
        Output<?> y, double distanceSum, int firstNa) {
      ObjectDoubleMap<Object> lt = new ObjectDoubleOpenHashMap<>();
      ObjectDoubleMap<Object> gt = new ObjectDoubleOpenHashMap<>();

      List<Object> presentTargets = classSet.getTargets();
      DoubleArray ltRelativeFrequency = DoubleArray.zeros(presentTargets.size());
      DoubleArray gtRelativeFrequency = DoubleArray.zeros(presentTargets.size());

      double ltWeight = 0.0, gtWeight = 0.0;

      // Initialize all value to the right (i.e. all values are larger than the initial threshold)
      for (ClassSet.Sample sample : classSet.samples()) {
        double weight = sample.getWeight();
        gtWeight += weight;

        lt.put(sample.getTarget(), 0);
        gt.put(sample.getTarget(), weight);
      }
      ExampleDistance ed = distances.get(0);

      // Transfer weights from the initial example
      Example first = ed.example;
      Object prevTarget = y.get(first.getIndex());
      gt.addTo(prevTarget, -first.getWeight());
      lt.addTo(prevTarget, first.getWeight());
      gtWeight -= first.getWeight();
      ltWeight += first.getWeight();

      double prevDistance = ed.distance;
      double lowestImpurity = Double.POSITIVE_INFINITY;
      double threshold = Double.isFinite(ed.distance) ? ed.distance / 2 : 0;
      Gain gain = getGain();
      double ltGap = 0.0, gtGap = distanceSum, largestGap = Double.NEGATIVE_INFINITY;
      for (int i = 1; i < distances.size(); i++) {
        if (firstNa >= 0 && i >= firstNa) {
          break;
        }
        ed = distances.get(i);
        Object target = y.get(ed.example.getIndex());

        // IF previous target NOT EQUALS current target and the previous distance equals the current
        // (except for the first)
        boolean notSameDistance = ed.distance != prevDistance;
        boolean firstOrEqualTarget = prevTarget == null || !prevTarget.equals(target);
        boolean firstIteration = i == 1;
        if (firstIteration || notSameDistance && firstOrEqualTarget) {

          // Generate the relative frequency distribution
          for (int j = 0; j < presentTargets.size(); j++) {
            Object presentTarget = presentTargets.get(j);
            ltRelativeFrequency.set(j, ltWeight != 0 ? lt.get(presentTarget) / ltWeight : 0);
            gtRelativeFrequency.set(j, gtWeight != 0 ? gt.get(presentTarget) / gtWeight : 0);
          }

          // If this split is better, update the threshold
          double impurity =
              gain.compute(ltWeight, ltRelativeFrequency, gtWeight, gtRelativeFrequency);
          double gap = (1 / ltWeight * ltGap) - (1 / gtWeight * gtGap);
          boolean lowerImpurity = impurity < lowestImpurity;
          boolean equalImpuritySmallerGap = impurity == lowestImpurity && gap > largestGap;
          if (lowerImpurity || equalImpuritySmallerGap) {
            lowestImpurity = impurity;
            largestGap = gap;
            threshold =
                Double.isFinite(ed.distance) ? (ed.distance + prevDistance) / 2 : prevDistance;
          }
        }

        /*
         * Move cursor one example forward, and adjust the weights accordingly. Then calculate the
         * new gain for moving the threshold. If this results in a cleaner split, adjust the
         * threshold (by taking the average of the current and the previous value).
         */
        double weight = ed.example.getWeight();
        ltWeight += weight;
        gtWeight -= weight;
        lt.addTo(target, weight);
        gt.addTo(target, -weight);

        if (Double.isFinite(ed.distance)) {
          ltGap += ed.distance;
          gtGap -= ed.distance;
          prevDistance = ed.distance;
        }

        prevTarget = target;
      }

      double minimumMargin = Double.POSITIVE_INFINITY;
      return new Threshold(threshold, lowestImpurity, largestGap, minimumMargin);
    }

    protected TreeSplit<PatternTree.Threshold<E>> split(IntDoubleMap distanceMap, ClassSet classSet,
        double threshold, E shapelet) {
      ClassSet left = new ClassSet(classSet.getDomain());
      ClassSet right = new ClassSet(classSet.getDomain());
      ClassSet missing = new ClassSet(classSet.getDomain());
      for (ClassSet.Sample sample : classSet.samples()) {
        Object target = sample.getTarget();

        ClassSet.Sample leftSample = ClassSet.Sample.create(target);
        ClassSet.Sample rightSample = ClassSet.Sample.create(target);
        ClassSet.Sample missingSample = ClassSet.Sample.create(target);

        for (Example example : sample) {
          double shapeletDistance = distanceMap.get(example.getIndex());
          // Missing
          if (Is.NA(shapeletDistance)) {
            // missingSample.add(example);
            rightSample.add(example);
          } else {
            if (shapeletDistance <= threshold) {
              leftSample.add(example);
            } else {
              rightSample.add(example);
            }
          }
        }

        if (!leftSample.isEmpty()) {
          left.add(leftSample);
        }
        if (!rightSample.isEmpty()) {
          right.add(rightSample);
        }
        if (!missingSample.isEmpty()) {
          missing.add(missingSample);
        }
      }

      return new TreeSplit<>(left, right, missing,
          new PatternTree.Threshold<>(shapelet, threshold, classSet));
    }

    public enum Assessment {
      IG, FSTAT
    }

    private static class ExampleDistance implements Comparable<ExampleDistance> {

      public static ExampleDistance NA = new ExampleDistance(Na.DOUBLE, null);
      public final double distance;
      public final Example example;

      public ExampleDistance(double distance, Example example) {
        this.distance = distance;
        this.example = example;
      }

      @Override
      public int compareTo(ExampleDistance o) {
        return Double.compare(distance, o.distance);
      }

      @Override
      public boolean equals(Object obj) {
        if (!(obj instanceof ExampleDistance)) {
          return false;
        }
        return compareTo((ExampleDistance) obj) == 0;
      }

      @Override
      public int hashCode() {
        return Double.hashCode(distance);
      }

      @Override
      public String toString() {
        return String.format("ExampleDistance(id=%d, %.2f)", example.getIndex(), distance);
      }
    }

    private static class Threshold {

      public double threshold, impurity, gap, margin;

      public Threshold(double threshold, double impurity, double gap, double margin) {
        this.threshold = threshold;
        this.impurity = impurity;
        this.gap = gap;
        this.margin = margin;
      }

      public static Threshold inf() {
        return new Threshold(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY);
      }

      public boolean isBetterThan(Threshold bestThreshold) {
        return this.impurity < bestThreshold.impurity
            || (this.impurity == bestThreshold.impurity && this.gap > bestThreshold.gap);
      }
    }

    private static class Params {
      public int features;
      public double noExamples;
      private int depth = 0;
    }

    private static class GuessVisitor
        implements TreeVisitor<Vector, PatternTree.Threshold<Shapelet>> {

      private final TreeNode<Vector, PatternTree.Threshold<Shapelet>> root;
      private final Distance<Vector> distanceMeasure;

      private GuessVisitor(TreeNode<Vector, PatternTree.Threshold<Shapelet>> root,
          Distance<Vector> distanceMeasure) {
        this.root = root;
        this.distanceMeasure = distanceMeasure;
      }

      @Override
      public TreeNode<Vector, PatternTree.Threshold<Shapelet>> getRoot() {
        return root;
      }

      @Override
      public DoubleArray visitLeaf(TreeLeaf<Vector, PatternTree.Threshold<Shapelet>> leaf,
          Vector example) {
        return leaf.getProbabilities();
      }

      @Override
      public DoubleArray visitBranch(TreeBranch<Vector, PatternTree.Threshold<Shapelet>> node,
          Vector example) {
        Shapelet shapelet = node.getThreshold().getPattern();
        Vector useExample = example;
        if (shapelet instanceof ChannelShapelet) {
          int channelIndex = ((ChannelShapelet) shapelet).getChannel();
          useExample = example.loc().get(Vector.class, channelIndex);
        }


        double threshold = node.getThreshold().getDistance();
        if (shapelet.size() > useExample.size()) {
          Vector mcd = node.getThreshold().getClassDistances();
          double d = distanceMeasure.compute(useExample, shapelet);
          double sqrt = Math.sqrt((d * d * useExample.size()) / shapelet.size());
          // if (sqrt < threshold) {
          // return visit(node.getLeft(), example);
          // } else {
          // return visit(node.getRight(), example);
          // }
          //
          double min = Double.POSITIVE_INFINITY;
          Object minKey = null;
          for (Object key : mcd.getIndex()) {
            double dist = Math.abs(sqrt - mcd.getAsDouble(key));
            if (dist < min) {
              min = dist;
              minKey = key;
            }
          }

          double left =
              node.getLeft().getClassDistribution().getAsDouble(minKey, Double.NEGATIVE_INFINITY);
          double right =
              node.getRight().getClassDistribution().getAsDouble(minKey, Double.NEGATIVE_INFINITY);
          if (left > right) {
            return visit(node.getLeft(), example);
          } else {
            return visit(node.getRight(), example);
          }
          // DoubleArray doubleArray = Arrays.doubleArray(node.getDomain().size());
          // for (int i = 0; i < node.getDomain().size(); i++) {
          // if (minKey.equals(node.getDomain().loc().get(Object.class, i))) {
          // doubleArray.set(i, 1);
          // break;
          // }
          // }
          // return doubleArray;
        } else {
          double computedDistance;
          // if (shapelet instanceof ChannelShapelet) {
          // int shapeletChannel = ((ChannelShapelet) shapelet).getChannel();
          // Vector exampleChannel = example.loc().get(Vector.class, shapeletChannel);
          // computedDistance = distanceMeasure.compute(exampleChannel, shapelet);
          // } else {
          computedDistance = distanceMeasure.compute(useExample, shapelet);
          // }
          if (computedDistance < threshold) {
            return visit(node.getLeft(), example);
          } else {
            return visit(node.getRight(), example);
          }
        }
      }
    }

    private static class OneNnVisitor
        implements TreeVisitor<Vector, PatternTree.Threshold<Shapelet>> {

      public static final EuclideanDistance EUCLIDEAN = EuclideanDistance.getInstance();
      private final TreeNode<Vector, PatternTree.Threshold<Shapelet>> root;
      private final Distance<Vector> distanceMeasure;
      private final DataFrame x;
      private final Vector y;

      private OneNnVisitor(TreeNode<Vector, PatternTree.Threshold<Shapelet>> root,
          Distance<Vector> distanceMeasure, DataFrame x, Vector y) {
        this.root = root;
        this.distanceMeasure = distanceMeasure;
        this.x = x;
        this.y = y;
      }

      @Override
      public TreeNode<Vector, PatternTree.Threshold<Shapelet>> getRoot() {
        return root;
      }

      @Override
      public DoubleArray visitLeaf(TreeLeaf<Vector, PatternTree.Threshold<Shapelet>> leaf,
          Vector example) {
        return leaf.getProbabilities();
      }

      @Override
      public DoubleArray visitBranch(TreeBranch<Vector, PatternTree.Threshold<Shapelet>> node,
          Vector example) {
        Shapelet shapelet = node.getThreshold().getPattern();
        double threshold = node.getThreshold().getDistance();
        if (shapelet.size() >= example.size()) {
          // Use 1NN
          ClassSet included = node.getThreshold().getClassSet();
          double minDistance = Double.POSITIVE_INFINITY;
          Object cls = null;
          for (Example ex : included) {
            double distance = EUCLIDEAN.compute(example.loc(), x.getRecord(ex.getIndex()).loc());
            if (distance < minDistance) {
              minDistance = distance;
              cls = y.loc().get(Object.class, ex.getIndex());
            }
          }
          double left =
              node.getLeft().getClassDistribution().getAsDouble(cls, Double.NEGATIVE_INFINITY);
          double right =
              node.getRight().getClassDistribution().getAsDouble(cls, Double.NEGATIVE_INFINITY);
          if (left > right) {
            return visit(node.getLeft(), example);
          } else {
            return visit(node.getRight(), example);
          }
          // DoubleArray doubleArray = Arrays.doubleArray(node.getDomain().size());
          // for (int i = 0; i < node.getDomain().size(); i++) {
          // if (cls.equals(node.getDomain().loc().get(Object.class, i))) {
          // doubleArray.set(i, 1);
          // break;
          // }
          // }
          // return doubleArray;
        } else {
          double computedDistance;
          if (shapelet instanceof ChannelShapelet) {
            int shapeletChannel = ((ChannelShapelet) shapelet).getChannel();
            Vector exampleChannel = example.loc().get(Vector.class, shapeletChannel);
            computedDistance = distanceMeasure.compute(exampleChannel, shapelet);
          } else {
            computedDistance = distanceMeasure.compute(example, shapelet);
          }
          if (computedDistance < threshold) {
            return visit(node.getLeft(), example);
          } else {
            return visit(node.getRight(), example);
          }
        }
      }
    }


    private static class WeightVisitor
        implements TreeVisitor<Vector, PatternTree.Threshold<Shapelet>> {

      private final TreeNode<Vector, PatternTree.Threshold<Shapelet>> root;
      private final Distance<Vector> distanceMeasure;
      private final double weight;

      public WeightVisitor(TreeNode<Vector, PatternTree.Threshold<Shapelet>> root,
          Distance<Vector> distanceMeasure) {
        this(root, distanceMeasure, 1);
      }

      private WeightVisitor(TreeNode<Vector, PatternTree.Threshold<Shapelet>> root,
          Distance<Vector> distanceMeasure, double weight) {
        this.root = root;
        this.distanceMeasure = distanceMeasure;
        this.weight = weight;
      }

      @Override
      public TreeNode<Vector, PatternTree.Threshold<Shapelet>> getRoot() {
        return root;
      }

      @Override
      public DoubleArray visitLeaf(TreeLeaf<Vector, PatternTree.Threshold<Shapelet>> leaf,
          Vector example) {
        return leaf.getProbabilities().times(weight);
      }

      @Override
      public DoubleArray visitBranch(TreeBranch<Vector, PatternTree.Threshold<Shapelet>> node,
          Vector example) {
        Shapelet shapelet = node.getThreshold().getPattern();
        double threshold = node.getThreshold().getDistance();
        Vector useExample = example;
        if (shapelet instanceof ChannelShapelet) {
          int channelIndex = ((ChannelShapelet) shapelet).getChannel();
          useExample = example.loc().get(Vector.class, channelIndex);
        }


        if (shapelet.size() > useExample.size()) {
          WeightVisitor leftVisitor =
              new WeightVisitor(root, distanceMeasure, node.getLeft().getWeight());
          WeightVisitor rightVisitor =
              new WeightVisitor(root, distanceMeasure, node.getRight().getWeight());

          DoubleArray leftProbabilities = leftVisitor.visit(node.getLeft(), example);
          DoubleArray rightProbabilities = rightVisitor.visit(node.getRight(), example);
          return leftProbabilities.plus(rightProbabilities);
        } else {
          WeightVisitor visitor = new WeightVisitor(root, distanceMeasure, weight);
          double computedDistance;
          computedDistance = distanceMeasure.compute(useExample, shapelet);
          if (computedDistance < threshold) {
            return visitor.visit(node.getLeft(), example);
          } else {
            return visitor.visit(node.getRight(), example);
          }
        }
      }
    }

    private static class DefaultPatternTreeVisitor<In, E>
        implements TreeVisitor<In, PatternTree.Threshold<E>> {

      private final TreeNode<In, PatternTree.Threshold<E>> root;
      private final PatternDistance<? super In, ? super E> patternDistance;

      private DefaultPatternTreeVisitor(TreeNode<In, PatternTree.Threshold<E>> root,
          PatternDistance<? super In, ? super E> patternDistance) {
        this.root = root;
        this.patternDistance = patternDistance;
      }

      @Override
      public TreeNode<In, PatternTree.Threshold<E>> getRoot() {
        return root;
      }

      @Override
      public DoubleArray visitLeaf(TreeLeaf<In, PatternTree.Threshold<E>> leaf, In example) {
        return leaf.getProbabilities();
      }

      @Override
      public DoubleArray visitBranch(TreeBranch<In, PatternTree.Threshold<E>> node, In example) {
        E shapelet = node.getThreshold().getPattern();
        double threshold = node.getThreshold().getDistance();
        double computedDistance = patternDistance.computeDistance(example, shapelet);
        if (Is.NA(computedDistance)) {
          if (node.getMissing() != null) {
            return visit(node.getMissing(), example);
          }
          return visit(node.getRight(), example);
        } else {
          if (computedDistance < threshold) {
            return visit(node.getLeft(), example);
          } else {
            return visit(node.getRight(), example);
          }
        }
      }
    }
  }

  public static class Configurator<T, E>
      implements Predictor.Configurator<T, Object, Learner<T, E>> {
    public Learner.Assessment assessment = Learner.Assessment.IG;

    public PatternFactory<? super T, ? extends E> patternFactory;
    public PatternDistance<? super T, ? super E> patternDistance;

    public double minSplit = 1;
    public int patternCount = 100;

    public Configurator(PatternFactory<? super T, ? extends E> patternFactory,
        PatternDistance<? super T, ? super E> patternDistance) {
      this.patternFactory = Objects.requireNonNull(patternFactory);
      this.patternDistance = Objects.requireNonNull(patternDistance);
    }

    public Configurator<T, E> setPatternDistance(
        PatternDistance<? super T, ? super E> patternDistance) {
      this.patternDistance = patternDistance;
      return this;
    }

    public Configurator<T, E> setPatternFactory(
        PatternFactory<? super T, ? extends E> patternFactory) {
      this.patternFactory = patternFactory;
      return this;
    }

    public Configurator<T, E> setMinimumSplit(double minSplit) {
      this.minSplit = minSplit;
      return this;
    }

    public Configurator<T, E> setAssessment(Learner.Assessment assessment) {
      this.assessment = assessment;
      return this;
    }

    public Configurator<T, E> setPatternCount(int patternCount) {
      this.patternCount = patternCount;
      return this;

    }

    public Learner<T, E> configure() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * @author Isak Karlsson
   */
  public static class Threshold<T> {

    private final T pattern;
    private final double distance;
    private Vector classDistances;
    private ClassSet classSet;

    public Threshold(T pattern, double distance, ClassSet classSet) {
      this.pattern = pattern;
      this.distance = distance;
      this.classSet = classSet;
    }

    public ClassSet getClassSet() {
      return classSet;
    }

    public T getPattern() {
      return pattern;
    }

    public double getDistance() {
      return distance;
    }

    public Vector getClassDistances() {
      return classDistances;
    }

    public void setClassDistances(Vector classDistances) {
      this.classDistances = classDistances;
    }
  }
}
