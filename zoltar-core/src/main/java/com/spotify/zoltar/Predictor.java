/*-
 * -\-\-
 * zoltar-core
 * --
 * Copyright (C) 2016 - 2018 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.zoltar;

import com.spotify.zoltar.PredictFns.AsyncPredictFn;
import com.spotify.zoltar.PredictFns.PredictFn;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entry point for prediction, it allows to perform E2E prediction given a recipe made of a {@link
 * Model}, {@link FeatureExtractor} and a {@link PredictFn}. In most cases you should use the static
 * factory methods.
 *
 * @param <InputT> type of the feature extraction input.
 * @param <ValueT> type of the prediction output.
 */
@FunctionalInterface
public interface Predictor<InputT, ValueT> {

  /** Default scheduler for predict functions. */
  ScheduledExecutorService SCHEDULER =
      Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors());

  /**
   * Returns a predictor given a {@link Model}, {@link FeatureExtractor} and a {@link PredictFn}.
   *
   * @param model model to perform prediction on.
   * @param featureExtractor a feature extractor to use to transform input into extracted features.
   * @param predictFn a prediction function to perform prediction with {@link PredictFn}.
   * @param <ModelT> underlying type of the {@link Model}.
   * @param <InputT> type of the input to the {@link FeatureExtractor}.
   * @param <VectorT> type of the output from {@link FeatureExtractor}.
   * @param <ValueT> type of the prediction result.
   */
  static <ModelT extends Model<?>, InputT, VectorT, ValueT> Predictor<InputT, ValueT> create(
      final ModelT model,
      final FeatureExtractor<InputT, VectorT> featureExtractor,
      final PredictFn<ModelT, InputT, VectorT, ValueT> predictFn) {
    return create(model, featureExtractor, AsyncPredictFn.lift(predictFn));
  }

  /**
   * Returns a predictor given a {@link Model}, {@link FeatureExtractor} and a {@link
   * AsyncPredictFn}.
   *
   * @param model model to perform prediction on.
   * @param featureExtractor a feature extractor to use to transform input into extracted features.
   * @param predictFn a prediction function to perform prediction with {@link AsyncPredictFn}.
   * @param <ModelT> underlying type of the {@link Model}.
   * @param <InputT> type of the input to the {@link FeatureExtractor}.
   * @param <VectorT> type of the output from {@link FeatureExtractor}.
   * @param <ValueT> type of the prediction result.
   */
  static <ModelT extends Model<?>, InputT, VectorT, ValueT> Predictor<InputT, ValueT> create(
      final ModelT model,
      final FeatureExtractor<InputT, VectorT> featureExtractor,
      final AsyncPredictFn<ModelT, InputT, VectorT, ValueT> predictFn) {
    return (input, timeout, scheduler) -> {
      final List<Vector<InputT, VectorT>> vectors = featureExtractor.extract(input);

      final CompletableFuture<List<Prediction<InputT, ValueT>>> future =
          predictFn.apply(model, vectors).toCompletableFuture();

      final ScheduledFuture<?> schedule = scheduler.schedule(() -> {
        future.completeExceptionally(new TimeoutException());
      }, timeout.toMillis(), TimeUnit.MILLISECONDS);

      future.whenComplete((r, t) -> schedule.cancel(true));

      return future;
    };
  }

  /**
   * Functional interface. You should perform E2E feature extraction and prediction. See {@link
   * Predictor#create(Model, FeatureExtractor, AsyncPredictFn)} for an example of usage.
   *
   * @param input a list of inputs to perform feature extraction and prediction on.
   * @param timeout implementation specific timeout, see {@link Predictor#create(Model,
   * FeatureExtractor, AsyncPredictFn)} for an example of usage.
   * @param scheduler implementation specific scheduler, see {@link Predictor#create(Model,
   * FeatureExtractor, AsyncPredictFn)} for an example of usage.
   */
  CompletionStage<List<Prediction<InputT, ValueT>>> predict(List<InputT> input,
                                                            Duration timeout,
                                                            ScheduledExecutorService scheduler)
      throws Exception;

  /** Perform prediction with a default scheduler. */
  default CompletionStage<List<Prediction<InputT, ValueT>>> predict(final List<InputT> input,
                                                                    final Duration timeout)
      throws Exception {
    return predict(input, timeout, SCHEDULER);
  }

  /** Perform prediction with a default scheduler, and practically infinite timeout. */
  default CompletionStage<List<Prediction<InputT, ValueT>>> predict(final List<InputT> input)
      throws Exception {
    return predict(input, Duration.ofDays(Integer.MAX_VALUE), SCHEDULER);
  }

}
